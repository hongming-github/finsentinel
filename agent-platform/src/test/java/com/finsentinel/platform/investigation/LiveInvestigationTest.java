package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.transaction.TransactionRepository;
import com.finsentinel.platform.knowledge.KnowledgeSync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the real model. Off by default because it costs money and is not deterministic.
 * Run: ./mvnw -pl agent-platform test -Dtest=LiveInvestigationTest -Dlive=true
 *
 * <p>A seed of the phase 7 evaluation suite: two cases with a clear expected direction.
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
@EnabledIfSystemProperty(named = "live", matches = "true")
class LiveInvestigationTest {

    @Autowired
    LlmCaseInvestigator investigator;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    CaseIntake intake;
    @Autowired
    JsonMapper json;
    @Autowired
    KnowledgeSync knowledge;

    private final Random random = new Random(7);

    @BeforeEach
    void indexRegulations() {
        knowledge.sync(); // idempotent; embeddings come from the on-disk cache after the first run
    }

    @Test
    void impossibleTravelIsNotCalledLegitimate() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        List<Transaction> history = normalHistory(tenant, "acc-geo", now);
        Transaction home = txn(tenant, "acc-geo", "84.20", Channel.CARD_PRESENT, "SG", "m-2", now.minus(Duration.ofMinutes(40)));
        Transaction abroad = txn(tenant, "acc-geo", "1290.00", Channel.CARD_PRESENT, "GB", "m-london-9", now);
        history.add(home);
        history.add(abroad);
        transactions.insertAll(history);

        InvestigationResult result = run(alert(tenant, "acc-geo", AlertRule.GEO_JUMP, Severity.HIGH,
                List.of(home.txnId(), abroad.txnId()), now));

        assertThat(result.report().disposition()).isNotEqualTo(Disposition.LIKELY_LEGITIMATE);
        assertThat(result.report().riskScore()).isGreaterThanOrEqualTo(60);
        assertGrounded(result);
    }

    @Test
    void honestOutlierAtHomeIsNotCalledFraud() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        List<Transaction> history = normalHistory(tenant, "acc-big", now.minus(Duration.ofHours(3)));
        Transaction big = txn(tenant, "acc-big", "1000.73", Channel.CARD_PRESENT, "SG", "m-3", now.minus(Duration.ofHours(2)));
        history.add(big);
        for (int i = 1; i <= 3; i++) {
            history.add(txn(tenant, "acc-big", amount(), Channel.CARD_PRESENT, "SG", "m-" + i, now.minus(Duration.ofMinutes(30L * i))));
        }
        transactions.insertAll(history);

        InvestigationResult result = run(alert(tenant, "acc-big", AlertRule.LARGE_AMOUNT, Severity.MEDIUM,
                List.of(big.txnId()), big.eventTime()));

        assertThat(result.report().disposition()).isNotEqualTo(Disposition.LIKELY_FRAUD);
        assertGrounded(result);
    }

    @Test
    void largeTransferToNewPayeeIsNotWavedThroughByNormalSpendingAfterwards() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        List<Transaction> history = normalHistory(tenant, "acc-app", now.minus(Duration.ofHours(3)));
        Transaction transfer = txn(tenant, "acc-app", "8745.00", Channel.TRANSFER, "SG", "payee-new-4411",
                now.minus(Duration.ofHours(2)));
        history.add(transfer);
        for (int i = 1; i <= 3; i++) {
            history.add(txn(tenant, "acc-app", amount(), Channel.CARD_PRESENT, "SG", "m-" + i, now.minus(Duration.ofMinutes(30L * i))));
        }
        transactions.insertAll(history);

        InvestigationResult result = run(alert(tenant, "acc-app", AlertRule.LARGE_AMOUNT, Severity.MEDIUM,
                List.of(transfer.txnId()), transfer.eventTime()));

        assertThat(result.report().disposition()).isNotEqualTo(Disposition.LIKELY_LEGITIMATE);
        assertThat(result.toolCalls()).extracting(ToolCall::tool).contains("getCounterpartyHistory");
        // A new-payee scam is exactly where cooling-off and scam-loss rules matter; validation already checked
        // that every cited chunk came from a search.
        assertThat(result.report().regulatoryNotes()).as("regulatory notes").isNotEmpty();
        assertGrounded(result);
    }

    private InvestigationResult run(Alert alert) {
        String caseId = intake.accept(alert).orElseThrow();
        InvestigationResult result = investigator.investigate(new CaseContext(caseId, alert.tenantId(), alert.accountId(),
                alert.customerId(), List.of(alert), 1, null));
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return result;
    }

    /** investigate() only returns reports that passed validation, which includes the evidence check. */
    private static void assertGrounded(InvestigationResult result) {
        assertThat(result.toolCalls()).as("agent used tools").isNotEmpty();
    }

    /** Twenty days of ordinary Singapore spending around 100 SGD at a handful of regular merchants. */
    private List<Transaction> normalHistory(String tenant, String account, Instant before) {
        List<Transaction> out = new ArrayList<>();
        for (int day = 20; day >= 1; day--) {
            Channel channel = day % 3 == 0 ? Channel.CARD_NOT_PRESENT : Channel.CARD_PRESENT;
            out.add(txn(tenant, account, amount(), channel, "SG", "m-" + (day % 5), before.minus(Duration.ofDays(day))));
        }
        return out;
    }

    private String amount() {
        return "%.2f".formatted(100 * Math.exp(0.3 * random.nextGaussian()));
    }
}
