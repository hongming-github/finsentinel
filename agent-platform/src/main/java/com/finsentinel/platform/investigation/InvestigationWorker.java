package com.finsentinel.platform.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the queue and runs investigations. Each poll claims at most {@code batchSize} alerts and waits
 * for them to finish, so {@code batchSize} is a hard cap on concurrent LLM calls from this instance.
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
        int capacity = props.batchSize();
        if (props.maxPerHour() > 0) {
            int remaining = props.maxPerHour() - queue.startedInLastHour();
            if (remaining <= 0) {
                warnBudgetExhausted();
                return 0;
            }
            capacity = Math.min(capacity, remaining);
        }
        List<Claim> claims = queue.claim(capacity, props.claimTimeout(), props.maxAttempts());
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
