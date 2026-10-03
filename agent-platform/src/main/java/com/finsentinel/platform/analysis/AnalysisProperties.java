package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.Disposition;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Bound from {@code finsentinel.analysis.*}. Invalid values fail at startup.
 *
 * @param mode         single investigator or multi-agent analysis
 * @param maxRevisions       times the reviewer may send a report back in one run; bounds model calls per analysis
 * @param reviewDispositions reports with these dispositions are reviewed; others go to the analyst unreviewed
 *                           (risk-based review: a report that clears a case is where a missed fraud hides)
 */
@ConfigurationProperties("finsentinel.analysis")
public record AnalysisProperties(AnalysisMode mode, int maxRevisions, Set<Disposition> reviewDispositions) {

    public AnalysisProperties {
        if (mode == null) {
            throw new IllegalArgumentException("finsentinel.analysis.mode is required (SINGLE or MULTI)");
        }
        if (maxRevisions < 0 || maxRevisions > 2) {
            throw new IllegalArgumentException("finsentinel.analysis.max-revisions must be between 0 and 2");
        }
        reviewDispositions = reviewDispositions == null ? Set.of() : Set.copyOf(reviewDispositions);
    }
}
