package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.llm.LlmPricing;
import com.finsentinel.platform.llm.LlmUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.context;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

/** The multi-agent pieces driven by a scripted model: no network, no cost, deterministic. */
@PlatformTest
class MultiAgentAgentsTest {

    @Autowired
    InvestigationToolsFactory toolsFactory;
    @Autowired
    InvestigationProperties props;
    @Autowired
    JsonMapper json;
    @Autowired
    ModelRouter router;
    @Autowired
    LlmPricing pricing;

    private final CaseContext someCase = context(alert(uniqueTenant(), "acc-1", AlertRule.GEO_JUMP, Severity.HIGH,
            List.of("t1", "t2"), Instant.now()));

    @Test
    void eachSpecialistIsOfferedOnlyItsOwnTools() {
        for (Specialist specialist : Specialist.values()) {
            ScriptedChatModel model = new ScriptedChatModel(json, new SpecialistFindings("s", List.of(), List.of()));

            new LlmSpecialistAgents(models(model), toolsFactory, json).run(specialist, someCase);

            assertThat(model.toolsOffered.getFirst()).as(specialist.name()).isEqualTo(specialist.tools());
            assertThat(model.systemPrompts.getFirst()).as(specialist.name()).contains("specialist");
        }
    }

    @Test
    void onlyTheRegulationsSpecialistMayAddRegulatoryNotes() {
        CaseReport.RegulatoryNote note = new CaseReport.RegulatoryNote("report it", List.of("c-1"));
        ScriptedChatModel model = new ScriptedChatModel(json, new SpecialistFindings("s", List.of(), List.of(note)));

        SpecialistReport report = new LlmSpecialistAgents(models(model), toolsFactory, json)
                .run(Specialist.TRANSACTIONS, someCase);

        assertThat(report.regulatoryNotes()).isEmpty();
        assertThat(report.usage().calls()).isEqualTo(1);
    }

    @Test
    void writerIsGroundedInWhatAnySpecialistSawAndRepairsAnInventedId() {
        ScriptedChatModel model = new ScriptedChatModel(json,
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("t-seen-by-customer", "invented")),
                CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD, 90, List.of("t-seen-by-transactions",
                        "t-seen-by-customer")));
        List<SpecialistReport> reports = List.of(
                report(Specialist.TRANSACTIONS, Set.of("t-seen-by-transactions"), new ToolCall("getAlertTransactions", "", 1, 3)),
                report(Specialist.CUSTOMER, Set.of("t-seen-by-customer"), new ToolCall("getCustomerProfile", "", 1, 5)));

        InvestigationResult result = writer(model).write(someCase, plan(false), reports, null);

        assertThat(model.toolsOffered).allSatisfy(tools -> assertThat(tools).as("the writer has no tools").isEmpty());
        assertThat(result.repairedIssues()).singleElement().asString().contains("invented");
        assertThat(model.prompts.get(1)).contains(ReportWriting.FIX_WITHOUT_TOOLS)
                .contains("The only transaction ids you may cite are: [t-seen-by-customer, t-seen-by-transactions]");
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(result.toolCalls()).extracting(ToolCall::tool)
                .containsExactly("getAlertTransactions", "getCustomerProfile");
    }

    @Test
    void writerGetsTheSpecialistsFindingsAndTheReviewersObjections() {
        ScriptedChatModel model = new ScriptedChatModel(json, CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW, 50,
                List.of()));
        Revision revision = new Revision(CaseReportValidatorTest.report(Disposition.LIKELY_LEGITIMATE, 10, List.of()),
                List.of("impossible travel cannot be legitimate"));

        writer(model).write(someCase, plan(false), List.of(report(Specialist.TRANSACTIONS, Set.of())), revision);

        assertThat(model.prompts.getFirst())
                .contains("Playbook for GEO_JUMP")
                .contains("TRANSACTIONS stub summary")
                .contains("A reviewer sent your previous report back")
                .contains("impossible travel cannot be legitimate")
                .doesNotContain("seenTxnIds");
    }

    @Test
    void reviewerVerdictsAreNormalised() {
        InvestigationResult anyResult = InvestigationResult.of(CaseReportValidatorTest.report(Disposition.NEEDS_REVIEW,
                50, List.of()), List.of(), List.of(), LlmUsage.NONE, List.of());

        Review approvedWithNoise = reviewer(new LlmCaseReviewer.Verdict(true, List.of("minor wording")))
                .review(someCase, anyResult, List.of());
        Review rejectedWithoutReason = reviewer(new LlmCaseReviewer.Verdict(false, List.of()))
                .review(someCase, anyResult, List.of());

        assertThat(approvedWithNoise.approved()).isTrue();
        assertThat(approvedWithNoise.issues()).isEmpty();
        assertThat(rejectedWithoutReason.approved()).isFalse();
        assertThat(rejectedWithoutReason.issues()).singleElement().asString().contains("without a reason");
        assertThat(rejectedWithoutReason.usage().calls()).isEqualTo(1);
    }

    @Test
    void reviewerOfASingleInvestigatorSeesExactlyWhatTheToolsReturned() {
        InvestigationResult withEvidence = InvestigationResult.of(CaseReportValidatorTest.report(Disposition.LIKELY_FRAUD,
                        90, List.of()), List.of(), List.of(), LlmUsage.NONE,
                List.of(new ToolEvidence("getAccountSecurityEvents", "{}", "{\"eventsLast30Days\":[\"NEW_DEVICE_LOGIN\"]}")));
        ScriptedChatModel model = new ScriptedChatModel(json, new LlmCaseReviewer.Verdict(true, List.of()));

        new LlmCaseReviewer(models(model), json, new ByteArrayResource("r".getBytes()))
                .review(someCase, withEvidence, List.of());

        assertThat(model.prompts.getFirst()).contains("getAccountSecurityEvents").contains("NEW_DEVICE_LOGIN");
    }

    private LlmReportWriter writer(ScriptedChatModel model) {
        return new LlmReportWriter(models(model), json, props,
                new ByteArrayResource("You are a test writer.".getBytes()));
    }

    private LlmCaseReviewer reviewer(LlmCaseReviewer.Verdict verdict) {
        return new LlmCaseReviewer(models(new ScriptedChatModel(json, verdict)), json,
                new ByteArrayResource("You are a test reviewer.".getBytes()));
    }

    private static AnalysisPlan plan(boolean transfer) {
        return new AnalysisPlan(Set.of(Specialist.TRANSACTIONS, Specialist.CUSTOMER), transfer, List.of("test"));
    }

    private static SpecialistReport report(Specialist specialist, Set<String> seen, ToolCall... calls) {
        return new SpecialistReport(specialist, false, specialist + " stub summary", List.of(), List.of(),
                List.of(calls), seen, Set.of(), LlmUsage.NONE);
    }

    private AgentModels models(ChatModel model) {
        return new AgentModels(ChatClient.builder(model), router, pricing);
    }
}
