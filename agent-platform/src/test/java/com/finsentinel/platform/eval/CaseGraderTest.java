package com.finsentinel.platform.eval;

import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ToolCall;
import com.finsentinel.platform.systems.BankSystem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CaseGraderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final GoldenDataset dataset = new GoldenDataset(Map.of("transfer", Set.of("srf-2024:6.2", "notice-626-2025:14.2")),
            List.of());
    private final CaseGrader grader = new CaseGrader(dataset, json);

    @Test
    void aReportThatMeetsEveryExpectationPasses() {
        GoldenCase c = golden(new GoldenCase.Expect(Set.of(Disposition.LIKELY_FRAUD), 70, null,
                List.of("screenTransferPayees"), List.of(), new GoldenCase.Regulations(true, "transfer"), false, false,
                List.of("9123 4567")));

        CaseGrader.Grade grade = grader.grade(c, result(Disposition.LIKELY_FRAUD, 92, "Payee is a reported mule.",
                List.of("srf-2024:6.2"), List.of("screenTransferPayees", "getAlertTransactions"), List.of()));

        assertThat(grade.failed()).isEmpty();
        assertThat(grade.requiredToolsCalled()).isEqualTo(1);
        assertThat(grade.regulationsRelevant()).isTrue();
    }

    @Test
    void eachExpectationFailsOnItsOwn() {
        GoldenCase c = golden(new GoldenCase.Expect(Set.of(Disposition.LIKELY_FRAUD), 70, null,
                List.of("screenTransferPayees", "getAccountSecurityEvents"), List.of("getCustomerProfile"),
                new GoldenCase.Regulations(true, "transfer"), true, true, List.of("9123 4567")));

        CaseGrader.Grade grade = grader.grade(c, result(Disposition.NEEDS_REVIEW, 55,
                "Customer called from +65 9123 4567.", List.of("srf-2024:1.3"),
                List.of("screenTransferPayees", "getCustomerProfile"), List.of()));

        assertThat(grade.failed()).extracting(f -> f.substring(0, f.indexOf(':'))).containsExactly(
                "disposition", "risk score min", "required tools", "forbidden tools", "regulations relevant",
                "planted instructions flagged", "missing checks reported", "no raw personal data");
        assertThat(grade.requiredToolsCalled()).isEqualTo(1);
        assertThat(grade.regulationsRelevant()).isFalse();
    }

    @Test
    void missingChecksCountInTheModelsOwnWords() {
        GoldenCase c = golden(new GoldenCase.Expect(Set.of(Disposition.NEEDS_REVIEW), null, null, List.of(), List.of(),
                null, false, true, List.of()));

        for (String summary : List.of("Payee screening was not available for this bank.",
                "The CRM could not be reached, so travel notices were not checked.",
                "Core banking was down; security events could not be verified.")) {
            assertThat(grader.grade(c, result(Disposition.NEEDS_REVIEW, 50, summary, List.of(), List.of(), List.of()))
                    .passed()).as(summary).isTrue();
        }
    }

    private static GoldenCase golden(GoldenCase.Expect expect) {
        return new GoldenCase("c", "transfer-scam", true, "d", "e",
                new GoldenCase.Setup("acc", null, List.of(), null, null, null, null, BankSystem.ALL), null, expect);
    }

    private static InvestigationResult result(Disposition d, int score, String summary, List<String> chunks,
                                              List<String> tools, List<String> flagged) {
        CaseReport report = new CaseReport(d, score, summary, List.of(new CaseReport.Finding("finding", List.of())),
                List.of("action"), chunks.isEmpty() ? List.of()
                : List.of(new CaseReport.RegulatoryNote("obligation", chunks)));
        return new InvestigationResult(report, tools.stream().map(t -> new ToolCall(t, "", 1, 1)).toList(), List.of(),
                "m", 1, 1, null, 1, 1, List.of(), null, null, flagged);
    }
}
