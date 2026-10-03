package com.finsentinel.mcp.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.compliance.*}. Invalid values fail at startup.
 *
 * @param screeningUrl   base URL of the screening vendor
 * @param connectTimeout longest wait for a connection
 * @param readTimeout    longest wait for an answer; the agent's tool call waits this long at most
 */
@ConfigurationProperties("finsentinel.compliance")
record ComplianceProperties(URI screeningUrl, Duration connectTimeout, Duration readTimeout) {

    ComplianceProperties {
        if (screeningUrl == null || !positive(connectTimeout) || !positive(readTimeout)) {
            throw new IllegalArgumentException(
                    "finsentinel.compliance.screening-url is required; connect-timeout and read-timeout must be positive");
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
