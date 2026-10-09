package com.finsentinel.platform.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CaseReportValidatorTest {

    private static final InvestigationProperties.Report RULES = new InvestigationProperties.Report(60, 40, 1, true);
    private static final Set<String> SEEN = Set.of("t1", "t2");

    @Test
    void consistentGroundedReportPasses() {
        assertThat(problems(report(Disposition.LIKELY_FRAUD, 90, List.of("t1")))).isEmpty();
        assertThat(problems(report(Disposition.NEEDS_REVIEW, 50, List.of()))).isEmpty();
        assertThat(problems(report(Disposition.LIKELY_LEGITIMATE, 10, List.of("t2")))).isEmpty();
    }

    @Test
    void aCaseWithPlantedInstructionsCannotBeCleared() {
        List<String> flagged = List.of("getCustomerProfile: names a case disposition or risk score");
        InvestigationProperties.Report rules = new InvestigationProperties.Report(60, 40, 1, true);

        assertThat(CaseReportValidator.problems(report(Disposition.LIKELY_LEGITIMATE, 10, List.of()), Set.of(), Set.of(),
                false, flagged, rules)).singleElement().asString().contains("cannot be cleared");
        assertThat(CaseReportValidator.problems(report(Disposition.NEEDS_REVIEW, 50, List.of()), Set.of(), Set.of(),
                false, flagged, rules)).isEmpty();
        assertThat(CaseReportValidator.problems(report(Disposition.LIKELY_LEGITIMATE, 10, List.of()), Set.of(), Set.of(),
                false, List.of(), rules)).as("without planted instructions clearing is fine").isEmpty();
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
        CaseReport empty = new CaseReport(Disposition.NEEDS_REVIEW, 50, " ", List.of(), List.of(), List.of());

        assertThat(problems(empty)).containsExactlyInAnyOrder("summary is empty", "at least one finding is required",
                "at least one recommended action is required");
    }

    @Test
    void inventedEvidenceIsRejectedWithTheOffendingIds() {
        assertThat(problems(report(Disposition.LIKELY_FRAUD, 90, List.of("t1", "m-1045"))))
                .singleElement().asString().contains("[m-1045]").contains("cite only transaction ids");
    }

    @Test
    void regulatoryNotesMustCiteRetrievedPassages() {
        CaseReport cited = withNotes(new CaseReport.RegulatoryNote("12h cooling off for new payees", List.of("eupg-2024:4.7")));
        CaseReport uncited = withNotes(new CaseReport.RegulatoryNote("banks must refund scam losses", List.of()));
        CaseReport invented = withNotes(new CaseReport.RegulatoryNote("STR within 15 days", List.of("notice-626:99.9")));

        assertThat(problems(cited)).isEmpty();
        assertThat(problems(uncited)).singleElement().asString().contains("cites no passage");
        assertThat(problems(invented)).singleElement().asString().contains("[notice-626:99.9]");
    }

    @Test
    void suspiciousTransferNeedsRegulatoryNotesButCardCasesDoNot() {
        CaseReport review = report(Disposition.NEEDS_REVIEW, 70, List.of());
        CaseReport legitimate = report(Disposition.LIKELY_LEGITIMATE, 10, List.of());

        assertThat(CaseReportValidator.problems(review, SEEN, Set.of(), true, RULES))
                .singleElement().asString().contains("suspicious transfer needs regulatoryNotes");
        assertThat(CaseReportValidator.problems(review, SEEN, Set.of(), false, RULES)).isEmpty();
        assertThat(CaseReportValidator.problems(legitimate, SEEN, Set.of(), true, RULES)).isEmpty();
    }

    private static List<String> problems(CaseReport report) {
        return CaseReportValidator.problems(report, SEEN, Set.of("eupg-2024:4.7"), false, RULES);
    }

    private static CaseReport withNotes(CaseReport.RegulatoryNote... notes) {
        CaseReport base = report(Disposition.NEEDS_REVIEW, 50, List.of());
        return new CaseReport(base.disposition(), base.riskScore(), base.summary(), base.findings(),
                base.recommendedActions(), List.of(notes));
    }

    static CaseReport report(Disposition disposition, int score, List<String> evidence) {
        return new CaseReport(disposition, score, "summary", List.of(new CaseReport.Finding("finding", evidence)),
                List.of("action"), List.of());
    }
}
