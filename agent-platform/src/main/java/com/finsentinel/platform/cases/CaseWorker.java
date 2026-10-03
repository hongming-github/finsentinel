package com.finsentinel.platform.cases;

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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final AtomicLong lastBudgetWarning = new AtomicLong();

    CaseWorker(CaseRepository cases, AlertRepository alerts, CaseAnalysisGraph analysis,
               InvestigationProperties props) {
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
        int abandoned = cases.failAbandoned(props.claimTimeout(), props.maxAttempts());
        if (abandoned > 0) {
            log.warn("Marked {} abandoned analyses as FAILED", abandoned);
        }
        CaseRepository.ClaimBatch batch;
        try {
            batch = cases.claimWithinBudget(props.batchSize(), props.claimTimeout(), props.maxAttempts(),
                    props.maxPerHour(), props.claimLockTimeout());
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
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claims.forEach(claim -> executor.submit(() -> process(claim)));
        }
        return claims.size();
    }

    private void process(CaseClaim claim) {
        FraudCase c = claim.fraudCase();
        try {
            List<Alert> caseAlerts = alerts.findByCase(c.tenantId(), c.caseId()).stream().map(StoredAlert::alert).toList();
            CaseContext context = new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts,
                    c.analysisRun(), c.analystNote());
            CaseAnalysisGraph.Outcome outcome = analysis.analyse(context, claim.claimedAt());
            InvestigationResult result = outcome.investigation();
            CaseRepository.Completion completion = cases.complete(claim, outcome.mode(), result, outcome.review());
            log.info("Case {} run {} ({} alerts, {}): {} {} {} risk={} review={} tools={} calls={} tokens={}/{} "
                            + "cached={} cost=${} {}ms{}",
                    c.caseId(), c.analysisRun(), caseAlerts.size(), context.rules(), outcome.mode(), completion,
                    result.report().disposition(), result.report().riskScore(),
                    outcome.review().approved() ? "approved" : "rejected " + outcome.review().issues(),
                    result.toolCalls().size(),
                    result.llmCalls(), result.inputTokens(), result.outputTokens(), result.cachedInputTokens(),
                    result.costUsd(), result.latencyMs(),
                    outcome.resumedAfter() == null ? "" : " resumed after " + outcome.resumedAfter());
        } catch (LostClaimException e) {
            // Our claim timed out and another worker owns the case now: its result counts, ours must not.
            log.warn("Abandoning analysis of case {}: {}", c.caseId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Analysis of case {} failed: {}", c.caseId(), e.toString());
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
