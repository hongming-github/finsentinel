package com.finsentinel.platform.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalGateTest {

    private static final EvalGate.Thresholds THRESHOLDS = new EvalGate.Thresholds(0.85, 0.67, 0.34, 0.90, 0.80, null, 0.03);

    @Test
    void aGoodRunPasses() {
        List<RunRecord> records = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            records.add(run("a", true, true, "0.010"));
            records.add(run("b", false, true, "0.010"));
        }

        assertThat(EvalGate.violations(report(records), THRESHOLDS)).isEmpty();
    }

    @Test
    void aCriticalCaseFailingTwiceInThreeFailsTheGateEvenWhenTheOverallRateHolds() {
        List<RunRecord> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(run("b" + i, false, true, "0.010"));
        }
        records.add(run("a", true, true, "0.010"));
        records.add(run("a", true, false, "0.010"));
        records.add(run("a", true, false, "0.010"));

        assertThat(EvalGate.violations(report(records), THRESHOLDS))
                .containsExactly("critical case a 1/3 below 67%");
    }

    @Test
    void budgetSkipsCostAndToolSelectionAreGated() {
        List<RunRecord> records = new ArrayList<>(List.of(
                new RunRecord("a", "x", false, "SINGLE", 1, RunRecord.PASSED, "LIKELY_FRAUD", 90, List.of(), 4, 2,
                        null, null, null, new BigDecimal("0.080"), 1000),
                RunRecord.skipped(new GoldenCase("a", "x", false, "d", "e", null, null, null), "SINGLE", 2)));

        assertThat(EvalGate.violations(report(records), THRESHOLDS)).containsExactlyInAnyOrder(
                "1 runs skipped because the budget ran out; raise eval.maxCostUsd or run fewer cases",
                "tool selection accuracy 50% below 90%",
                "mean cost per run $0.0800 above $0.0300");
    }

    private static RunRecord run(String id, boolean critical, boolean passed, String cost) {
        return new RunRecord(id, "x", critical, "SINGLE", 1, passed ? RunRecord.PASSED : RunRecord.FAILED,
                "LIKELY_FRAUD", 90, passed ? List.of() : List.of("disposition: wrong"), 1, 1, true, null, null,
                new BigDecimal(cost), 1000);
    }

    private static EvalReport report(List<RunRecord> records) {
        return EvalReport.of("now", "[SINGLE]", 3, records);
    }
}
