package com.finsentinel.platform.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code finsentinel.alerts.*}.
 *
 * @param maxPageSize   upper bound for {@code limit} on alert listings
 * @param retryInterval pause between redeliveries of a failing Kafka record
 * @param retryAttempts redeliveries before the record goes to the dead-letter topic
 */
@ConfigurationProperties("finsentinel.alerts")
public record AlertProperties(int maxPageSize, Duration retryInterval, int retryAttempts) {

    public AlertProperties {
        if (maxPageSize <= 0 || retryAttempts < 0 || retryInterval == null || retryInterval.isNegative()) {
            throw new IllegalArgumentException("finsentinel.alerts: invalid settings");
        }
    }
}
