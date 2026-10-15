package com.finsentinel.platform.eval;

import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.analysis.RealAgentGraph;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.knowledge.KnowledgeSync;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.ModelRef;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation (ADR 0017): every golden case through the real agents, several times, graded, optionally judged,
 * summarised in {@code target/eval/report.{md,json}}, and held to {@code eval/thresholds.json}. Costs money, so it is
 * off unless asked for, and stops starting runs before {@code eval.maxCostUsd} would be passed.
 *
 * <p>Run: {@code ./mvnw -pl agent-platform test -Dtest=GoldenEvalTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false}
 * with {@code -Deval.repeats=3} (default), {@code -Deval.modes=SINGLE,MULTI}, {@code -Deval.cases=id1,id2},
 * {@code -Deval.maxCostUsd=1.00} (default), {@code -Deval.judge=true -Deval.judgeModel=gpt-5.4-mini}. Spring settings
 * override as system properties, e.g. {@code -Dfinsentinel.llm.models.reviewer=gpt-5.5} for a routing comparison.
 * At gpt-5.4-mini prices one run costs about $0.007 to $0.046 (cases that need a repair cost more); the 17 cases
 * three times in SINGLE mode, about $0.55-0.65, plus about $0.003 per judged run.
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
@EnabledIfSystemProperty(named = "eval", matches = "true")
class GoldenEvalTest {

    /** Assumed cost of a run until one has been measured; the budget check uses the dearest run seen so far. */
    private static final BigDecimal FIRST_RUN_ESTIMATE = new BigDecimal("0.05");

    @Autowired
    ApplicationContext context;
    @Autowired
    CaseRepository cases;
    @Autowired
    CaseIntake intake;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    FakeExternalSystems systems;
    @Autowired
    KnowledgeSync knowledge;
    @Autowired
    LlmGateway gateway;
    @Autowired
    JsonMapper json;

    @Test
    void goldenCasesMeetTheThresholds() throws Exception {
        knowledge.sync();
        GoldenDataset dataset = GoldenDataset.load(json);
        List<GoldenCase> selected = dataset.select(System.getProperty("eval.cases"));
        int repeats = Integer.getInteger("eval.repeats", 3);
        List<AnalysisMode> modes = Arrays.stream(System.getProperty("eval.modes", "SINGLE").split(","))
                .map(String::trim).map(AnalysisMode::valueOf).toList();
        BigDecimal budget = new BigDecimal(System.getProperty("eval.maxCostUsd", "1.00"));
        EvalJudge judge = Boolean.getBoolean("eval.judge")
                ? new EvalJudge(gateway, ModelRef.parse(System.getProperty("eval.judgeModel", "gpt-5.4-mini")), json)
                : null;
        EvalHarness harness = new EvalHarness(new ScenarioBuilder(transactions, systems),
                new CaseGrader(dataset, json), intake, cases, jdbc, systems, judge);
        CaseAnalysisGraph graph = RealAgentGraph.of(context);
        System.out.printf("%nEvaluation: %d cases x %d repeats x %s = %d runs, budget $%s, judge %s%n",
                selected.size(), repeats, modes, selected.size() * repeats * modes.size(), budget,
                judge == null ? "off" : "on");

        String startedAt = Instant.now().toString();
        List<RunRecord> records = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal dearest = FIRST_RUN_ESTIMATE;
        // Repeat outermost: if the budget cuts the run short, every case has still run as often as possible.
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (AnalysisMode mode : modes) {
                for (GoldenCase c : selected) {
                    if (spent.add(dearest).compareTo(budget) > 0) {
                        records.add(RunRecord.skipped(c, mode.name(), repeat));
                        continue;
                    }
                    RunRecord r = harness.run(c, graph, mode, repeat);
                    records.add(r);
                    // A failed analysis spent money too, but its cost is lost with the exception: charge the dearest
                    // run seen so far against the budget, so failures cannot make the budget look larger than it is.
                    spent = spent.add(r.analysisFailed() ? dearest : r.costUsd());
                    dearest = dearest.max(r.costUsd());
                    System.out.printf("%-30s %-6s #%d %-7s %-18s %4s $%.4f %s%n", c.id(), mode, repeat, r.status(),
                            r.disposition(), r.riskScore(), r.costUsd(), r.failures());
                    r.explanation().forEach(line -> System.out.println("    " + line));
                }
            }
        }

        EvalReport report = EvalReport.of(startedAt, modes.toString(), repeats, records);
        Path out = Path.of("target", "eval");
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.md"), report.markdown());
        Files.writeString(out.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("\n" + report.markdown());

        assertThat(EvalGate.violations(report, EvalGate.Thresholds.load(json))).as("evaluation gate").isEmpty();
    }
}
