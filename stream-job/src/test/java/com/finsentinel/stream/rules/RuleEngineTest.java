package com.finsentinel.stream.rules;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-01T02:00:00Z");
    private final RuleEngine engine = new RuleEngine(RuleConfig.defaults());
    private final AccountRiskState state = new AccountRiskState();
    private int seq;

    @Test
    void velocityFiresOnceWhenThresholdReachedInsideWindow() {
        List<Alert> alerts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            alerts.addAll(run(txn("20", Channel.CARD_NOT_PRESENT, "SG", T0.plusSeconds(i * 3L))));
        }

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(AlertRule.VELOCITY);
            assertThat(a.txnIds()).hasSize(8);
            assertThat(a.severity()).isEqualTo(Severity.MEDIUM);
        });
    }

    @Test
    void velocityIgnoresTransactionsSpreadBeyondWindow() {
        List<Alert> alerts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            alerts.addAll(run(txn("20", Channel.CARD_NOT_PRESENT, "SG", T0.plusSeconds(i * 10L))));
        }

        assertThat(alerts).noneMatch(a -> a.rule() == AlertRule.VELOCITY);
    }

    @Test
    void velocityCountsOutOfOrderArrivals() {
        List<Alert> alerts = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            alerts.addAll(run(txn("20", Channel.CARD_NOT_PRESENT, "SG", T0.plusSeconds(i))));
        }

        assertThat(alerts).extracting(Alert::rule).containsExactly(AlertRule.VELOCITY);
    }

    @Test
    void largeAmountNeedsHistoryBeforeItJudgesOutliers() {
        assertThat(run(txn("5000", Channel.TRANSFER, "SG", T0))).isEmpty();
    }

    @Test
    void largeAmountFlagsOutlierAgainstAccountBaseline() {
        buildBaseline();

        List<Alert> alerts = run(txn("4000", Channel.TRANSFER, "SG", T0.plusSeconds(3600)));

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(AlertRule.LARGE_AMOUNT);
            assertThat(a.severity()).isEqualTo(Severity.MEDIUM);
        });
    }

    @Test
    void flaggedAmountDoesNotShiftTheBaseline() {
        buildBaseline();
        double meanBefore = state.amountMean;

        run(txn("4000", Channel.TRANSFER, "SG", T0.plusSeconds(3600)));

        assertThat(state.amountMean).isEqualTo(meanBefore);
    }

    @Test
    void hardLimitAlwaysFiresAsHigh() {
        assertThat(run(txn("50000", Channel.TRANSFER, "SG", T0)))
                .singleElement()
                .extracting(Alert::severity).isEqualTo(Severity.HIGH);
    }

    @Test
    void geoJumpFiresForCardPresentInTwoCountriesWithinWindow() {
        Transaction home = txn("30", Channel.CARD_PRESENT, "SG", T0);
        run(home);

        List<Alert> alerts = run(txn("30", Channel.CARD_PRESENT, "GB", T0.plusSeconds(1800)));

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(AlertRule.GEO_JUMP);
            assertThat(a.txnIds()).first().isEqualTo(home.txnId());
        });
    }

    @Test
    void geoJumpIgnoresOnlinePaymentsAndSlowTravel() {
        run(txn("30", Channel.CARD_PRESENT, "SG", T0));

        assertThat(run(txn("30", Channel.CARD_NOT_PRESENT, "US", T0.plusSeconds(60)))).isEmpty();
        assertThat(run(txn("30", Channel.CARD_PRESENT, "MY", T0.plusSeconds(3 * 3600)))).isEmpty();
    }

    @Test
    void alertIdIsStableForTheSameTrigger() {
        Transaction big = txn("60000", Channel.TRANSFER, "SG", T0);

        String first = engine.evaluate(new AccountRiskState(), big, Instant.now()).getFirst().alertId();
        String second = engine.evaluate(new AccountRiskState(), big, Instant.now()).getFirst().alertId();

        assertThat(first).isEqualTo(second);
    }

    private void buildBaseline() {
        String[] amounts = {"40", "55", "38", "61", "47", "52", "44", "58", "49", "50", "46", "53"};
        for (int i = 0; i < amounts.length; i++) {
            run(txn(amounts[i], Channel.TRANSFER, "SG", T0.plusSeconds(i * 120L)));
        }
    }

    private List<Alert> run(Transaction txn) {
        return engine.evaluate(state, txn, Instant.now());
    }

    private Transaction txn(String amount, Channel channel, String country, Instant at) {
        return new Transaction("t" + (++seq), "harbour", "acc-1", "cus-1", new BigDecimal(amount), "SGD",
                "m-1", "5411", channel, country, at);
    }
}
