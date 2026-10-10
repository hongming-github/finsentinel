package com.finsentinel.platform.eval;

import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.cases.CaseClaim;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.security.Tenancy;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Runs one golden case through an analysis graph and grades it. The graph is the caller's: the real agents for an
 * evaluation, the stubs for the free dry run, so both exercise the same setup, claiming and grading.
 */
final class EvalHarness {

    static final String ANALYSIS_FAILED = "analysis failed: ";

    private final ScenarioBuilder scenarios;
    private final CaseGrader grader;
    private final CaseIntake intake;
    private final CaseRepository cases;
    private final JdbcClient jdbc;
    private final FakeExternalSystems systems;
    private final EvalJudge judge;

    /** @param judge null to skip judging */
    EvalHarness(ScenarioBuilder scenarios, CaseGrader grader, CaseIntake intake, CaseRepository cases, JdbcClient jdbc,
                FakeExternalSystems systems, EvalJudge judge) {
        this.scenarios = scenarios;
        this.grader = grader;
        this.intake = intake;
        this.cases = cases;
        this.jdbc = jdbc;
        this.systems = systems;
        this.judge = judge;
    }

    RunRecord run(GoldenCase c, CaseAnalysisGraph graph, AnalysisMode mode, int repeat) {
        systems.reset();
        // The run's case must be the one claimed: close whatever earlier runs left open (system scope, like a DBA).
        Tenancy.callAsSystem(() -> jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage <> 'CLOSED'")
                .update());
        String tenant = TestData.uniqueTenant();
        ScenarioBuilder.Scenario scenario = scenarios.build(c, tenant, Instant.now());
        String caseId = intake.accept(scenario.alert()).orElseThrow();
        CaseClaim claim = Tenancy.callAsSystem(() -> cases.claimWithinBudget(1, Duration.ofMinutes(10), 3, 0,
                Duration.ofSeconds(2))).claims().getFirst();
        if (!claim.fraudCase().caseId().equals(caseId)) {
            throw new IllegalStateException("claimed " + claim.fraudCase().caseId() + " instead of " + caseId);
        }
        CaseContext context = new CaseContext(caseId, tenant, scenario.alert().accountId(),
                scenario.alert().customerId(), List.of(scenario.alert()), 1, null, c.setup().connectedSystems());

        long started = System.nanoTime();
        InvestigationResult result;
        try {
            // As the worker does: the analysis runs in the case's tenant scope.
            result = Tenancy.call(tenant, () -> graph.analyse(context, claim.claimedAt(), mode)).investigation();
        } catch (RuntimeException e) {
            return new RunRecord(c.id(), c.category(), c.critical(), mode.name(), repeat, RunRecord.FAILED, null,
                    null, List.of(ANALYSIS_FAILED + e.getMessage()), c.expect().requiredTools().size(), 0, null,
                    null, null, null, (System.nanoTime() - started) / 1_000_000);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        CaseGrader.Grade grade = grader.grade(c, result);
        BigDecimal cost = result.costUsd();
        Double judgeScore = null;
        List<String> unsupported = List.of();
        if (judge != null) {
            EvalJudge.Judged judged = judge.judge(c, result);
            judgeScore = judged.verdict().mean();
            unsupported = judged.verdict().unsupportedClaims();
            cost = cost.add(judged.usage().costUsd());
        }
        return new RunRecord(c.id(), c.category(), c.critical(), mode.name(), repeat,
                grade.passed() ? RunRecord.PASSED : RunRecord.FAILED, result.report().disposition().name(),
                result.report().riskScore(), grade.failed(), grade.requiredTools(), grade.requiredToolsCalled(),
                grade.regulationsRelevant(), judgeScore, unsupported, cost, latencyMs);
    }
}
