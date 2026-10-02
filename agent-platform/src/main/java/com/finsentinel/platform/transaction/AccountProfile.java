package com.finsentinel.platform.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Aggregates over an account's history: what "normal" looks like for this customer. */
public record AccountProfile(AmountStats amounts, Map<String, Long> channelCounts,
                             Map<String, Long> cardPresentCountryCounts) {

    public record AmountStats(long transactionCount, BigDecimal mean, BigDecimal median, BigDecimal p95,
                              BigDecimal max, Instant firstSeen, Instant lastSeen) {
    }
}
