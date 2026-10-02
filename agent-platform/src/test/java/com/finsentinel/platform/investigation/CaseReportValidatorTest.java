package com.finsentinel.platform.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CaseReportValidatorTest {

    private static final InvestigationProperties.Report RULES = new InvestigationProperties.Report(60, 40, 1);
    private static final Set<String> SEEN = Set.of("t1", "t2");

    @Test
    void consistentGroundedReportPasses() {
        assertThat(problems(report(Disposition.LIKELY_FRAUD, 90, List.of("t1")))).isEmpty();
        assertThat(problems(report(Disposition.NEEDS_REVIEW, 50, List.of()))).isEmpty();
        assertThat(problems(report(Disposition.LIKELY_LEGITIMATE, 10, List.of("t2")))).isEmpty();
    }

    @Test
    void dispositionMustMatchScore() {
        assertThat(problems(report(Disposition.LIKELY_FRAUD, 10, List.of())))
                .containsExactly("disposition LIKELY_FRAUD needs riskScore >= 60, got 10");
        assertThat(problems(report(Disposition.LIKELY_LEGITIMATE, 85, List.of())))
                .containsExactly("disposition LIKELY_LEGITIMATE needs riskScore <= 40, got 85");
    }

    @Test
    void scoreMustBeInRange() {
        assertThat(problems(report(Disposition.NEEDS_REVIEW, 140, List.of()))).containsExactly("riskScore 140 is outside 0-100");
    }

    @Test
    void emptySectionsAreRejected() {
        CaseReport empty = new CaseReport(Disposition.NEEDS_REVIEW, 50, " ", List.of(), List.of());

        assertThat(problems(empty)).containsExactlyInAnyOrder("summary is empty", "at least one finding is required",
                "at least one recommended action is required");
    }

    @Test
    void inventedEvidenceIsRejectedWithTheOffendingIds() {
        assertThat(problems(report(Disposition.LIKELY_FRAUD, 90, List.of("t1", "m-1045"))))
                .singleElement().asString().contains("[m-1045]").contains("cite only transaction ids");
    }

    private static List<String> problems(CaseReport report) {
        return CaseReportValidator.problems(report, SEEN, RULES);
    }

    static CaseReport report(Disposition disposition, int score, List<String> evidence) {
        return new CaseReport(disposition, score, "summary", List.of(new CaseReport.Finding("finding", evidence)),
                List.of("action"));
    }
}
