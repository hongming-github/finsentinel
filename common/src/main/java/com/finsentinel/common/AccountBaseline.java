package com.finsentinel.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * An account's spending profile computed by the daily batch job over its curated history (ADR 0019).
 *
 * <p>The centre and spread are robust ({@code medianAmount}, {@code robustStdDev} = 1.4826 x median absolute
 * deviation): the batch job cannot see which past amounts were flagged as fraud, so one large fraudulent payment must
 * not move the baseline. Mean, p95 and max are descriptive.
 *
 * @param asOf       midnight UTC of the as-of date: the baseline covers complete UTC days before it. An {@code Instant},
 *                   not a {@code LocalDate}, because Flink serialises {@code LocalDate} record fields with Kryo
 * @param windowDays how many days before {@code asOf} it covers
 * @param sampleSize transactions in the window
 */
public record AccountBaseline(
        String tenantId,
        String accountId,
        Instant asOf,
        int windowDays,
        long sampleSize,
        double medianAmount,
        double robustStdDev,
        double meanAmount,
        double p95Amount,
        double maxAmount,
        Instant computedAt) {

    public AccountBaseline {
        if (!asOf.truncatedTo(ChronoUnit.DAYS).equals(asOf)) {
            throw new IllegalArgumentException("asOf must be midnight UTC: " + asOf);
        }
    }

    /** Same key as {@link Transaction#accountKey()}, so baselines and transactions meet on one partition. */
    public String accountKey() {
        return tenantId + "|" + accountId;
    }
}
