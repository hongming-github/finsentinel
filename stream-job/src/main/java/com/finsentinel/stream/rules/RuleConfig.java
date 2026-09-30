package com.finsentinel.stream.rules;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;

/** Thresholds for the built-in rules. Serializable because Flink ships it to every task. */
public record RuleConfig(
        Duration velocityWindow,
        int velocityThreshold,
        int largeAmountMinHistory,
        double largeAmountStdDevs,
        double largeAmountMeanMultiple,
        BigDecimal hardLimit,
        Duration geoJumpWindow) implements Serializable {

    public static RuleConfig defaults() {
        return new RuleConfig(
                Duration.ofSeconds(60), 8,
                10, 6.0, 5.0, new BigDecimal("50000"),
                Duration.ofHours(2));
    }
}
