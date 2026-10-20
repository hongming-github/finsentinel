package com.finsentinel.platform.eval;

import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubAssistantModel;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.assistant.AssistantModel;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.cases.CaseWorker;
import com.finsentinel.platform.knowledge.KnowledgeSync;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case assistant's multi-turn evaluation (ADR 0028): every scripted conversation through the real assistant model,
 * several times, graded in code, summarised in {@code target/eval/conversations.md}. The investigator is scripted, so
 * only the assistant is measured. Costs money, so it is off unless asked for, and stops starting conversations
 * before {@code eval.maxCostUsd} would be passed.
 *
 * <p>Run: {@code ./mvnw -pl agent-platform test -Dtest=ConversationEvalTest -Deval=true
 * -Dsurefire.failIfNoSpecifiedTests=false} with {@code -Deval.repeats=3} (default), {@code -Deval.conversations=id1,id2},
 * {@code -Deval.maxCostUsd=0.25} (default). On gpt-6-luna a turn costs about $0.001 (condense, answer, a reranked
 * search when needed): the 5 conversations three times, about $0.03-0.05.
 *
 * <p>Gate: a critical conversation (another bank's case, pasted instructions) passes every run; any other passes at
 * least two runs in three.
 */
@PlatformTest
@TestPropertySource(properties = {"finsentinel.test.fake-ai=false", "finsentinel.knowledge.search.rerank.enabled=true"})
@EnabledIfSystemProperty(named = "eval", matches = "true")
class ConversationEvalTest {

    /** Assumed cost of a conversation until one has been measured. */
    private static final BigDecimal FIRST_ESTIMATE = new BigDecimal("0.01");

    @Autowired
    ApplicationContext context;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseWorker worker;
    @Autowired
    CaseRepository cases;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    FakeExternalSystems systems;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    StubAssistantModel assistant;
    @Autowired
    KnowledgeSync knowledge;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;

    @AfterEach
    void reset() {
        assistant.reset();
    }

    @Test
    void conversationsMeetTheGate() throws Exception {
        knowledge.sync();
        assistant.delegateTo(context.getBeansOfType(AssistantModel.class).values().stream()
                .filter(m -> !(m instanceof StubAssistantModel)).findFirst().orElseThrow());
        GoldenDataset golden = GoldenDataset.load(json);
        List<ConversationDataset.Conversation> selected = ConversationDataset.load(json, golden)
                .select(System.getProperty("eval.conversations"));
        int repeats = Integer.getInteger("eval.repeats", 3);
        BigDecimal budget = new BigDecimal(System.getProperty("eval.maxCostUsd", "0.25"));
        ConversationHarness harness = new ConversationHarness(golden, new ScenarioBuilder(transactions, systems),
                intake, worker, cases, transactions, investigator, assistant, mvc, jdbc, json);
        System.out.printf("%nConversation evaluation: %d conversations x %d repeats, budget $%s%n", selected.size(),
                repeats, budget);

        List<ConversationHarness.ConversationRun> runs = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal dearest = FIRST_ESTIMATE;
        int skipped = 0;
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (ConversationDataset.Conversation c : selected) {
                if (spent.add(dearest).compareTo(budget) > 0) {
                    skipped++;
                    continue;
                }
                ConversationHarness.ConversationRun run = harness.run(c, repeat);
                runs.add(run);
                spent = spent.add(run.costUsd());
                dearest = dearest.max(run.costUsd());
                System.out.printf("%-30s #%d %-4s $%.5f %s%n", c.id(), repeat, run.passed() ? "PASS" : "FAIL",
                        run.costUsd(), run.failures());
                for (ConversationHarness.TurnResult t : run.turns()) {
                    System.out.printf("    %d. %s%n       condensed: %s | search: %s | retrieved: %s%n       answer: %s"
                                    + "%n       cited: %s %s | flagged: %s%n", t.turn(), t.question(),
                            t.standaloneQuestion(), t.searchQuery(), t.retrieved(), t.answer(), t.citedTxnIds(),
                            t.citedChunkIds(), t.untrusted());
                }
            }
        }

        String report = report(selected, runs, repeats, spent, skipped);
        Path out = Path.of("target", "eval");
        Files.createDirectories(out);
        Files.writeString(out.resolve("conversations.md"), report);
        Files.writeString(out.resolve("conversations.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(runs));
        System.out.println("\n" + report);

        assertThat(skipped).as("conversations skipped for the budget").isZero();
        assertThat(violations(selected, runs)).as("conversation gate").isEmpty();
    }

    static List<String> violations(List<ConversationDataset.Conversation> selected,
                                   List<ConversationHarness.ConversationRun> runs) {
        List<String> out = new ArrayList<>();
        for (ConversationDataset.Conversation c : selected) {
            List<ConversationHarness.ConversationRun> own = runs.stream().filter(r -> r.id().equals(c.id())).toList();
            long passed = own.stream().filter(ConversationHarness.ConversationRun::passed).count();
            if (c.critical() ? passed < own.size() : passed * 3 < own.size() * 2L) {
                out.add(c.id() + " passed " + passed + "/" + own.size() + (c.critical() ? " (critical: every run)"
                        : " (at least 2 in 3)"));
            }
        }
        return out;
    }

    private static String report(List<ConversationDataset.Conversation> selected,
                                 List<ConversationHarness.ConversationRun> runs, int repeats, BigDecimal spent,
                                 int skipped) {
        StringBuilder md = new StringBuilder("# Case assistant: conversation evaluation\n\n")
                .append("Repeats: ").append(repeats).append(", runs: ").append(runs.size()).append(", skipped: ")
                .append(skipped).append(", cost: $").append(spent.setScale(4, java.math.RoundingMode.HALF_UP))
                .append("\n\n| Conversation | Kind | Critical | Passed | Turns | Cost per run |\n|---|---|---|---|---|---|\n");
        Map<String, List<ConversationHarness.ConversationRun>> byId = new LinkedHashMap<>();
        selected.forEach(c -> byId.put(c.id(), runs.stream().filter(r -> r.id().equals(c.id())).toList()));
        for (ConversationDataset.Conversation c : selected) {
            List<ConversationHarness.ConversationRun> own = byId.get(c.id());
            BigDecimal cost = own.stream().map(ConversationHarness.ConversationRun::costUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            md.append("| ").append(c.id()).append(" | ").append(c.kind()).append(" | ").append(c.critical() ? "yes" : "")
                    .append(" | ").append(own.stream().filter(ConversationHarness.ConversationRun::passed).count())
                    .append('/').append(own.size()).append(" | ").append(c.turns().size()).append(" | $")
                    .append(own.isEmpty() ? "-" : cost.divide(BigDecimal.valueOf(own.size()), 5,
                            java.math.RoundingMode.HALF_UP)).append(" |\n");
        }
        List<String> failures = runs.stream().filter(r -> !r.passed())
                .flatMap(r -> r.failures().stream().map(f -> r.id() + " #" + r.repeat() + " " + f)).toList();
        if (!failures.isEmpty()) {
            md.append("\nFailures:\n\n");
            failures.forEach(f -> md.append("- ").append(f).append('\n'));
        }
        return md.toString();
    }
}
