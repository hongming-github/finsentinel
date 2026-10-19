package com.finsentinel.platform.cases;

import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.platform.observability.Traces;
import com.finsentinel.platform.security.Tenancy;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import com.finsentinel.common.Alert;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.analysis.LostClaimException;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationProperties;
import com.finsentinel.platform.investigation.InvestigationResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Claims cases in ANALYSIS and runs the analysis graph on them, at most {@code concurrency} at once on this instance.
 * A pool, not batches: a finished run claims the next case at once, so one slow analysis holds its own slot and no
 * other (ADR 0025 Next; drills 2, 3 and 7b, ADR 0026). The poll only matters when the slots fell idle; a heartbeat
 * renews the claims of the runs in flight (drill 10).
 *
 * <p>Safe on several instances: the schedules are only local alarm clocks. Coordination lives in Postgres
 * ({@code SKIP LOCKED} claims, {@code claimed_at} fencing, an advisory lock around the budget check); the in-flight set
 * is this instance's own runs, used to size its claims and renew them.
 */
@Component
public class CaseWorker {

    private static final Logger log = LoggerFactory.getLogger(CaseWorker.class);

    private final CaseRepository cases;
    private final AlertRepository alerts;
    private final CaseAnalysisGraph analysis;
    private final InvestigationProperties props;
    private final SystemsProperties systems;
    private final AtomicLong lastBudgetWarning = new AtomicLong();
    private final Tracer tracer;
    private final PipelineMetrics metrics;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    /** The claims this instance is running. */
    private final Set<CaseClaim> inFlight = ConcurrentHashMap.newKeySet();
    /** One claim at a time per instance, so the free slots are counted before they are filled. */
    private final Object claiming = new Object();
    private volatile boolean stopped;

    CaseWorker(CaseRepository cases, AlertRepository alerts, CaseAnalysisGraph analysis,
               InvestigationProperties props, SystemsProperties systems, Tracer tracer, PipelineMetrics metrics) {
        this.tracer = tracer;
        this.metrics = metrics;
        this.systems = systems;
        this.cases = cases;
        this.alerts = alerts;
        this.analysis = analysis;
        this.props = props;
    }

    /** Fills the free slots; the runs then keep them filled until the queue is empty. */
    @Scheduled(fixedDelayString = "${finsentinel.investigation.poll-interval:5s}", initialDelayString = "5s")
    void poll() {
        if (props.enabled()) {
            failAbandoned();
            fill(true);
        }
    }

    @Scheduled(fixedDelayString = "${finsentinel.investigation.claim-heartbeat:10s}",
            initialDelayString = "${finsentinel.investigation.claim-heartbeat:10s}")
    void heartbeat() {
        if (props.enabled()) {
            renewInFlight();
        }
    }

    @PreDestroy
    void stop() {
        // Runs in flight finish if they can; their claims lapse otherwise, and fencing covers the rest.
        stopped = true;
        runs.shutdown();
    }

    /**
     * Claims cases for the free slots, runs them and waits for them, renewing their claims meanwhile; a finished run
     * does not claim another. Public so tests can drive the queue deterministically.
     *
     * @return the cases claimed
     */
    public int runOnce() {
        failAbandoned();
        List<Future<?>> started = fill(false);
        renewWhileRunning(started);
        return started.size();
    }

    private void failAbandoned() {
        // Queue work spans every bank: the system scope, the only place outside tests that uses it.
        int abandoned = Tenancy.callAsSystem(() -> cases.failAbandoned(props.claimTimeout(), props.claimLease(),
                props.maxAttempts()));
        if (abandoned > 0) {
            log.warn("Marked {} abandoned analyses as FAILED", abandoned);
        }
    }

    /**
     * Claims as many cases as there are free slots and starts them.
     *
     * @param refill whether each finished run fills its slot again (the pool) or not ({@link #runOnce})
     */
    private List<Future<?>> fill(boolean refill) {
        synchronized (claiming) {
            int free = props.concurrency() - inFlight.size();
            if (stopped || free <= 0) {
                return List.of();
            }
            List<Future<?>> started = new ArrayList<>();
            for (CaseClaim claim : claim(free)) {
                inFlight.add(claim);
                started.add(runs.submit(() -> run(claim, refill)));
            }
            return started;
        }
    }

    private List<CaseClaim> claim(int limit) {
        CaseRepository.ClaimBatch batch;
        try {
            batch = Tenancy.callAsSystem(() -> cases.claimWithinBudget(limit, props.claimTimeout(),
                    props.claimLease(), props.maxAttempts(), props.maxPerHour(), props.claimLockTimeout()));
        } catch (DataAccessException e) {
            if (!CaseRepository.isLockTimeout(e)) {
                throw e;
            }
            // Another instance holds the claim lock for unusually long. Nothing was claimed; try next poll.
            log.warn("Claim lock not acquired within {}; skipping this poll", props.claimLockTimeout());
            return List.of();
        }
        if (batch.budgetExhausted()) {
            warnBudgetExhausted();
            return List.of();
        }
        return batch.claims();
    }

    private void run(CaseClaim claim, boolean refill) {
        try {
            process(claim);
        } catch (RuntimeException e) {
            // Outside the analysis's own handling (the database gone while recording a failure): the claim lapses and
            // the case is taken again.
            log.error("Processing case {} failed outside its analysis; the claim will lapse and be retried",
                    claim.fraudCase().caseId(), e);
        } finally {
            inFlight.remove(claim);
        }
        if (refill) {
            try {
                fill(true);
            } catch (RuntimeException e) {
                log.warn("Could not claim the next case: {}; the next poll tries again", e.toString());
            }
        }
    }

    /** Waits for the given runs, renewing the claims in flight every heartbeat until they are done (drill 10). */
    private void renewWhileRunning(List<Future<?>> started) {
        long heartbeatNanos = props.claimHeartbeat().toNanos();
        while (true) {
            long next = System.nanoTime() + heartbeatNanos;
            for (Future<?> run : started) {
                long wait = next - System.nanoTime();
                if (wait <= 0) {
                    break;
                }
                try {
                    run.get(wait, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    break;
                } catch (ExecutionException | CancellationException e) {
                    // run() logs its own failures.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (started.stream().allMatch(Future::isDone)) {
                return;
            }
            renewInFlight();
        }
    }

    /**
     * Renews the claims of the runs in flight, so they do not lapse after the lease. Only a live process renews, so a
     * killed one's claims lapse. A renewal that fails (the database gone) is skipped: the claim may lapse and be taken
     * over, and fencing then stops this run from writing.
     */
    private void renewInFlight() {
        List<CaseClaim> running = List.copyOf(inFlight);
        if (running.isEmpty()) {
            return;
        }
        try {
            Tenancy.callAsSystem(() -> cases.renew(running));
        } catch (DataAccessException e) {
            log.warn("Could not renew {} claims: {}", running.size(), e.toString());
        }
    }

    /** In the claimed case's tenant scope: everything the analysis reads or writes belongs to that bank. */
    private void process(CaseClaim claim) {
        Tenancy.run(claim.fraudCase().tenantId(), () -> analyse(claim));
    }

    /**
     * The analysis continues the trace of whatever started this run (ADR 0020): the alert that opened or reopened the
     * case, or the analyst's request. The case's other alerts are linked, so each alert's trace leads here too.
     */
    private void analyse(CaseClaim claim) {
        FraudCase c = claim.fraudCase();
        CaseRepository.RunTrace trace = cases.runTrace(c.tenantId(), c.caseId());
        SpanBuilder builder = tracer.spanBuilder("analyse case")
                .setParent(Traces.context(trace.parent()))
                .setAttribute("finsentinel.tenant", c.tenantId())
                .setAttribute("finsentinel.case_id", c.caseId())
                .setAttribute("finsentinel.run", c.analysisRun())
                .setAttribute("finsentinel.attempt", c.attempts());
        trace.alerts().stream().filter(alert -> !alert.equals(trace.parent()))
                .map(alert -> Span.fromContext(Traces.context(alert)).getSpanContext())
                .filter(SpanContext::isValid)
                .forEach(builder::addLink);
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            analyseInSpan(claim, span);
        } finally {
            span.end();
        }
    }

    private void analyseInSpan(CaseClaim claim, Span span) {
        FraudCase c = claim.fraudCase();
        try {
            List<StoredAlert> stored = alerts.findByCase(c.tenantId(), c.caseId());
            List<Alert> caseAlerts = stored.stream().map(StoredAlert::alert).toList();
            CaseContext context = new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts,
                    c.analysisRun(), c.analystNote(), systems.connectedFor(c.tenantId()));
            CaseAnalysisGraph.Outcome outcome = analysis.analyse(context, claim.claimedAt());
            InvestigationResult result = outcome.investigation();
            CaseRepository.Completion completion = cases.complete(claim, outcome.mode(), result, outcome.review());
            metrics.analysed(stored, claim);
            log.info("Case {} run {} ({} alerts, {}): {} {} {} risk={} review={} tools={} calls={} tokens={}/{} "
                            + "cached={} written={} cost=${} {}ms{}",
                    c.caseId(), c.analysisRun(), caseAlerts.size(), context.rules(), outcome.mode(), completion,
                    result.report().disposition(), result.report().riskScore(),
                    outcome.review().verdict(),
                    result.toolCalls().size(),
                    result.llmCalls(), result.inputTokens(), result.outputTokens(), result.cachedInputTokens(),
                    result.cacheWriteTokens(), result.costUsd(), result.latencyMs(),
                    outcome.resumedAfter() == null ? "" : " resumed after " + outcome.resumedAfter());
            span.setAttribute("finsentinel.mode", outcome.mode().name());
            span.setAttribute("finsentinel.disposition", result.report().disposition().name());
            span.setAttribute("finsentinel.completion", completion.toString());
            span.setAttribute("finsentinel.cost_usd", result.costUsd().doubleValue());
            if (outcome.resumedAfter() != null) {
                span.setAttribute("finsentinel.resumed_after", outcome.resumedAfter());
            }
        } catch (LostClaimException e) {
            // Our claim timed out and another worker owns the case now: its result counts, ours must not.
            log.warn("Abandoning analysis of case {}: {}", c.caseId(), e.getMessage());
            span.setAttribute("finsentinel.completion", "LOST_CLAIM");
        } catch (RuntimeException e) {
            log.warn("Analysis of case {} failed: {}", c.caseId(), e.toString());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.toString());
            cases.fail(claim, props.maxAttempts(), props.retry().pauseAfter(c.attempts()), e.toString());
        }
    }

    private void warnBudgetExhausted() {
        long now = System.currentTimeMillis();
        long last = lastBudgetWarning.get();
        if (now - last > 60_000 && lastBudgetWarning.compareAndSet(last, now)) {
            log.warn("Hourly analysis budget of {} reached; cases stay queued until it frees up", props.maxPerHour());
        }
    }
}
