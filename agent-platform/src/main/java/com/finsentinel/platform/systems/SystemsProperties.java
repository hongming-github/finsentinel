package com.finsentinel.platform.systems;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Bound from {@code finsentinel.systems.*}. Invalid values fail at startup.
 *
 * @param bankingUrl     MCP server for CRM and core banking
 * @param complianceUrl  MCP server for watchlist screening
 * @param connectTimeout longest wait for a connection to an MCP server
 * @param requestTimeout longest wait for one tool call; above the servers' own backend timeouts, so a slow backend
 *                       comes back as the server's error rather than as ours
 * @param circuit        when a system's circuit opens, so calls stop waiting on a system that keeps failing
 * @param connectedDefault systems of a bank with no entry in {@code connected}
 * @param connected        per bank (tenant id), the systems it has connected; its agents get only their tools
 */
@ConfigurationProperties("finsentinel.systems")
public record SystemsProperties(URI bankingUrl, URI complianceUrl, Duration connectTimeout, Duration requestTimeout,
                                Circuit circuit, Set<BankSystem> connectedDefault,
                                Map<String, Set<BankSystem>> connected) {

    public SystemsProperties {
        connectedDefault = connectedDefault == null ? Set.of() : Set.copyOf(connectedDefault);
        connected = connected == null ? Map.of() : Map.copyOf(connected);
        if (bankingUrl == null || complianceUrl == null) {
            throw new IllegalArgumentException("finsentinel.systems.banking-url and compliance-url are required");
        }
        if (!positive(connectTimeout) || !positive(requestTimeout)) {
            throw new IllegalArgumentException("finsentinel.systems.connect-timeout and request-timeout must be positive");
        }
        if (circuit == null) {
            throw new IllegalArgumentException("finsentinel.systems.circuit is required");
        }
    }

    /**
     * @param failureThreshold consecutive failed calls to a system (no answer, an error, unreadable) that open its circuit
     * @param openFor          how long calls to it fail at once before one trial call decides
     */
    public record Circuit(int failureThreshold, Duration openFor) {

        public Circuit {
            if (failureThreshold <= 0 || !positive(openFor)) {
                throw new IllegalArgumentException(
                        "finsentinel.systems.circuit.failure-threshold and open-for must be positive");
            }
        }
    }

    /** What this bank has connected: its own entry, else {@code connected-default}. */
    public Set<BankSystem> connectedFor(String tenant) {
        Set<BankSystem> own = connected.get(tenant);
        return own == null ? connectedDefault : Set.copyOf(own);
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
