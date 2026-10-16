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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Claims cases in ANALYSIS and runs the analysis graph on them. Each poll claims at most {@code batchSize} cases and waits for them,
 * so {@code batchSize} caps concurrent analyses on this instance.
 *
 * <p>Safe on several instances: the schedule is only a local alarm clock. Coordination lives in Postgres
 * ({@code SKIP LOCKED} claims, {@code claimed_at} fencing, an advisory lock around the budget check).
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

    CaseWorker(CaseRepository cases, AlertRepository alerts, CaseAnalysisGraph analysis,
               InvestigationProperties props, SystemsProperties systems, Tracer tracer) {
        this.tracer = tracer;
        this.systems = systems;
        this.cases = cases;
        this.alerts = alerts;
        this.analysis = analysis;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${finsentinel.investigation.poll-interval:5s}", initialDelayString = "5s")
    void poll() {
        if (props.enabled()) {
            runOnce();
        }
    }

    /** One poll cycle. Public so tests can drive the queue deterministically. */
    public int runOnce() {
        // Queue work spans every bank: the system scope, the only place outside tests that uses it.
        int abandoned = Tenancy.callAsSystem(() -> cases.failAbandoned(props.claimTimeout(), props.maxAttempts()));
        if (abandoned > 0) {
            log.warn("Marked {} abandoned analyses as FAILED", abandoned);
        }
        CaseRepository.ClaimBatch batch;
        try {
            batch = Tenancy.callAsSystem(() -> cases.claimWithinBudget(props.batchSize(), props.claimTimeout(),
                    props.maxAttempts(), props.maxPerHour(), props.claimLockTimeout()));
        } catch (DataAccessException e) {
            if (!CaseRepository.isLockTimeout(e)) {
                throw e;
            }
            // Another instance holds the claim lock for unusually long. Nothing was claimed; try next poll.
            log.warn("Claim lock not acquired within {}; skipping this poll", props.claimLockTimeout());
            return 0;
        }
        if (batch.budgetExhausted()) {
            warnBudgetExhausted();
            return 0;
        }
        List<CaseClaim> claims = batch.claims();
        List<Future<?>> runs = new ArrayList<>(claims.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claims.forEach(claim -> runs.add(executor.submit(() -> process(claim))));
        }
        // Closing the executor waited for every run. A failure outside the analysis's own handling (the database gone
        // while recording a failure) would otherwise vanish with its future; the claim times out and is retried.
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).state() == Future.State.FAILED) {
                log.error("Processing case {} failed outside its analysis; the claim will time out and be retried",
                        claims.get(i).fraudCase().caseId(), runs.get(i).exceptionNow());
            }
        }
        return claims.size();
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
            List<Alert> caseAlerts = alerts.findByCase(c.tenantId(), c.caseId()).stream().map(StoredAlert::alert).toList();
            CaseContext context = new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts,
                    c.analysisRun(), c.analystNote(), systems.connectedFor(c.tenantId()));
            CaseAnalysisGraph.Outcome outcome = analysis.analyse(context, claim.claimedAt());
            InvestigationResult result = outcome.investigation();
            CaseRepository.Completion completion = cases.complete(claim, outcome.mode(), result, outcome.review());
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
            cases.fail(claim, props.maxAttempts(), e.toString());
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
