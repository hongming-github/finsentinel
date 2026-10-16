package com.finsentinel.platform.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What an evaluation run measured, from its run records. Rates are over runs that happened; runs the budget skipped
 * are counted separately and never as passes.
 */
record EvalReport(String startedAt, String modes, int repeats, int runs, int skipped, double passRate,
                  Map<String, CaseStats> byCase, Map<String, Double> passRateByCategory, Double toolSelectionAccuracy,
                  Double regulationRelevance, Double judgeMeanScore, BigDecimal totalCostUsd, BigDecimal meanCostUsd,
                  long p50LatencyMs, long p95LatencyMs, List<RunRecord> records) {

    /** @param dispositions how often each verdict came out, e.g. {LIKELY_FRAUD=2, NEEDS_REVIEW=1} */
    record CaseStats(String category, boolean critical, int runs, int passed, double passRate,
                     Map<String, Integer> dispositions, List<String> failures) {
    }

    static EvalReport of(String startedAt, String modes, int repeats, List<RunRecord> records) {
        List<RunRecord> ran = records.stream().filter(RunRecord::ran).toList();
        Map<String, CaseStats> byCase = new LinkedHashMap<>();
        records.stream().map(RunRecord::caseId).distinct().forEach(id -> {
            List<RunRecord> mine = ran.stream().filter(r -> r.caseId().equals(id)).toList();
            RunRecord any = records.stream().filter(r -> r.caseId().equals(id)).findFirst().orElseThrow();
            Map<String, Integer> dispositions = new TreeMap<>();
            mine.stream().filter(r -> r.disposition() != null).forEach(r -> dispositions.merge(r.disposition(), 1, Integer::sum));
            int passed = (int) mine.stream().filter(RunRecord::passed).count();
            byCase.put(id, new CaseStats(any.category(), any.critical(), mine.size(), passed,
                    rate(passed, mine.size()), dispositions,
                    mine.stream().flatMap(r -> r.failures().stream()).distinct().toList()));
        });
        Map<String, Double> byCategory = new TreeMap<>();
        ran.stream().collect(Collectors.groupingBy(RunRecord::category)).forEach((category, rs) ->
                byCategory.put(category, rate(rs.stream().filter(RunRecord::passed).count(), rs.size())));
        int required = ran.stream().mapToInt(RunRecord::requiredTools).sum();
        int called = ran.stream().mapToInt(RunRecord::requiredToolsCalled).sum();
        List<Boolean> relevance = ran.stream().map(RunRecord::regulationsRelevant).filter(r -> r != null).toList();
        List<Double> judged = ran.stream().map(RunRecord::judgeScore).filter(s -> s != null && s > 0).toList();
        BigDecimal total = records.stream().map(RunRecord::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> latencies = ran.stream().map(RunRecord::latencyMs).sorted(Comparator.naturalOrder()).toList();
        return new EvalReport(startedAt, modes, repeats, ran.size(), records.size() - ran.size(),
                rate(ran.stream().filter(RunRecord::passed).count(), ran.size()), byCase, byCategory,
                required == 0 ? null : rate(called, required),
                relevance.isEmpty() ? null : rate(relevance.stream().filter(b -> b).count(), relevance.size()),
                judged.isEmpty() ? null : judged.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                total, ran.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(ran.size()), 6, RoundingMode.HALF_UP),
                percentile(latencies, 0.50), percentile(latencies, 0.95), List.copyOf(records));
    }

    String markdown() {
        StringBuilder md = new StringBuilder("# Evaluation ").append(startedAt).append("\n\n")
                .append("Modes ").append(modes).append(", ").append(repeats).append(" repeats: ").append(runs)
                .append(" runs").append(skipped > 0 ? " (" + skipped + " skipped: budget)" : "").append("\n\n")
                .append("| Metric | Value |\n|---|---|\n")
                .append("| Pass rate | ").append(pct(passRate)).append(" |\n")
                .append("| Tool selection accuracy | ").append(pct(toolSelectionAccuracy)).append(" |\n")
                .append("| Regulation relevance | ").append(pct(regulationRelevance)).append(" |\n")
                .append("| Judge mean score (1-5) | ").append(judgeMeanScore == null ? "not judged"
                        : "%.2f".formatted(judgeMeanScore)).append(" |\n")
                .append("| Analyses that failed (cost not measured) | ")
                .append(records.stream().filter(RunRecord::analysisFailed).count()).append(" |\n")
                .append("| Cost total / per run | $").append(totalCostUsd.setScale(4, RoundingMode.HALF_UP)).append(" / $")
                .append(meanCostUsd.setScale(4, RoundingMode.HALF_UP)).append(" |\n")
                .append("| Latency p50 / p95 | ").append(p50LatencyMs).append(" ms / ").append(p95LatencyMs)
                .append(" ms |\n")
                .append("| Prompt tokens: total / read from cache / written to cache (where reported) | ")
                .append(promptCache())
                .append(" |\n\n")
                .append("| Case | Category | Critical | Passed | Verdicts | Failed checks |\n|---|---|---|---|---|---|\n");
        byCase.forEach((id, s) -> md.append("| ").append(id).append(" | ").append(s.category()).append(" | ")
                .append(s.critical() ? "yes" : "").append(" | ").append(s.passed()).append("/").append(s.runs())
                .append(" | ").append(s.dispositions()).append(" | ")
                .append(String.join("; ", s.failures()).replace("|", "/")).append(" |\n"));
        md.append("\n| Category | Pass rate |\n|---|---|\n");
        passRateByCategory.forEach((c, r) -> md.append("| ").append(c).append(" | ").append(pct(r)).append(" |\n"));
        return md.toString();
    }

    /**
     * Over the runs that reported tokens. Cached and written shares of the prompt tokens: a low cached share after a
     * prompt change means the change broke the reusable prefix (ADR 0023). The written share is a floor: Spring AI
     * 2.0.1 loses the write count of tool-calling exchanges (see {@code LlmUsage.cacheWriteTokens}).
     */
    String promptCache() {
        List<RunRecord> counted = records.stream().filter(r -> r.inputTokens() != null).toList();
        long input = counted.stream().mapToLong(RunRecord::inputTokens).sum();
        if (input == 0) {
            return "not reported";
        }
        long cached = counted.stream().mapToLong(r -> r.cachedInputTokens() == null ? 0 : r.cachedInputTokens()).sum();
        long written = counted.stream().mapToLong(r -> r.cacheWriteTokens() == null ? 0 : r.cacheWriteTokens()).sum();
        return "%,d / %s / %s".formatted(input, pct((double) cached / input), pct((double) written / input));
    }

    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    private static String pct(Double rate) {
        return rate == null ? "n/a" : "%.0f%%".formatted(rate * 100);
    }

    /** Cases whose pass rate is below {@code min}, for the gate. */
    List<String> casesBelow(double min, boolean criticalOnly) {
        List<String> out = new ArrayList<>();
        byCase.forEach((id, s) -> {
            if ((!criticalOnly || s.critical()) && s.runs() > 0 && s.passRate() < min) {
                out.add(id + " " + s.passed() + "/" + s.runs());
            }
        });
        return out;
    }
}
