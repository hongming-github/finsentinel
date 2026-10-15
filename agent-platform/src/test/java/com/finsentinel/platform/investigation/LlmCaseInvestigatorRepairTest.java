package com.finsentinel.platform.investigation;

import com.finsentinel.platform.privacy.PiiMasker;
import com.finsentinel.common.Alert;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.systems.BankSystem;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.llm.TestLlm;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
    PiiMasker masker;
    @Autowired
    InvestigationToolsFactory toolsFactory;
    @Autowired
    InvestigationProperties props;
    @Autowired
    JsonMapper json;
    @Autowired
    ModelRouter router;

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
    void casesOfTheSameRuleShareThePlaybookAsAPromptPrefix() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()),
                CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));

        investigator(model).investigate(anyCase());
        investigator(model).investigate(anyCase());

        // The provider caches shared prefixes only: case data placed before the playbook made every case a miss.
        String a = model.prompts.get(0);
        String b = model.prompts.get(1);
        int shared = 0;
        while (shared < Math.min(a.length(), b.length()) && a.charAt(shared) == b.charAt(shared)) {
            shared++;
        }
        assertThat(a.substring(0, shared)).contains("Playbook for VELOCITY").contains("card testing");
        assertThat(a.substring(shared)).as("after the first case-specific character").doesNotContain("Playbook");
    }

    @Test
    void aBankMissingASystemIsToldWhichChecksAreNotAvailable() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()),
                CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));
        Alert alert = alert(uniqueTenant(), "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t1"), Instant.now());

        investigator(model).investigate(TestData.context(java.util.Set.of(BankSystem.BANKING), alert));
        investigator(model).investigate(TestData.context(alert));

        assertThat(model.prompts.get(0)).contains("This bank has not connected [COMPLIANCE]");
        assertThat(model.prompts.get(1)).as("a fully connected bank's prompt is unchanged").doesNotContain("not connected");
    }

    @Test
    void personalDataInTheAnalystsNoteIsPseudonymisedBeforeTheModel() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));
        String tenant = uniqueTenant();
        CaseContext reanalysis = new CaseContext(UUID.randomUUID().toString(), tenant, "acc-1", "cus-acc-1", List.of(
                alert(tenant, "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t1"), Instant.now())), 2,
                "Customer (S7654321Z) confirmed by phone on 9876 5432 that the burst was theirs.", BankSystem.ALL);

        investigator(model).investigate(reanalysis);

        assertThat(model.prompts.getFirst()).doesNotContain("S7654321Z", "9876 5432")
                .contains("[NRIC_", "[PHONE_", "confirmed by phone");
    }

    @Test
    void caseWithTwoRulesGetsBothPlaybooksAndTheAnalystNote() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));
        String tenant = uniqueTenant();
        CaseContext twoRules = new CaseContext(UUID.randomUUID().toString(), tenant, "acc-1", "cus-acc-1", List.of(
                alert(tenant, "acc-1", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t1"), Instant.now()),
                alert(tenant, "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t2"), Instant.now())),
                2, "Customer says the UK purchase was theirs; check the card-testing burst separately.", BankSystem.ALL);

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
        return new LlmCaseInvestigator(models(model), toolsFactory, json, props,
                new ByteArrayResource("You are a test investigator.".getBytes(StandardCharsets.UTF_8)));
    }

    private static CaseContext anyCase() {
        return context(alert(uniqueTenant(), "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t1"), Instant.now()));
    }

    private AgentModels models(ChatModel model) {
        return new AgentModels(TestLlm.gateway(model, model, TestLlm.properties(Set.of()), Clock.systemUTC()), router, masker);
    }
}
