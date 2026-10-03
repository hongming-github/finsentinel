package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.context;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the validate-and-repair loop with a scripted model: no network, no cost, deterministic. */
@PlatformTest
class LlmCaseInvestigatorRepairTest {

    @Autowired
    InvestigationToolsFactory toolsFactory;
    @Autowired
    InvestigationProperties props;
    @Autowired
    JsonMapper json;

    @Test
    void inconsistentDraftIsRepairedWithTheProblemsSpelledOut() {
        ScriptedChatModel model = new ScriptedChatModel(json, 
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 15, List.of()),
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 88, List.of()));

        InvestigationResult result = investigator(model).investigate(anyCase());

        assertThat(result.report().riskScore()).isEqualTo(88);
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(result.repairedIssues()).containsExactly("disposition LIKELY_FRAUD needs riskScore >= 60, got 15");
        assertThat(model.prompts.get(1)).contains("rejected for these reasons").contains("got 15");
    }

    @Test
    void draftThatStaysInvalidFailsTheInvestigation() {
        ScriptedChatModel model = new ScriptedChatModel(json, 
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("invented-id")),
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("still-invented")));

        assertThatThrownBy(() -> investigator(model).investigate(anyCase()))
                .isInstanceOf(InvalidReportException.class)
                .hasMessageContaining("still-invented");
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void briefCarriesOnlyThePlaybookForTheAlertsRule() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));

        investigator(model).investigate(anyCase()); // one VELOCITY alert

        assertThat(model.prompts.getFirst()).contains("Playbook for VELOCITY").contains("card testing")
                .doesNotContain("impossible travel").doesNotContain("authorised push payment");
    }

    @Test
    void caseWithTwoRulesGetsBothPlaybooksAndTheAnalystNote() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));
        String tenant = uniqueTenant();
        CaseContext twoRules = new CaseContext(UUID.randomUUID().toString(), tenant, "acc-1", "cus-acc-1", List.of(
                alert(tenant, "acc-1", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t1"), Instant.now()),
                alert(tenant, "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t2"), Instant.now())),
                2, "Customer says the UK purchase was theirs; check the card-testing burst separately.");

        investigator(model).investigate(twoRules);

        assertThat(model.prompts.getFirst()).contains("Playbook for GEO_JUMP").contains("Playbook for VELOCITY")
                .doesNotContain("Playbook for LARGE_AMOUNT")
                .contains("sent the case back").contains("Customer says the UK purchase was theirs");
    }

    @Test
    void validFirstDraftCostsOneCall() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));

        InvestigationResult result = investigator(model).investigate(anyCase());

        assertThat(result.llmCalls()).isEqualTo(1);
        assertThat(result.repairedIssues()).isEmpty();
    }

    private LlmCaseInvestigator investigator(ChatModel model) {
        return new LlmCaseInvestigator(ChatClient.builder(model), toolsFactory, json, props,
                new ByteArrayResource("You are a test investigator.".getBytes()));
    }

    private static CaseContext anyCase() {
        return context(alert(uniqueTenant(), "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t1"), Instant.now()));
    }
}
