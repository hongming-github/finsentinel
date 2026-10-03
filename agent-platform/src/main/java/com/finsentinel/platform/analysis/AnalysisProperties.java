package com.finsentinel.platform.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code finsentinel.analysis.*}. Invalid values fail at startup.
 *
 * @param mode         single investigator or multi-agent analysis
 * @param maxRevisions times the reviewer may send a report back in one run; bounds model calls per analysis
 */
@ConfigurationProperties("finsentinel.analysis")
public record AnalysisProperties(AnalysisMode mode, int maxRevisions) {

    public AnalysisProperties {
        if (mode == null) {
            throw new IllegalArgumentException("finsentinel.analysis.mode is required (SINGLE or MULTI)");
        }
        if (maxRevisions < 0 || maxRevisions > 2) {
            throw new IllegalArgumentException("finsentinel.analysis.max-revisions must be between 0 and 2");
        }
    }
}
