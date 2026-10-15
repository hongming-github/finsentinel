package com.finsentinel.platform.cases;

import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.investigation.InvestigationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static com.finsentinel.platform.cases.CaseTestSupport.outboxTypes;
import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class CaseWorkerTest {

    @Autowired
    CaseWorker worker;
    @Autowired
    CaseRepository cases;
    @Autowired
    SystemsProperties systems;
    @Autowired
    CaseIntake intake;
    @Autowired
    AlertRepository alerts;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();

    @BeforeEach
    void ownQueue() {
        CaseTestSupport.parkOtherCases(jdbc);
    }

    @AfterEach
    void resetStub() {
        investigator.reset();
    }

    @Test
    void plantedInstructionsTheToolsFlaggedAreRecordedOnTheCase() {
        String caseId = newCase(Severity.HIGH);
        List<String> flagged = List.of("getCustomerProfile: addresses automated systems");
        investigator.respondWith(c -> {
            InvestigationResult r = StubCaseInvestigator.defaultResult(c);
            return new InvestigationResult(r.report(), r.toolCalls(), r.repairedIssues(), r.model(), r.inputTokens(),
                    r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs(), List.of(), null, null, flagged);
        });

        worker.runOnce();

        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "UNTRUSTED_INSTRUCTIONS", "ANALYSED");
        assertThat(cases.events(tenant, caseId).get(1).detail().toString()).contains("addresses automated systems");
    }

    @Test
    void analysedCaseWaitsForTheAnalystWithItsAnalysis() {
        String caseId = newCase(Severity.HIGH);

        worker.runOnce();

        FraudCase c = cases.find(tenant, caseId).orElseThrow();
        assertThat(c.stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(cases.analyses(tenant, caseId)).singleElement().satisfies(a -> {
            assertThat(a.run()).isEqualTo(1);
            assertThat(a.model()).isEqualTo("stub-model");
        });
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "ANALYSED");
        assertThat(outboxTypes(jdbc, caseId)).containsExactly("case.ready_for_decision");
    }

    @Test
    void failedAnalysisIsRetriedThenTheCaseFails() {
        String caseId = newCase(Severity.MEDIUM);
        investigator.respondWith(c -> {
            throw new IllegalStateException("LLM down");
        });

        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        worker.runOnce();
        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.FAILED);
        assertThat(eventTypes(cases, tenant, caseId)).endsWith("ANALYSIS_ATTEMPT_FAILED", "ANALYSIS_ATTEMPT_FAILED", "FAILED");
        assertThat(outboxTypes(jdbc, caseId)).containsExactly("case.failed");
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void highPriorityIsClaimedFirst() {
        String medium = newCase(Severity.MEDIUM);
        String high = newCase(Severity.HIGH);

        List<CaseClaim> claims = cases.claim(1, Duration.ofMinutes(5), 3);

        assertThat(claims).singleElement().extracting(c -> c.fraudCase().caseId()).isEqualTo(high);
        assertThat(cases.find(tenant, medium).orElseThrow().attempts()).isZero();
    }

    @Test
    void workerThatLostItsClaimDoesNotWriteAResult() {
        String caseId = newCase(Severity.HIGH);
        investigator.respondWith(c -> {
            // Another worker re-claims while this one is still thinking.
            jdbc.sql("update fraud_case set claimed_at = clock_timestamp() where case_id = cast(:id as uuid)")
                    .param("id", c.caseId()).update();
            return StubCaseInvestigator.defaultResult(c);
        });

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        assertThat(cases.analyses(tenant, caseId)).isEmpty();
    }

    @Test
    void abandonedClaimIsTakenBackAfterTimeout() {
        String caseId = newCase(Severity.HIGH);
        cases.claim(1, Duration.ofMinutes(5), 3);
        jdbc.sql("update fraud_case set claimed_at = now() - interval '10 minutes' where case_id = cast(:id as uuid)")
                .param("id", caseId).update();

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    @Test
    void newAlertDuringAnalysisSupersedesTheRunAndTheNextRunSeesBothAlerts() {
        String caseId = newCase(Severity.MEDIUM);
        investigator.respondWith(c -> {
            if (c.run() == 1) {
                // Evidence arrives while the agent is still working on run 1.
                intake.accept(alertOn(tenant, c.accountId(), Severity.HIGH));
            }
            return StubCaseInvestigator.defaultResult(c);
        });

        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).as("superseded").isEqualTo(CaseStage.ANALYSIS);
        worker.runOnce();

        assertThat(investigator.lastContext().alerts()).hasSize(2);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(cases.analyses(tenant, caseId)).extracting(CaseRepository.CaseAnalysis::run).containsExactly(2, 1);
        assertThat(eventTypes(cases, tenant, caseId)).contains("ANALYSIS_SUPERSEDED");
    }

    @Test
    void hourlyBudgetStopsClaimingWhenSpent() {
        newCase(Severity.HIGH);
        newCase(Severity.HIGH);
        int alreadyUsed = cases.startedInLastHour();
        CaseWorker capped = new CaseWorker(cases, alerts, analysis, props(alreadyUsed + 1, Duration.ofSeconds(2)), systems, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));

        assertThat(capped.runOnce()).isEqualTo(1);
        assertThat(capped.runOnce()).isZero();
    }

    private String newCase(Severity severity) {
        return intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), severity)).orElseThrow();
    }

    static InvestigationProperties props(int maxPerHour, Duration lockTimeout) {
        return new InvestigationProperties(true, 5, Duration.ofMinutes(5), 3, maxPerHour, lockTimeout,
                new InvestigationProperties.Tools(90, 100), new InvestigationProperties.Report(60, 40, 1, true));
    }
}
