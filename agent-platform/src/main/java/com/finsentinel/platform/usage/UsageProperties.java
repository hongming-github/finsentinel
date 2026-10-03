package com.finsentinel.platform.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code finsentinel.usage.*}.
 *
 * @param maxDays furthest look-back a usage report accepts; larger requests are capped
 */
@ConfigurationProperties("finsentinel.usage")
public record UsageProperties(int maxDays) {

    public UsageProperties {
        if (maxDays <= 0) {
            throw new IllegalArgumentException("finsentinel.usage.max-days must be positive");
        }
    }
}
