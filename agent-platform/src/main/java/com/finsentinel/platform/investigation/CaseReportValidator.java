package com.finsentinel.platform.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks what the JSON Schema cannot: the schema guarantees the report's shape, not that it is internally
 * consistent or that its evidence exists. Each problem is phrased so it can be sent back to the model.
 */
final class CaseReportValidator {

    private CaseReportValidator() {
    }

    static List<String> problems(CaseReport report, Set<String> seenTxnIds, InvestigationProperties.Report rules) {
        List<String> problems = new ArrayList<>();
        if (report.disposition() == null) {
            problems.add("disposition is missing");
        }
        int score = report.riskScore();
        if (score < 0 || score > 100) {
            problems.add("riskScore %d is outside 0-100".formatted(score));
        }
        if (report.disposition() == Disposition.LIKELY_FRAUD && score < rules.fraudMinScore()) {
            problems.add("disposition LIKELY_FRAUD needs riskScore >= %d, got %d".formatted(rules.fraudMinScore(), score));
        }
        if (report.disposition() == Disposition.LIKELY_LEGITIMATE && score > rules.legitimateMaxScore()) {
            problems.add("disposition LIKELY_LEGITIMATE needs riskScore <= %d, got %d"
                    .formatted(rules.legitimateMaxScore(), score));
        }
        if (report.summary() == null || report.summary().isBlank()) {
            problems.add("summary is empty");
        }
        if (report.findings() == null || report.findings().isEmpty()) {
            problems.add("at least one finding is required");
        }
        if (report.recommendedActions() == null || report.recommendedActions().isEmpty()) {
            problems.add("at least one recommended action is required");
        }
        List<String> ungrounded = ungrounded(report, seenTxnIds);
        if (!ungrounded.isEmpty()) {
            problems.add("evidenceTxnIds %s were never returned by a tool; cite only transaction ids from tool results"
                    .formatted(ungrounded));
        }
        return problems;
    }

    /** Ids the report cites that never appeared in a tool result. */
    static List<String> ungrounded(CaseReport report, Set<String> seen) {
        if (report.findings() == null) {
            return List.of();
        }
        return report.findings().stream()
                .filter(f -> f.evidenceTxnIds() != null)
                .flatMap(f -> f.evidenceTxnIds().stream())
                .filter(id -> !seen.contains(id))
                .distinct()
                .toList();
    }
}
