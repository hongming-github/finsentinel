package com.finsentinel.platform.investigation;

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
 * Polls the queue and runs investigations. Each poll claims at most {@code batchSize} alerts and waits
 * for them to finish, so {@code batchSize} is a hard cap on concurrent LLM calls from this instance.
 *
 * <p>Safe to run on several instances: the schedule is only a local alarm clock. Coordination lives in Postgres
 * ({@code SKIP LOCKED} claims, {@code claimed_at} fencing, an advisory lock around the budget check), so no
 * alert is investigated twice and the hourly budget holds across instances.
 */
@Component
public class InvestigationWorker {

    private static final Logger log = LoggerFactory.getLogger(InvestigationWorker.class);

    private final InvestigationRepository queue;
    private final CaseInvestigator investigator;
    private final InvestigationProperties props;
    private final AtomicLong lastBudgetWarning = new AtomicLong();

    InvestigationWorker(InvestigationRepository queue, CaseInvestigator investigator, InvestigationProperties props) {
        this.queue = queue;
        this.investigator = investigator;
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
        int abandoned = queue.failAbandoned(props.claimTimeout(), props.maxAttempts());
        if (abandoned > 0) {
            log.warn("Marked {} abandoned investigations as FAILED", abandoned);
        }
        InvestigationRepository.ClaimBatch batch;
        try {
            batch = queue.claimWithinBudget(props.batchSize(), props.claimTimeout(), props.maxAttempts(),
                    props.maxPerHour(), props.claimLockTimeout());
        } catch (DataAccessException e) {
            if (!InvestigationRepository.isLockTimeout(e)) {
                throw e;
            }
            // Another instance holds the claim lock for unusually long. The transaction rolled back, so nothing
            // was claimed; skip this poll and try again on the next one.
            log.warn("Claim lock not acquired within {}; skipping this poll", props.claimLockTimeout());
            return 0;
        }
        if (batch.budgetExhausted()) {
            warnBudgetExhausted();
            return 0;
        }
        List<Claim> claims = batch.claims();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claims.forEach(claim -> executor.submit(() -> process(claim)));
        }
        return claims.size();
    }

    private void warnBudgetExhausted() {
        long now = System.currentTimeMillis();
        long last = lastBudgetWarning.get();
        if (now - last > 60_000 && lastBudgetWarning.compareAndSet(last, now)) {
            log.warn("Hourly investigation budget of {} reached; alerts stay queued until it frees up",
                    props.maxPerHour());
        }
    }

    private void process(Claim claim) {
        var alert = claim.alert().alert();
        try {
            InvestigationResult result = investigator.investigate(claim.alert());
            if (queue.complete(claim, result)) {
                log.info("Reported {} {}/{}: {} risk={} tools={} calls={} tokens={}/{} cached={} {}ms{}", alert.rule(),
                        alert.tenantId(), alert.accountId(), result.report().disposition(), result.report().riskScore(),
                        result.toolCalls().size(), result.llmCalls(), result.inputTokens(), result.outputTokens(),
                        result.cachedInputTokens(), result.latencyMs(),
                        result.repairedIssues().isEmpty() ? "" : " repaired=" + result.repairedIssues());
            } else {
                log.warn("Lost claim on alert {} before completing; result discarded", alert.alertId());
            }
        } catch (RuntimeException e) {
            log.warn("Investigation of alert {} failed: {}", alert.alertId(), e.toString());
            queue.fail(claim, props.maxAttempts());
        }
    }
}
