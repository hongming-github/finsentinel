package com.finsentinel.platform.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code finsentinel.mcp-server.*}. Invalid values fail at startup.
 *
 * @param maxRegulationSearchesPerHour every search embeds the question and may rerank with a model: a cost path open
 *                                     to external callers, so it has its own budget, shared by all instances
 * @param maxCases                     most cases one listCases call returns
 * @param maxPassages                  most passages one readRegulationPassages call returns
 */
@ConfigurationProperties("finsentinel.mcp-server")
public record McpServerProperties(int maxRegulationSearchesPerHour, int maxCases, int maxPassages) {

    public McpServerProperties {
        if (maxRegulationSearchesPerHour <= 0 || maxCases <= 0 || maxPassages <= 0) {
            throw new IllegalArgumentException("finsentinel.mcp-server limits must be positive");
        }
    }
}
