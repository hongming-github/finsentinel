package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.systems.BankSystem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.finsentinel.platform.TestData.alert;
import static org.assertj.core.api.Assertions.assertThat;

class ReportWritingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Alert ALERT = alert("t-1", "acc-1", AlertRule.LARGE_AMOUNT, Severity.HIGH,
            List.of("t-stored", "t-missing"), Instant.parse("2026-10-24T08:00:00Z"));

    /** After the evidence wait the case is analysed anyway, and the brief says what is not there (ADR 0025). */
    @Test
    void theBriefNamesTransactionsThatWereNotStoredWhenTheRunBegan() {
        CaseContext context = new CaseContext("c-1", "t-1", "acc-1", "cus-acc-1", List.of(ALERT), 1, null,
                Set.of(BankSystem.values()), Set.of("t-missing"));

        assertThat(ReportWriting.caseData(context, JSON))
                .contains("[t-missing]")
                .contains("not stored yet")
                .contains("cannot be cleared");
    }

    @Test
    void aCompleteCaseSaysNothingAboutMissingTransactions() {
        CaseContext context = new CaseContext("c-1", "t-1", "acc-1", "cus-acc-1", List.of(ALERT), 1, null,
                Set.of(BankSystem.values()));

        assertThat(context.missingTxnIds()).isEmpty();
        assertThat(ReportWriting.caseData(context, JSON)).doesNotContain("not stored yet");
    }
}
