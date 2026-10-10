package com.finsentinel.stream.rules;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Properties;

/** Thresholds for the built-in rules. Serializable because Flink ships it to every task. */
public record RuleConfig(
        Duration velocityWindow,
        int velocityThreshold,
        int largeAmountMinHistory,
        double largeAmountStdDevs,
        double largeAmountMeanMultiple,
        BigDecimal hardLimit,
        Duration baselineMaxAge,
        Duration geoJumpWindow) implements Serializable {

    public RuleConfig {
        if (velocityThreshold < 2) {
            throw new IllegalArgumentException("rules.velocity.threshold must be at least 2");
        }
        if (velocityWindow.isNegative() || velocityWindow.isZero() || geoJumpWindow.isNegative() || geoJumpWindow.isZero()) {
            throw new IllegalArgumentException("rule windows must be positive");
        }
        if (baselineMaxAge.isNegative() || baselineMaxAge.isZero()) {
            throw new IllegalArgumentException("rules.large-amount.baseline-max-age must be positive");
        }
    }

    public static RuleConfig from(Properties p) {
        return new RuleConfig(
                Settings.duration(p, "rules.velocity.window"),
                Settings.integer(p, "rules.velocity.threshold"),
                Settings.integer(p, "rules.large-amount.min-history"),
                Settings.decimal(p, "rules.large-amount.std-devs"),
                Settings.decimal(p, "rules.large-amount.mean-multiple"),
                new BigDecimal(Settings.string(p, "rules.large-amount.hard-limit")),
                Settings.duration(p, "rules.large-amount.baseline-max-age"),
                Settings.duration(p, "rules.geo-jump.window"));
    }

    /** The values in the bundled stream-job.properties. */
    public static RuleConfig defaults() {
        return from(Settings.defaults());
    }
}
