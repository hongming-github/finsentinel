package com.finsentinel.platform.systems;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.systems.*}. Invalid values fail at startup.
 *
 * @param bankingUrl     MCP server for CRM and core banking
 * @param complianceUrl  MCP server for watchlist screening
 * @param connectTimeout longest wait for a connection to an MCP server
 * @param requestTimeout longest wait for one tool call; above the servers' own backend timeouts, so a slow backend
 *                       comes back as the server's error rather than as ours
 */
@ConfigurationProperties("finsentinel.systems")
public record SystemsProperties(URI bankingUrl, URI complianceUrl, Duration connectTimeout, Duration requestTimeout) {

    public SystemsProperties {
        if (bankingUrl == null || complianceUrl == null) {
            throw new IllegalArgumentException("finsentinel.systems.banking-url and compliance-url are required");
        }
        if (!positive(connectTimeout) || !positive(requestTimeout)) {
            throw new IllegalArgumentException("finsentinel.systems.connect-timeout and request-timeout must be positive");
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
