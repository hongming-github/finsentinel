package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.alert.AlertStatus;
import com.finsentinel.platform.alert.StoredAlert;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.finsentinel.platform.TestData.alert;
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
        ScriptedModel model = new ScriptedModel(
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 15, List.of()),
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 88, List.of()));

        InvestigationResult result = investigator(model).investigate(anyAlert());

        assertThat(result.report().riskScore()).isEqualTo(88);
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(result.repairedIssues()).containsExactly("disposition LIKELY_FRAUD needs riskScore >= 60, got 15");
        assertThat(model.prompts.get(1)).contains("rejected for these reasons").contains("got 15");
    }

    @Test
    void draftThatStaysInvalidFailsTheInvestigation() {
        ScriptedModel model = new ScriptedModel(
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("invented-id")),
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("still-invented")));

        assertThatThrownBy(() -> investigator(model).investigate(anyAlert()))
                .isInstanceOf(InvalidReportException.class)
                .hasMessageContaining("still-invented");
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void validFirstDraftCostsOneCall() {
        ScriptedModel model = new ScriptedModel(CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));

        InvestigationResult result = investigator(model).investigate(anyAlert());

        assertThat(result.llmCalls()).isEqualTo(1);
        assertThat(result.repairedIssues()).isEmpty();
    }

    private LlmCaseInvestigator investigator(ChatModel model) {
        return new LlmCaseInvestigator(ChatClient.builder(model), toolsFactory, json, props,
                new ByteArrayResource("You are a test investigator.".getBytes()));
    }

    private static StoredAlert anyAlert() {
        return new StoredAlert(alert(uniqueTenant(), "acc-1", AlertRule.VELOCITY, Severity.MEDIUM, List.of("t1"),
                Instant.now()), AlertStatus.INVESTIGATING, Instant.now());
    }

    /** Returns the given reports in order, as JSON text, and records what it was asked. */
    private class ScriptedModel implements ChatModel {

        private final Deque<CaseReport> replies;
        final List<String> prompts = new ArrayList<>();

        ScriptedModel(CaseReport... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getUserMessage().getText());
            String reply = json.writeValueAsString(replies.removeFirst());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }
}
