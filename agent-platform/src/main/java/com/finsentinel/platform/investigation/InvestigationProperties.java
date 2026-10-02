package com.finsentinel.platform.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code finsentinel.investigation.*} in application.yaml. Invalid values fail at startup.
 *
 * @param enabled      scheduled polling on/off; tests and cost-conscious local runs turn it off
 * @param batchSize    alerts claimed per poll, which is also the max concurrent LLM investigations
 * @param claimTimeout a claim older than this is considered abandoned and may be taken again
 * @param maxAttempts  after this many failed attempts the alert is marked FAILED
 * @param maxPerHour   spend circuit breaker: investigations started per rolling hour; 0 = unlimited.
 *                     An upstream alert storm must not turn directly into an unbounded LLM bill.
 * @param tools        limits on what one tool call may fetch
 * @param report       acceptance rules for the agent's report
 */
@ConfigurationProperties("finsentinel.investigation")
public record InvestigationProperties(boolean enabled, int batchSize, Duration claimTimeout, int maxAttempts,
                                      int maxPerHour, Tools tools, Report report) {

    public InvestigationProperties {
        require(batchSize > 0, "batch-size must be positive");
        require(maxAttempts > 0, "max-attempts must be positive");
        require(maxPerHour >= 0, "max-per-hour must be 0 (unlimited) or positive");
        require(claimTimeout != null && !claimTimeout.isNegative() && !claimTimeout.isZero(), "claim-timeout must be positive");
        require(tools != null, "tools settings are required");
        require(report != null, "report settings are required");
    }

    /**
     * @param maxDays         furthest look-back a tool accepts; larger requests are clamped
     * @param maxTransactions most transactions one call returns
     */
    public record Tools(int maxDays, int maxTransactions) {

        public Tools {
            require(maxDays > 0 && maxTransactions > 0, "tools limits must be positive");
        }
    }

    /**
     * @param fraudMinScore      a LIKELY_FRAUD report must score at least this
     * @param legitimateMaxScore a LIKELY_LEGITIMATE report must score at most this
     * @param maxRepairs         extra LLM calls allowed to fix a rejected report; bounds cost per case
     */
    public record Report(int fraudMinScore, int legitimateMaxScore, int maxRepairs) {

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
