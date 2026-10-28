package com.finsentinel.platform.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code finsentinel.investigation.*} in application.yaml. Invalid values fail at startup.
 *
 * @param enabled      scheduled polling on/off; tests and cost-conscious local runs turn it off
 * @param concurrency  analyses running at once on this instance (its worker pool); bounds model calls in flight
 * @param claimTimeout the longest one analysis may hold its claim, renewed or not; then it may be taken again
 * @param claimLease   a claim not renewed for this long has lapsed (its owner died) and may be taken again
 * @param claimHeartbeat how often a running analysis renews its claim; well under the lease
 * @param maxAttempts  after this many failed attempts the alert is marked FAILED
 * @param retry        pauses between a case's failed attempts
 * @param maxPerHour   spend circuit breaker: investigations started per rolling hour; 0 = unlimited.
 *                     An upstream alert storm must not turn directly into an unbounded LLM bill.
 * @param claimLockTimeout longest wait for the cross-instance claim lock before skipping a poll
 * @param evidenceWait longest a case waits for the transactions behind its alerts to be stored before it is analysed
 *                     without them (the agents are then told what is missing); 0 = no wait (ADR 0025)
 * @param tools        limits on what one tool call may fetch
 * @param report       acceptance rules for the agent's report
 */
@ConfigurationProperties("finsentinel.investigation")
public record InvestigationProperties(boolean enabled, int concurrency, Duration claimTimeout, Duration claimLease,
                                      Duration claimHeartbeat, int maxAttempts,
                                      Retry retry, int maxPerHour, Duration claimLockTimeout,
                                      Duration evidenceWait, Tools tools, Report report) {

    public InvestigationProperties {
        require(concurrency > 0, "concurrency must be positive");
        require(maxAttempts > 0, "max-attempts must be positive");
        require(maxPerHour >= 0, "max-per-hour must be 0 (unlimited) or positive");
        require(claimTimeout != null && !claimTimeout.isNegative() && !claimTimeout.isZero(), "claim-timeout must be positive");
        require(claimLease != null && claimLease.isPositive() && claimLease.compareTo(claimTimeout) <= 0,
                "claim-lease must be positive and at most claim-timeout");
        require(claimHeartbeat != null && claimHeartbeat.isPositive()
                        && claimHeartbeat.multipliedBy(2).compareTo(claimLease) <= 0,
                "claim-heartbeat must be positive and at most half of claim-lease");
        require(claimLockTimeout != null && !claimLockTimeout.isNegative() && !claimLockTimeout.isZero(),
                "claim-lock-timeout must be positive");
        require(evidenceWait != null && !evidenceWait.isNegative(), "evidence-wait must be 0 or positive");
        require(retry != null, "retry settings are required");
        require(tools != null, "tools settings are required");
        require(report != null, "report settings are required");
    }

    /**
     * A failed attempt waits {@code pause}, then {@code multiplier} times longer after each further failure, at most
     * {@code maxPause}, so the attempts span minutes and outlast a provider outage of a few minutes (drill 02, ADR 0026).
     *
     * @param pause      wait after the first failed attempt
     * @param multiplier growth of the wait after each further failure
     * @param maxPause   longest wait
     */
    public record Retry(Duration pause, double multiplier, Duration maxPause) {

        public Retry {
            require(pause != null && pause.isPositive(), "retry.pause must be positive");
            require(multiplier >= 1, "retry.multiplier must be at least 1");
            require(maxPause != null && maxPause.compareTo(pause) >= 0, "retry.max-pause must be at least retry.pause");
        }

        /** The wait before the next attempt, after {@code failedAttempts} attempts (1 or more) have failed. */
        public Duration pauseAfter(int failedAttempts) {
            double millis = pause.toMillis() * Math.pow(multiplier, Math.max(0, failedAttempts - 1));
            return millis >= maxPause.toMillis() ? maxPause : Duration.ofMillis(Math.round(millis));
        }
    }

    /**
     * @param maxDays         furthest look-back a tool accepts; larger requests are clamped
     * @param maxTransactions most transactions one call returns
     * @param travelMaxSpeedKmh speed behind the travel lower bound shown with the alert's transactions (common
     *                          {@code TravelFeasibility}); the same value as {@code rules.geo-jump.max-speed-kmh} in the
     *                          stream job, so the agent and the rule agree on what is impossible
     */
    public record Tools(int maxDays, int maxTransactions, double travelMaxSpeedKmh) {

        public Tools {
            require(maxDays > 0 && maxTransactions > 0, "tools limits must be positive");
            require(travelMaxSpeedKmh > 0, "tools.travel-max-speed-kmh must be positive");
        }
    }

    /**
     * @param fraudMinScore      a LIKELY_FRAUD report must score at least this
     * @param legitimateMaxScore a LIKELY_LEGITIMATE report must score at most this
     * @param maxRepairs         extra LLM calls allowed to fix a rejected report; bounds cost per case
     * @param requireRegulationsForTransfers a case involving a transfer that is not judged LIKELY_LEGITIMATE must
     *                                       carry regulatory notes; the model skipped the search too often to leave it
     *                                       to the prompt
     */
    public record Report(int fraudMinScore, int legitimateMaxScore, int maxRepairs, boolean requireRegulationsForTransfers) {

        public Report {
            require(legitimateMaxScore >= 0 && legitimateMaxScore < fraudMinScore && fraudMinScore <= 100,
                    "report scores must satisfy 0 <= legitimate-max-score < fraud-min-score <= 100");
            require(maxRepairs >= 0 && maxRepairs <= 3, "report.max-repairs must be between 0 and 3");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("finsentinel.investigation." + message);
        }
    }
}
