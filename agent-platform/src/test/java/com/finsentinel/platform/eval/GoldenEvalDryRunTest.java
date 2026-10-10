package com.finsentinel.platform.eval;

import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.systems.AccountDetails;
import com.finsentinel.platform.systems.SystemUnavailableException;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evaluation without a model: the dataset, every scenario's setup, claiming, grading, the report and the gate,
 * driven by the stub agents. Free, so it runs with every build and a broken golden case is found before anyone pays
 * for an evaluation.
 */
@PlatformTest
class GoldenEvalDryRunTest {

    @Autowired
    JsonMapper json;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    FakeExternalSystems systems;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseRepository cases;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CaseAnalysisGraph stubbedGraph;

    @AfterEach
    void resetSystems() {
        systems.reset();
    }

    @Test
    void theDatasetIsValid() {
        GoldenDataset dataset = GoldenDataset.load(json);

        assertThat(dataset.problems()).isEmpty();
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(16);
        assertThat(dataset.cases()).filteredOn(GoldenCase::critical).isNotEmpty();
        assertThat(dataset.cases()).extracting(GoldenCase::category).contains("false-positive", "injection");
    }

    @Test
    void scenariosPutTheirDataWhereTheAgentsWillLook() {
        GoldenDataset dataset = GoldenDataset.load(json);
        ScenarioBuilder builder = new ScenarioBuilder(transactions, systems);
        String tenant = TestData.uniqueTenant();
        Instant now = Instant.now();

        ScenarioBuilder.Scenario takeover = builder.build(dataset.select("takeover-mule-payee").getFirst(), tenant, now);

        assertThat(takeover.alert().txnIds()).containsExactly(takeover.ids().get("transfer"));
        assertThat(transactions.findRecent(tenant, "acc-ato", now.minusSeconds(86_400 * 30), 100)).hasSize(21);
        assertThat(systems.accountDetails(tenant, "acc-ato").recentSecurityEvents())
                .extracting(AccountDetails.SecurityEvent::type)
                .containsExactly("PHONE_NUMBER_CHANGED", "NEW_DEVICE_LOGIN", "TRANSFER_LIMIT_RAISED");
        assertThat(systems.screenCounterparty(tenant, "payee-new-4415").hits()).isNotEmpty();

        String other = TestData.uniqueTenant();
        builder.build(dataset.select("banking-system-down").getFirst(), other, now);
        assertThatThrownBy(() -> systems.customerProfile(other, "cus-acc-down"))
                .isInstanceOf(SystemUnavailableException.class);
    }

    @Test
    void everyCaseRunsThroughTheHarnessAndTheGateFailsClosedOnAStub() {
        GoldenDataset dataset = GoldenDataset.load(json);
        EvalHarness harness = new EvalHarness(new ScenarioBuilder(transactions, systems), new CaseGrader(dataset, json),
                intake, cases, jdbc, systems, null);

        List<RunRecord> records = dataset.cases().stream()
                .map(c -> harness.run(c, stubbedGraph, AnalysisMode.SINGLE, 1)).toList();

        // The stub answers NEEDS_REVIEW 50 without calling any tool: every case runs, and the grader says what is wrong.
        assertThat(records).hasSize(dataset.cases().size()).allSatisfy(r -> {
            assertThat(r.status()).isIn(RunRecord.PASSED, RunRecord.FAILED);
            assertThat(r.failures()).noneMatch(f -> f.startsWith("analysis failed"));
        });
        Map<String, RunRecord> byCase = records.stream().collect(Collectors.toMap(RunRecord::caseId, r -> r));
        assertThat(byCase.get("takeover-mule-payee").failures())
                .anySatisfy(f -> assertThat(f).startsWith("disposition"))
                .anySatisfy(f -> assertThat(f).startsWith("required tools"));
        assertThat(byCase.get("injection-crm-note").failures())
                .anySatisfy(f -> assertThat(f).startsWith("planted instructions flagged"));
        assertThat(byCase.get("outlier-at-home").failures()).as("NEEDS_REVIEW 50 is an accepted verdict here")
                .noneMatch(f -> f.startsWith("disposition") || f.startsWith("risk score"));

        EvalReport report = EvalReport.of(Instant.now().toString(), "[SINGLE]", 1, records);
        assertThat(report.markdown()).contains("| Pass rate |", "takeover-mule-payee");
        assertThat(EvalGate.violations(report, EvalGate.Thresholds.load(json))).isNotEmpty()
                .anySatisfy(v -> assertThat(v).startsWith("pass rate"))
                .anySatisfy(v -> assertThat(v).startsWith("critical case takeover-mule-payee"));
    }
}
