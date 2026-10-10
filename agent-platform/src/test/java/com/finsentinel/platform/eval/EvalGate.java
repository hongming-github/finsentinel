package com.finsentinel.platform.eval;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The CI gate (ADR 0017): an evaluation fails when a metric drops below its threshold ({@code eval/thresholds.json}).
 * A null threshold is not gated. Runs skipped for budget fail the gate too: an evaluation that did not run every case
 * proves nothing about the cases it skipped.
 */
final class EvalGate {

    /**
     * @param overallPassRate     share of all runs that passed every check
     * @param criticalCasePassRate each critical case on its own
     * @param casePassRate        every other case on its own
     * @param maxMeanCostUsd      mean cost per run: a prompt or routing change that doubles cost should be a decision
     */
    record Thresholds(Double overallPassRate, Double criticalCasePassRate, Double casePassRate,
                      Double toolSelectionAccuracy, Double regulationRelevance, Double judgeMeanScore,
                      Double maxMeanCostUsd) {

        static Thresholds load(JsonMapper json) {
            try (InputStream in = new ClassPathResource("eval/thresholds.json").getInputStream()) {
                return json.readValue(in, Thresholds.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private EvalGate() {
    }

    /** Every threshold the report misses; empty means the gate passes. */
    static List<String> violations(EvalReport report, Thresholds t) {
        List<String> out = new ArrayList<>();
        if (report.runs() == 0) {
            out.add("no runs");
            return out;
        }
        if (report.skipped() > 0) {
            out.add(report.skipped() + " runs skipped because the budget ran out; raise eval.maxCostUsd or run fewer cases");
        }
        below(out, "pass rate", report.passRate(), t.overallPassRate());
        below(out, "tool selection accuracy", report.toolSelectionAccuracy(), t.toolSelectionAccuracy());
        below(out, "regulation relevance", report.regulationRelevance(), t.regulationRelevance());
        if (t.judgeMeanScore() != null && report.judgeMeanScore() != null && report.judgeMeanScore() < t.judgeMeanScore()) {
            out.add("judge mean score %.2f below %.2f".formatted(report.judgeMeanScore(), t.judgeMeanScore()));
        }
        if (t.criticalCasePassRate() != null) {
            report.casesBelow(t.criticalCasePassRate(), true)
                    .forEach(c -> out.add("critical case " + c + " below " + pct(t.criticalCasePassRate())));
        }
        if (t.casePassRate() != null) {
            report.casesBelow(t.casePassRate(), false).stream()
                    .filter(c -> !out.stream().anyMatch(o -> o.startsWith("critical case " + c)))
                    .forEach(c -> out.add("case " + c + " below " + pct(t.casePassRate())));
        }
        if (t.maxMeanCostUsd() != null && report.meanCostUsd().doubleValue() > t.maxMeanCostUsd()) {
            out.add("mean cost per run $%.4f above $%.4f".formatted(report.meanCostUsd(), t.maxMeanCostUsd()));
        }
        return out;
    }

    private static void below(List<String> out, String metric, Double value, Double threshold) {
        if (threshold != null && value != null && value < threshold) {
            out.add("%s %s below %s".formatted(metric, pct(value), pct(threshold)));
        }
    }

    private static String pct(double rate) {
        return "%.0f%%".formatted(rate * 100);
    }
}
