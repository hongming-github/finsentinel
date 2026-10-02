package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.context;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@PlatformTest
class InvestigationToolsTest {

    @Autowired
    TransactionRepository transactions;
    @Autowired
    InvestigationToolsFactory toolsFactory;
    @Autowired
    CaseIntake intake;
    @Autowired
    JdbcClient jdbc;

    @Test
    void toolsOnlySeeTheAlertsOwnTenantAndAccount() {
        String tenant = uniqueTenant();
        String otherTenant = uniqueTenant();
        Instant now = Instant.now();
        Transaction mine = txn(tenant, "acc-1", "10", Channel.CARD_PRESENT, "SG", now.minusSeconds(60));
        Transaction myHistory = txn(tenant, "acc-1", "15", Channel.CARD_PRESENT, "SG", now.minusSeconds(86_400));
        Transaction sameAccountIdOtherBank = txn(otherTenant, "acc-1", "20", Channel.CARD_PRESENT, "SG", now.minusSeconds(60));
        Transaction otherAccount = txn(tenant, "acc-2", "30", Channel.CARD_PRESENT, "SG", now.minusSeconds(60));
        transactions.insertAll(List.of(mine, myHistory, sameAccountIdOtherBank, otherAccount));

        InvestigationTools tools = toolsFor(alert(tenant, "acc-1", AlertRule.VELOCITY, Severity.MEDIUM,
                List.of(mine.txnId(), sameAccountIdOtherBank.txnId(), otherAccount.txnId()), now));

        assertThat(tools.getAlertTransactions()).extracting(InvestigationTools.TxnView::id).containsExactly(mine.txnId());
        assertThat(tools.getRecentTransactions(7, 100)).extracting(InvestigationTools.TxnView::id)
                .containsExactly(mine.txnId(), myHistory.txnId());
        // Baseline: own history only, without the alert's transaction.
        assertThat(tools.getAccountProfile(7).amounts().transactionCount()).isEqualTo(1);
    }

    @Test
    void transactionViewDropsFieldsThatRepeatOnEveryRow() {
        Transaction t = txn("bank", "acc-1", "84.2", Channel.CARD_PRESENT, "SG", "m-7", Instant.parse("2026-09-30T13:11:38.114530Z"));

        InvestigationTools.TxnView view = InvestigationTools.TxnView.of(t);

        assertThat(view).isEqualTo(new InvestigationTools.TxnView(t.txnId(), Instant.parse("2026-09-30T13:11:38Z"),
                "84.20 SGD", "CARD_PRESENT", "SG", "m-7", "5411"));
    }

    @Test
    void outOfRangeArgumentsAreClamped() {
        String tenant = uniqueTenant();
        InvestigationTools tools = toolsFor(alert(tenant, "acc-1", AlertRule.VELOCITY, Severity.LOW, List.of(), Instant.now()));

        tools.getRecentTransactions(10_000, 10_000);

        assertThat(tools.calls()).singleElement()
                .extracting(ToolCall::arguments).isEqualTo("days=90, limit=100");
    }

    @Test
    void priorAlertsComeFromOtherCasesWithTheirOutcome() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        Alert earlier = alert(tenant, "acc-1", AlertRule.LARGE_AMOUNT, Severity.MEDIUM, List.of("x"), now.minusSeconds(86_400));
        String earlierCase = intake.accept(earlier).orElseThrow();
        jdbc.sql("update fraud_case set stage = 'CLOSED', decision = 'FALSE_POSITIVE' where case_id = cast(:id as uuid)")
                .param("id", earlierCase).update();
        Alert current = alert(tenant, "acc-1", AlertRule.GEO_JUMP, Severity.HIGH, List.of("y"), now);
        String currentCase = intake.accept(current).orElseThrow();

        List<InvestigationTools.PriorAlert> prior = toolsFactory.forCase(new CaseContext(currentCase, tenant, "acc-1",
                current.customerId(), List.of(current), 1, null)).getPriorAlerts(30);

        assertThat(prior).singleElement().satisfies(p -> {
            assertThat(p.rule()).isEqualTo("LARGE_AMOUNT");
            assertThat(p.caseDecision()).isEqualTo("FALSE_POSITIVE");
        });
    }

    @Test
    void counterpartyHistoryDistinguishesNewFromEstablishedPayees() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        Transaction earlierRent = txn(tenant, "acc-1", "1800", Channel.TRANSFER, "SG", "payee-landlord", now.minusSeconds(30 * 86_400L));
        Transaction rent = txn(tenant, "acc-1", "1800", Channel.TRANSFER, "SG", "payee-landlord", now.minusSeconds(60));
        Transaction scam = txn(tenant, "acc-1", "9000", Channel.TRANSFER, "SG", "payee-unknown", now);
        transactions.insertAll(List.of(earlierRent, rent, scam));

        List<InvestigationTools.CounterpartyCheck> checks = toolsFor(alert(tenant, "acc-1", AlertRule.LARGE_AMOUNT,
                Severity.MEDIUM, List.of(rent.txnId(), scam.txnId()), now)).getCounterpartyHistory();

        assertThat(checks).extracting(InvestigationTools.CounterpartyCheck::counterparty,
                        InvestigationTools.CounterpartyCheck::priorCount)
                .containsExactly(tuple("payee-landlord", 1L), tuple("payee-unknown", 0L));
    }

    @Test
    void regulationSearchesAreCappedPerCase() {
        InvestigationTools tools = toolsFor(alert(uniqueTenant(), "acc-1", AlertRule.VELOCITY, Severity.LOW, List.of(), Instant.now()));

        for (int i = 0; i < 3; i++) {
            assertThat(tools.searchRegulations("cooling off period").note()).doesNotContain("limit");
        }

        assertThat(tools.searchRegulations("one more").note()).contains("limit");
    }

    @Test
    void citedIdsTheToolsNeverReturnedAreFlagged() {
        CaseReport report = new CaseReport(Disposition.LIKELY_FRAUD, 90, "s", List.of(
                new CaseReport.Finding("seen", List.of("a", "b")),
                new CaseReport.Finding("invented", List.of("b", "zzz"))), List.of(), List.of());

        assertThat(CaseReportValidator.ungrounded(report, Set.of("a", "b"))).containsExactly("zzz");
    }

    private InvestigationTools toolsFor(Alert alert) {
        return toolsFactory.forCase(context(alert));
    }
}
