package com.finsentinel.platform.cases;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code finsentinel.outbox.*}.
 *
 * @param enabled     scheduled publishing on/off; tests drive {@link OutboxRelay#publishOnce()} directly
 * @param batchSize   messages published per poll
 * @param sendTimeout longest wait for the broker to acknowledge one message
 */
@ConfigurationProperties("finsentinel.outbox")
public record OutboxProperties(boolean enabled, int batchSize, Duration sendTimeout) {

    public OutboxProperties {
        if (batchSize <= 0 || sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("finsentinel.outbox: invalid settings");
        }
    }
}
