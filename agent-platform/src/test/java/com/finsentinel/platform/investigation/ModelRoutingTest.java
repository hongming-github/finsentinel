package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmPricing;
import com.finsentinel.platform.llm.LlmProperties;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.context;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

/** Routing with escalation of the investigator and writer to gpt-5.5 on HIGH alerts and transfers. */
@PlatformTest
class ModelRoutingTest {

    @Autowired
    ModelRouter configured;
    @Autowired
    LlmProperties props;
    @Autowired
    LlmPricing pricing;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    InvestigationToolsFactory toolsFactory;
    @Autowired
    InvestigationProperties investigationProps;
    @Autowired
    JsonMapper json;

    private ModelRouter router;

    @BeforeEach
    void escalateInvestigatorAndWriter() {
        router = new ModelRouter(new LlmProperties(props.models(), new LlmProperties.Escalation("gpt-5.5",
                Set.of(AgentRole.INVESTIGATOR, AgentRole.WRITER), true, Set.of(Severity.HIGH)), props.prices()),
                transactions);
    }

    @Test
    void escalationIsOffAsShipped() {
        assertThat(configured.modelFor(AgentRole.INVESTIGATOR, caseWith(Channel.TRANSFER, Severity.HIGH)))
                .isEqualTo("gpt-5.4-mini");
    }

    @Test
    void ordinaryCardCaseUsesTheRoleModels() {
        CaseContext card = caseWith(Channel.CARD_PRESENT, Severity.MEDIUM);

        assertThat(router.route(card).escalated()).isFalse();
        assertThat(router.modelFor(AgentRole.INVESTIGATOR, card)).isEqualTo("gpt-5.4-mini");
    }

    @Test
    void transferOrHighAlertEscalatesOnlyTheConfiguredRoles() {
        CaseContext transfer = caseWith(Channel.TRANSFER, Severity.MEDIUM);
        CaseContext high = caseWith(Channel.CARD_PRESENT, Severity.HIGH);

        assertThat(router.route(transfer).reasons()).containsExactly("transfer");
        assertThat(router.route(high).reasons()).containsExactly("HIGH alert");
        assertThat(router.modelFor(AgentRole.INVESTIGATOR, high)).isEqualTo("gpt-5.5");
        assertThat(router.modelFor(AgentRole.WRITER, transfer)).isEqualTo("gpt-5.5");
        assertThat(router.modelFor(AgentRole.REVIEWER, high)).as("not an escalating role").isEqualTo("gpt-5.4-mini");
    }

    @Test
    void theRoutedModelIsTheOneAskedAndItsCostIsRecorded() {
        ScriptedChatModel model = new ScriptedChatModel(json,
                CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50, List.of()));
        LlmCaseInvestigator investigator = new LlmCaseInvestigator(
                new AgentModels(ChatClient.builder(model), router, pricing), toolsFactory, json, investigationProps,
                new ByteArrayResource("You are a test investigator.".getBytes()));

        InvestigationResult result = investigator.investigate(caseWith(Channel.CARD_PRESENT, Severity.HIGH));

        assertThat(model.modelsAsked).containsExactly("gpt-5.5");
        assertThat(result.costByModel()).containsKey("gpt-5.5");
        assertThat(result.costUsd()).as("5 input + 3 output tokens at gpt-5.5 prices").isPositive();
    }

    private CaseContext caseWith(Channel channel, Severity severity) {
        String tenant = uniqueTenant();
        Transaction t = txn(tenant, "acc-1", "500", channel, "SG", Instant.now());
        transactions.insertAll(List.of(t));
        return context(alert(tenant, "acc-1", AlertRule.LARGE_AMOUNT, severity, List.of(t.txnId()), Instant.now()));
    }
}
