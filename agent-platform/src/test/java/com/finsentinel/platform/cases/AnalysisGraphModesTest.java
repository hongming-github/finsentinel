package com.finsentinel.platform.cases;

import com.finsentinel.platform.systems.BankSystem;
import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.StubCaseReviewer;
import com.finsentinel.platform.StubReportWriter;
import com.finsentinel.platform.StubSpecialistAgents;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.Specialist;
import com.finsentinel.platform.investigation.SpecialistReport;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both analysis shapes on the real graph runtime and checkpoint saver, with every agent stubbed. */
@PlatformTest
class AnalysisGraphModesTest {

    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    CaseRepository cases;
    @Autowired
    CaseIntake intake;
    @Autowired
    AlertRepository alerts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    StubSpecialistAgents specialists;
    @Autowired
    StubReportWriter writer;
    @Autowired
    StubCaseReviewer reviewer;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();

    @BeforeEach
    void setUp() {
        CaseTestSupport.parkOtherCases(jdbc);
        resetStubs();
    }

    @AfterEach
    void resetStubs() {
        investigator.reset();
        specialists.reset();
        writer.reset();
        reviewer.reset();
    }

    @Test
    void specialistsRunInParallelAndTheWriterWorksFromTheirFindings() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        CountDownLatch bothStarted = new CountDownLatch(2);
        specialists.respondWith((specialist, c) -> {
            bothStarted.countDown();
            // Sequential execution would wait here forever: the other specialist could never start.
            await(bothStarted);
            return StubSpecialistAgents.defaultReport(specialist, c);
        });

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI);

        assertThat(outcome.mode()).isEqualTo(AnalysisMode.MULTI);
        assertThat(specialists.calls(Specialist.TRANSACTIONS)).isEqualTo(1);
        assertThat(specialists.calls(Specialist.CUSTOMER)).isEqualTo(1);
        assertThat(specialists.calls(Specialist.REGULATIONS)).as("no transfer: planned out").isZero();
        assertThat(writer.lastPlan().involvesTransfer()).isFalse();
        assertThat(writer.lastReports()).extracting(SpecialistReport::specialist)
                .containsExactlyInAnyOrder(Specialist.TRANSACTIONS, Specialist.CUSTOMER);
        assertThat(reviewer.lastEvidence()).hasSize(2);
        assertThat(investigator.calls()).as("the single investigator is not used").isZero();
        // Two specialist calls, one writer call, an approval that cost nothing.
        assertThat(outcome.investigation().llmCalls()).isEqualTo(3);
        assertThat(outcome.investigation().inputTokens()).isEqualTo(100 + 100 + 10);
    }

    @Test
    void transferBringsInTheRegulationsSpecialist() {
        CaseClaim claim = claimNewCase(Channel.TRANSFER);

        analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI);

        assertThat(specialists.calls(Specialist.REGULATIONS)).isEqualTo(1);
        assertThat(writer.lastPlan().involvesTransfer()).isTrue();
        assertThat(writer.lastReports()).hasSize(3);
    }

    @Test
    void reviewerSendsTheReportBackAtMostOnceThenItGoesToTheAnalystWithTheObjection() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        reviewer.respondWith((c, i) -> StubCaseReviewer.sendBack("cite the foreign transaction"));

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI);

        assertThat(writer.calls()).as("first draft plus one revision").isEqualTo(2);
        assertThat(writer.lastRevision().issues()).containsExactly("cite the foreign transaction");
        assertThat(reviewer.calls()).isEqualTo(2);
        assertThat(specialists.totalCalls()).as("revisions do not repeat the specialists").isEqualTo(2);
        assertThat(outcome.review().approved()).isFalse();
        // Two specialists, two drafts, two reviews.
        assertThat(outcome.investigation().llmCalls()).isEqualTo(6);

        cases.complete(claim, outcome.mode(), outcome.investigation(), outcome.review());
        assertThat(cases.analyses(tenant, claim.fraudCase().caseId())).singleElement().satisfies(a -> {
            assertThat(a.mode()).isEqualTo("MULTI");
            assertThat(a.review().approved()).isFalse();
            assertThat(a.review().issues()).containsExactly("cite the foreign transaction");
        });
    }

    @Test
    void reportFixedAfterOneRevisionIsApproved() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        AtomicInteger reviews = new AtomicInteger();
        reviewer.respondWith((c, i) -> reviews.incrementAndGet() == 1
                ? StubCaseReviewer.sendBack("address the second alert") : StubCaseReviewer.approve(c, i));

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.SINGLE);

        assertThat(outcome.review().approved()).isTrue();
        assertThat(investigator.calls()).isEqualTo(2);
        assertThat(investigator.lastRevision().issues()).containsExactly("address the second alert");
        assertThat(investigator.lastRevision().previousReport().summary()).isEqualTo("stub report");
    }

    @Test
    void fraudReportsGoToTheAnalystUnreviewedAndSaySo() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        investigator.respondWith(c -> fraud(StubCaseInvestigator.defaultResult(c)));

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.SINGLE);

        assertThat(reviewer.calls()).isZero();
        assertThat(outcome.review().reviewed()).isFalse();
        assertThat(outcome.review().skippedBecause()).contains("LIKELY_FRAUD");
        cases.complete(claim, outcome.mode(), outcome.investigation(), outcome.review());
        assertThat(cases.analyses(tenant, claim.fraudCase().caseId())).singleElement()
                .satisfies(a -> assertThat(a.review().reviewed()).as("stored as unreviewed").isFalse());
    }

    @Test
    void clearingReportIsStillReviewed() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        investigator.respondWith(c -> {
            InvestigationResult r = StubCaseInvestigator.defaultResult(c);
            CaseReport legit = new CaseReport(Disposition.LIKELY_LEGITIMATE, 10, "clear", r.report().findings(),
                    List.of("close"), List.of());
            return new InvestigationResult(legit, r.toolCalls(), r.repairedIssues(), r.model(), r.inputTokens(),
                    r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs());
        });

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.SINGLE);

        assertThat(reviewer.calls()).isEqualTo(1);
        assertThat(outcome.review().reviewed()).isTrue();
    }

    private static InvestigationResult fraud(InvestigationResult r) {
        CaseReport fraud = new CaseReport(Disposition.LIKELY_FRAUD, 90, "fraud", r.report().findings(),
                List.of("block card"), List.of());
        return new InvestigationResult(fraud, r.toolCalls(), r.repairedIssues(), r.model(), r.inputTokens(),
                r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs());
    }

    @Test
    void crashInTheWriterResumesWithoutRunningTheSpecialistsAgain() {
        CaseClaim claim = claimNewCase(Channel.TRANSFER);
        AtomicInteger drafts = new AtomicInteger();
        writer.respondWith(c -> {
            if (drafts.incrementAndGet() == 1) {
                throw new IllegalStateException("process killed while writing");
            }
            return StubCaseInvestigator.defaultResult(c);
        });

        assertThatThrownBy(() -> analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI))
                .hasMessageContaining("process killed while writing");
        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI);

        assertThat(specialists.totalCalls()).as("three specialists, once").isEqualTo(3);
        assertThat(writer.calls()).isEqualTo(2);
        assertThat(outcome.resumedAfter()).isNotNull();
        assertThat(outcome.investigation().llmCalls()).as("specialists' cost survives the restart").isEqualTo(3 + 1);
    }

    @Test
    void resumedRunKeepsTheModeItStartedIn() {
        CaseClaim claim = claimNewCase(Channel.CARD_PRESENT);
        writer.respondWith(c -> {
            throw new IllegalStateException("crash");
        });
        assertThatThrownBy(() -> analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.MULTI));
        writer.reset();

        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt(), AnalysisMode.SINGLE);

        assertThat(outcome.mode()).isEqualTo(AnalysisMode.MULTI);
        assertThat(investigator.calls()).isZero();
        assertThat(writer.calls()).isEqualTo(1);
    }

    private CaseClaim claimNewCase(Channel channel) {
        Instant now = Instant.now();
        String account = "acc-" + UUID.randomUUID();
        Transaction t = txn(tenant, account, "900.00", channel, "SG", "payee-new-1", now);
        transactions.insertAll(List.of(t));
        String caseId = intake.accept(alert(tenant, account, AlertRule.LARGE_AMOUNT, Severity.HIGH, List.of(t.txnId()), now))
                .orElseThrow();
        CaseClaim claim = cases.claim(1, Duration.ofMinutes(5), 3).getFirst();
        assertThat(claim.fraudCase().caseId()).isEqualTo(caseId);
        return claim;
    }

    private CaseContext contextOf(CaseClaim claim) {
        FraudCase c = claim.fraudCase();
        List<Alert> caseAlerts = alerts.findByCase(c.tenantId(), c.caseId()).stream().map(StoredAlert::alert).toList();
        return new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts, c.analysisRun(),
                c.analystNote(), BankSystem.ALL);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("specialists did not run in parallel");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
