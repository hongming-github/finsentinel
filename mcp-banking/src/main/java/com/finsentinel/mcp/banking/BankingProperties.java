package com.finsentinel.mcp.banking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.banking.*}. Invalid values fail at startup.
 *
 * @param crmUrl         base URL of the CRM
 * @param coreUrl        base URL of core banking
 * @param connectTimeout longest wait for a connection to either system
 * @param readTimeout    longest wait for an answer; the agent's tool call waits this long at most
 */
@ConfigurationProperties("finsentinel.banking")
record BankingProperties(URI crmUrl, URI coreUrl, Duration connectTimeout, Duration readTimeout) {

    BankingProperties {
        require(crmUrl != null && coreUrl != null, "crm-url and core-url are required");
        require(positive(connectTimeout) && positive(readTimeout), "connect-timeout and read-timeout must be positive");
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("finsentinel.banking." + message);
        }
    }
}
