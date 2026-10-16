package com.finsentinel.platform.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * One analysis of one golden case.
 *
 * @param status      PASSED, FAILED (a check failed or the analysis errored), or SKIPPED (the budget ran out first)
 * @param failures    the failed checks, or the error
 * @param judgeScore  mean of the judge's assessed scores; null when not judged
 * @param costUsd     the analysis plus, when judged, the judge's call; 0 when the analysis failed (cost not known)
 */
record RunRecord(String caseId, String category, boolean critical, String mode, int repeat, String status,
                 String disposition, Integer riskScore, List<String> failures, int requiredTools,
                 int requiredToolsCalled, Boolean regulationsRelevant, Double judgeScore, List<String> unsupportedClaims,
                 BigDecimal costUsd, long latencyMs, Integer inputTokens, Integer cachedInputTokens,
                 Integer cacheWriteTokens, List<String> explanation) {

    static final String PASSED = "PASSED";
    static final String FAILED = "FAILED";
    static final String SKIPPED = "SKIPPED";

    RunRecord {
        failures = failures == null ? List.of() : List.copyOf(failures);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
        explanation = explanation == null ? List.of() : List.copyOf(explanation);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }

    static RunRecord skipped(GoldenCase c, String mode, int repeat) {
        return new RunRecord(c.id(), c.category(), c.critical(), mode, repeat, SKIPPED, null, null,
                List.of("budget used up before this run"), 0, 0, null, null, null, null, 0, null, null, null,
                List.of());
    }

    /** The analysis threw (provider failure, report still invalid after repair): its cost is not known. */
    boolean analysisFailed() {
        return failures.stream().anyMatch(f -> f.startsWith(EvalHarness.ANALYSIS_FAILED));
    }

    boolean ran() {
        return !status.equals(SKIPPED);
    }

    boolean passed() {
        return status.equals(PASSED);
    }
}
