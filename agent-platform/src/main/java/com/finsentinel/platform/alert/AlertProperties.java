package com.finsentinel.platform.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code finsentinel.alerts.*}.
 *
 * @param maxPageSize      upper bound for {@code limit} on alert listings
 * @param retryInterval    first pause before redelivering a failing Kafka record; failures are retried without a limit
 * @param retryMultiplier  growth of the pause after each failed attempt
 * @param retryMaxInterval longest pause, so a record is tried again soon after the outage ends
 */
@ConfigurationProperties("finsentinel.alerts")
public record AlertProperties(int maxPageSize, Duration retryInterval, double retryMultiplier,
                              Duration retryMaxInterval) {

    public AlertProperties {
        if (maxPageSize <= 0 || retryInterval == null || !retryInterval.isPositive() || retryMultiplier < 1
                || retryMaxInterval == null || retryMaxInterval.compareTo(retryInterval) < 0) {
            throw new IllegalArgumentException("finsentinel.alerts: invalid settings");
        }
    }
}
