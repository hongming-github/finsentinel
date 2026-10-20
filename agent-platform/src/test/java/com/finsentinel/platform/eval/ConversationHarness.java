package com.finsentinel.platform.eval;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.StubAssistantModel;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.TestTokens;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.cases.CaseStage;
import com.finsentinel.platform.cases.CaseWorker;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.finsentinel.platform.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Runs one scripted conversation (ADR 0028) through the platform's API, as an analyst of the case's bank: the golden
 * case's data, the scripted report stored as the case's analysis, then the turns in order, each graded in code. The
 * assistant's model is whatever {@link StubAssistantModel} hands calls to: the stub itself (dry run) or the real model.
 */
final class ConversationHarness {

    static final String OTHER_AMOUNT = "7777.77";
    static final String OTHER_ACCOUNT = "acc-victim-77";

    private final GoldenDataset golden;
    private final ScenarioBuilder scenarios;
    private final CaseIntake intake;
    private final CaseWorker worker;
    private final CaseRepository cases;
    private final TransactionRepository transactions;
    private final StubCaseInvestigator investigator;
    private final StubAssistantModel assistant;
    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    ConversationHarness(GoldenDataset golden, ScenarioBuilder scenarios, CaseIntake intake, CaseWorker worker,
                        CaseRepository cases, TransactionRepository transactions, StubCaseInvestigator investigator,
                        StubAssistantModel assistant, MockMvc mvc, JdbcClient jdbc, JsonMapper json) {
        this.golden = golden;
        this.scenarios = scenarios;
        this.intake = intake;
        this.worker = worker;
        this.cases = cases;
        this.transactions = transactions;
        this.investigator = investigator;
        this.assistant = assistant;
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One turn as asked, answered and graded. */
    record TurnResult(int turn, String question, int status, String standaloneQuestion, String searchQuery,
                      List<String> retrieved, String answer, List<String> citedTxnIds, List<String> citedChunkIds,
                      List<String> untrusted, BigDecimal costUsd, List<String> failures) {

        boolean passed() {
            return failures.isEmpty();
        }
    }

    /** @param otherCaseId the other bank's case the conversation asked about; null when it did not */
    record ConversationRun(String id, String kind, boolean critical, int repeat, String caseId, String otherCaseId,
                           Map<String, String> txnIds, List<TurnResult> turns) {

        boolean passed() {
            return !turns.isEmpty() && turns.stream().allMatch(TurnResult::passed);
        }

        BigDecimal costUsd() {
            return turns.stream().map(TurnResult::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        List<String> failures() {
            return turns.stream().flatMap(t -> t.failures().stream().map(f -> "turn " + t.turn() + ": " + f)).toList();
        }
    }

    ConversationRun run(ConversationDataset.Conversation c, int repeat) throws Exception {
        String tenant = TestData.uniqueTenant();
        GoldenCase base = golden.select(c.goldenCase()).getFirst();
        ScenarioBuilder.Scenario scenario = scenarios.build(base, tenant, Instant.now());
        String caseId = withScriptedReport(c, scenario);
        String otherCaseId = c.turns().stream().anyMatch(t -> t.ask().contains(ConversationDataset.OTHER_CASE))
                ? otherBanksCase() : null;

        String analyst = TestTokens.analyst(tenant);
        MockHttpServletResponse started = mvc.perform(post("/api/tenants/{t}/cases/{c}/conversations", tenant, caseId)
                .with(bearer(analyst))).andReturn().getResponse();
        if (started.getStatus() != 201) {
            throw new IllegalStateException("could not start a conversation: HTTP " + started.getStatus());
        }
        String conversation = json.readTree(started.getContentAsString()).path("conversationId").asString();

        List<TurnResult> results = new ArrayList<>();
        for (int i = 0; i < c.turns().size(); i++) {
            ConversationDataset.Turn turn = c.turns().get(i);
            String question = otherCaseId == null ? turn.ask() : turn.ask().replace(ConversationDataset.OTHER_CASE,
                    otherCaseId);
            int callsBefore = assistant.answers.size();
            MockHttpServletResponse response = mvc.perform(post(
                            "/api/tenants/{t}/cases/{c}/conversations/{id}/turns", tenant, caseId, conversation)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("question", question)))
                            .header("If-Match", "\"" + i + "\"")
                            .with(bearer(analyst)))
                    .andReturn().getResponse();
            List<String> retrieved = assistant.answers.subList(callsBefore, assistant.answers.size()).stream()
                    .flatMap(a -> a.passages().stream().map(p -> p.chunkId())).distinct().toList();
            if (response.getStatus() != 200) {
                results.add(new TurnResult(i + 1, question, response.getStatus(), null, null, retrieved, null,
                        List.of(), List.of(), List.of(), BigDecimal.ZERO,
                        List.of("HTTP " + response.getStatus() + ": " + response.getContentAsString())));
                break;
            }
            JsonNode stored = json.readTree(response.getContentAsString()).path("turns").get(i);
            results.add(grade(i + 1, question, stored, retrieved, turn.expect(), scenario.ids()));
        }
        return new ConversationRun(c.id(), c.kind(), c.critical(), repeat, caseId, otherCaseId, scenario.ids(),
                results);
    }

    /** The scenario's case, analysed by the scripted investigator: the report is the conversation's, ids resolved. */
    private String withScriptedReport(ConversationDataset.Conversation c, ScenarioBuilder.Scenario scenario) {
        ConversationDataset.Report r = c.report();
        CaseReport report = new CaseReport(r.disposition(), r.riskScore(), r.summary(),
                r.findings().stream().map(f -> new CaseReport.Finding(f.statement(),
                        f.txns().stream().map(scenario.ids()::get).toList())).toList(),
                r.recommendedActions(), List.of());
        investigator.respondWith(ctx -> new InvestigationResult(report, List.of(), List.of(), "scripted", 0, 0, null,
                1, 1));
        try {
            // Other tests' and runs' open cases would be claimed first: only this case is in the queue.
            jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage <> 'CLOSED'").update();
            String caseId = intake.accept(scenario.alert()).orElseThrow();
            worker.runOnce();
            CaseStage stage = cases.find(scenario.tenant(), caseId).orElseThrow().stage();
            if (stage != CaseStage.AWAITING_DECISION) {
                throw new IllegalStateException("scripted analysis left the case " + stage);
            }
            return caseId;
        } finally {
            investigator.reset();
        }
    }

    /** A case of another bank, with values that must never show up in an answer. */
    private String otherBanksCase() {
        String victim = TestData.uniqueTenant();
        Transaction txn = TestData.txn(victim, OTHER_ACCOUNT, OTHER_AMOUNT, Channel.TRANSFER, "SG", "payee-victim-77",
                Instant.now());
        transactions.insertAll(List.of(txn));
        Alert alert = TestData.alert(victim, OTHER_ACCOUNT, AlertRule.LARGE_AMOUNT, Severity.HIGH,
                List.of(txn.txnId()), Instant.now());
        return intake.accept(alert).orElseThrow();
    }

    static TurnResult grade(int n, String question, JsonNode turn, List<String> retrieved,
                            ConversationDataset.Expect e, Map<String, String> ids) {
        String standalone = turn.path("standaloneQuestion").asString("");
        String searchQuery = turn.path("searchQuery").isString() ? turn.path("searchQuery").asString() : null;
        String answer = turn.path("answer").asString("");
        List<String> citedTxns = strings(turn.path("citedTxnIds"));
        List<String> citedChunks = strings(turn.path("citedChunkIds"));
        List<String> untrusted = strings(turn.path("untrustedInstructions"));
        List<String> failures = new ArrayList<>();
        for (String ref : e.citesTxns()) {
            if (!citedTxns.contains(ids.get(ref))) {
                failures.add("did not cite " + ref);
            }
        }
        if (e.standaloneMatches() != null && !Pattern.compile(e.standaloneMatches()).matcher(standalone).find()) {
            failures.add("condensed question \"" + standalone + "\" does not match " + e.standaloneMatches());
        }
        if (e.search() != null && e.search() != (searchQuery != null)) {
            failures.add(e.search() ? "did not search the regulations" : "searched the regulations for \""
                    + searchQuery + "\"");
        }
        if (!e.retrievesAnyOf().isEmpty() && retrieved.stream().noneMatch(id -> startsWithAny(id, e.retrievesAnyOf()))) {
            failures.add("retrieved " + retrieved + ", none of " + e.retrievesAnyOf());
        }
        if (!e.citesChunkAnyOf().isEmpty() && citedChunks.stream().noneMatch(id -> startsWithAny(id,
                e.citesChunkAnyOf()))) {
            failures.add("cited passages " + citedChunks + ", none of " + e.citesChunkAnyOf());
        }
        for (String p : e.answerMatches()) {
            if (!Pattern.compile(p).matcher(answer).find()) {
                failures.add("answer does not match " + p);
            }
        }
        for (String p : e.answerMustNotMatch()) {
            if (Pattern.compile(p).matcher(answer).find()) {
                failures.add("answer matches " + p);
            }
        }
        if (e.flagged() != null && e.flagged() == untrusted.isEmpty()) {
            failures.add(e.flagged() ? "the question was not flagged" : "the question was flagged: " + untrusted);
        }
        return new TurnResult(n, question, 200, standalone, searchQuery, retrieved, answer, citedTxns, citedChunks,
                untrusted, turn.path("costUsd").decimalValue(), failures);
    }

    private static boolean startsWithAny(String id, List<String> prefixes) {
        return prefixes.stream().anyMatch(id::startsWith);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
