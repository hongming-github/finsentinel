package com.finsentinel.platform.eval;

import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ToolCall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How lenient the golden cases are (ADR 0017): an agent that never judges, answering NEEDS_REVIEW with one fixed risk
 * score whatever the case, graded against every case. It is given everything that does not take judgement: it calls
 * every required tool, cites a relevant passage wherever regulations are required, and the platform flags planted
 * instructions as it would. A case it passes checks process, not judgement. The evaluation's gate must still turn it
 * down at every fixed score, or a passing evaluation says nothing about the agents.
 */
class LenientAgentBaselineTest {

    private static final List<Integer> SCORES = List.of(40, 50, 59, 60, 70, 80);
    private static final String PLAIN = "Manual review recommended.";
    /** Boilerplate a template could add to every report, whatever happened. */
    private static final String BOILERPLATE = "Some checks could not be completed; manual review recommended.";

    private final JsonMapper json = JsonMapper.builder().build();
    private final GoldenDataset dataset = GoldenDataset.load(json);
    private final CaseGrader grader = new CaseGrader(dataset, json);
    private final EvalGate.Thresholds thresholds = EvalGate.Thresholds.load(json);

    @Test
    void anAgentThatNeverJudgesFailsTheGateAtEveryFixedScore() {
        for (String summary : List.of(PLAIN, BOILERPLATE)) {
            Map<Integer, List<GoldenCase>> passedAt = new LinkedHashMap<>();
            for (int score : SCORES) {
                passedAt.put(score, dataset.cases().stream()
                        .filter(c -> grader.grade(c, lazy(c, score, summary)).passed()).toList());
            }
            print(summary, passedAt);
            for (int score : SCORES) {
                assertThat(gateViolations(passedAt.get(score)))
                        .as("a constant NEEDS_REVIEW %d with summary \"%s\" passes the gate", score, summary)
                        .isNotEmpty();
            }
        }
    }

    /** NEEDS_REVIEW at a fixed score, every required tool called, a relevant passage cited where one is required. */
    private InvestigationResult lazy(GoldenCase c, int score, String summary) {
        GoldenCase.Expect e = c.expect();
        List<CaseReport.RegulatoryNote> notes = new ArrayList<>();
        if (e.regulations() != null && e.regulations().required()) {
            String chunk = dataset.chunks(e.regulations().relevantChunkSet()).stream().sorted().findFirst()
                    .orElse("notice-626-2025:14.2");
            notes.add(new CaseReport.RegulatoryNote("The bank's obligations apply.", List.of(chunk)));
        }
        CaseReport report = new CaseReport(Disposition.NEEDS_REVIEW, score, summary,
                List.of(new CaseReport.Finding("The alert's transactions need a closer look.", List.of())),
                List.of("Review the case manually."), notes);
        List<ToolCall> tools = e.requiredTools().stream().map(t -> new ToolCall(t, "", 1, 1)).toList();
        // Flagging is the platform's (UntrustedInstructions in the tool callbacks), not the agent's judgement.
        List<String> flagged = e.untrustedInstructionsFlagged() ? List.of("planted text") : List.of();
        return new InvestigationResult(report, tools, List.of(), "lazy", 1, 1, null, 1, 1, List.of(), null, null,
                flagged);
    }

    /** EvalGate's rules for one run per case: overall pass rate, each critical case, every other case. */
    private List<String> gateViolations(List<GoldenCase> passed) {
        List<String> out = new ArrayList<>();
        double overall = (double) passed.size() / dataset.cases().size();
        if (thresholds.overallPassRate() != null && overall < thresholds.overallPassRate()) {
            out.add("overall " + overall);
        }
        for (GoldenCase c : dataset.cases()) {
            double rate = passed.contains(c) ? 1 : 0;
            Double min = c.critical() ? thresholds.criticalCasePassRate() : thresholds.casePassRate();
            if (min != null && rate < min) {
                out.add(c.id());
            }
        }
        return out;
    }

    private void print(String summary, Map<Integer, List<GoldenCase>> passedAt) {
        System.out.printf("%nLenient agent, NEEDS_REVIEW at a fixed score, summary \"%s\"%n", summary);
        System.out.printf("%-30s %-8s %s%n", "case", "critical", SCORES.stream().map(s -> "%4d".formatted(s))
                .collect(Collectors.joining()));
        for (GoldenCase c : dataset.cases()) {
            System.out.printf("%-30s %-8s %s   %s%n", c.id(), c.critical() ? "yes" : "",
                    SCORES.stream().map(s -> passedAt.get(s).contains(c) ? "   ✓" : "   ·").collect(Collectors.joining()),
                    String.join("; ", grader.grade(c, lazy(c, 60, summary)).failed()));
        }
        System.out.printf("%-30s %-8s %s%n", "passed", "", SCORES.stream()
                .map(s -> "%4d".formatted(passedAt.get(s).size())).collect(Collectors.joining()));
    }
}
