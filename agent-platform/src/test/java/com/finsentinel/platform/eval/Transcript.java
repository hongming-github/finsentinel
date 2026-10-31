package com.finsentinel.platform.eval;

import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.Review;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything one analysis produced, kept with its run record so a run can be read afterwards (ADR 0017): which
 * model answered, the stored report, every tool call with the arguments the model chose and the result it was handed
 * (pseudonymised, wrapped in the platform's warning where one was added), the passages each regulation search
 * returned, what the validator sent back for repair, and the reviewer's verdict. Without it a failure said which check
 * failed but not which search had returned which passage.
 *
 * @param lastModel   the model of the run's latest call: the reviewer's when the report was reviewed, so
 *                    {@code costByModel} is what says which models took part
 * @param costByModel cost split by priced model, investigator and reviewer together
 * @param calls       in the order the model made them; empty in MULTI mode, where the writer calls no tools
 */
record Transcript(String lastModel, Map<String, BigDecimal> costByModel, CaseReport report, List<Call> calls,
                  List<String> repairedIssues, ReviewView review) {

    private static final Pattern CHUNK_ID = Pattern.compile("\"chunkId\"\\s*:\\s*\"([^\"]+)\"");

    /** @param returnedChunkIds for a regulation search, the passages it returned, in rank order */
    record Call(String tool, String input, String output, List<String> returnedChunkIds) {
    }

    /** @param skippedBecause set when risk-based review skipped this report (ADR 0010) */
    record ReviewView(boolean approved, List<String> issues, String skippedBecause) {
    }

    static Transcript of(CaseAnalysisGraph.Outcome outcome) {
        InvestigationResult result = outcome.investigation();
        List<Call> calls = result.evidence().stream()
                .map(e -> new Call(e.tool(), e.input(), e.output(),
                        e.tool().equals("searchRegulations") ? chunkIds(e.output()) : List.of()))
                .toList();
        Review review = outcome.review();
        return new Transcript(result.model(), result.costByModel(), result.report(), calls, result.repairedIssues(),
                review == null ? null : new ReviewView(review.approved(), review.issues(), review.skippedBecause()));
    }

    private static List<String> chunkIds(String output) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = CHUNK_ID.matcher(output == null ? "" : output);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return List.copyOf(ids);
    }
}
