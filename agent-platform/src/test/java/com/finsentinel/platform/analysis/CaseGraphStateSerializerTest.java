package com.finsentinel.platform.analysis;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PlatformTest
class CaseGraphStateSerializerTest {

    @Autowired
    CaseGraphStateSerializer serializer;

    @Test
    void stateSurvivesJsonAsTypedRecords() throws Exception {
        CaseContext caseContext = context(
                alert("t1", "acc-1", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t-1", "t-2"), Instant.parse("2026-10-01T10:00:00Z")),
                alert("t1", "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t-3"), Instant.parse("2026-10-01T10:05:00Z")));
        CaseReport report = new CaseReport(Disposition.LIKELY_FRAUD, 90, "summary",
                List.of(new CaseReport.Finding("finding", List.of("t-1"))), List.of("call the customer"),
                List.of(new CaseReport.RegulatoryNote("statement", List.of("mas-626#4.2"))));
        // Every field shape a real result has, including a null token count.
        InvestigationResult investigation = new InvestigationResult(report,
                List.of(new ToolCall("getAlertTransactions", "{}", 2, 12)), List.of("missing evidence"), "gpt-5.4-mini",
                4033, 575, null, 2, 5673);
        Map<String, Object> data = Map.of(
                CaseGraphState.CASE, caseContext,
                CaseGraphState.INVESTIGATION, investigation,
                CaseGraphState.REVIEW, new Review(true, "ok"));

        CaseGraphState copy = serializer.readDataFromString(serializer.toJson(data));

        assertThat(copy.caseContext()).isEqualTo(caseContext);
        assertThat(copy.investigation()).contains(investigation);
        assertThat(copy.review()).contains(new Review(true, "ok"));
    }

    @Test
    void undeclaredKeysAreRejectedRatherThanReadBackAsMaps() {
        assertThatThrownBy(() -> serializer.toJson(Map.of("scratch", List.of(1, 2))))
                .hasMessageContaining("undeclared graph state key 'scratch'");
        assertThatThrownBy(() -> serializer.readDataFromString("{\"scratch\": 1}"))
                .hasMessageContaining("undeclared graph state key 'scratch'");
    }
}
