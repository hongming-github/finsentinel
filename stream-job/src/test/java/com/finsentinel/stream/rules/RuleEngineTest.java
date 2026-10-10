package com.finsentinel.stream.rules;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    void aFreshBatchBaselineStandsInForMissingStreamingHistory() {
        // T0 is 2026-09-01; the baseline covers days before it.
        engine.applyBaseline(state, baseline("2026-09-01", 40, 50.0, 6.0));

        List<Alert> alerts = run(txn("4000", Channel.TRANSFER, "SG", T0));

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(AlertRule.LARGE_AMOUNT);
            assertThat(a.reason()).contains("median of 50.00 over 40 transactions (batch baseline as of 2026-09-01)");
        });
        assertThat(run(txn("60", Channel.TRANSFER, "SG", T0.plusSeconds(60)))).isEmpty();
    }

    @Test
    void aStaleOrThinBaselineIsNotTrusted() {
        engine.applyBaseline(state, baseline("2026-08-25", 40, 50.0, 6.0));
        assertThat(run(txn("4000", Channel.TRANSFER, "SG", T0))).as("7 days old, max age 3d").isEmpty();

        AccountRiskState thin = new AccountRiskState();
        engine.applyBaseline(thin, baseline("2026-09-01", 5, 50.0, 6.0));
        assertThat(engine.evaluate(thin, txn("4000", Channel.TRANSFER, "SG", T0), Instant.now()))
                .as("5 transactions, min history 10").isEmpty();
    }

    @Test
    void streamingHistoryWinsOnceThereIsEnoughOfIt() {
        buildBaseline();
        // A baseline that would call 4000 normal: the account's own recent history must decide.
        engine.applyBaseline(state, baseline("2026-09-01", 400, 3000.0, 1000.0));

        assertThat(run(txn("4000", Channel.TRANSFER, "SG", T0.plusSeconds(3600))))
                .singleElement().satisfies(a -> assertThat(a.reason()).contains("mean of"));
    }

    @Test
    void aLateBaselineForAnEarlierDayDoesNotReplaceANewerOne() {
        engine.applyBaseline(state, baseline("2026-09-01", 40, 50.0, 6.0));
        engine.applyBaseline(state, baseline("2026-08-30", 40, 5000.0, 600.0));

        assertThat(state.baselineMedian).isEqualTo(50.0);
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

    private static AccountBaseline baseline(String asOf, long sampleSize, double median, double robustStdDev) {
        return new AccountBaseline("harbour", "acc-1", LocalDate.parse(asOf).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant(), 90, sampleSize, median, robustStdDev,
                median, median * 2, median * 3, Instant.now());
    }

    private List<Alert> run(Transaction txn) {
        return engine.evaluate(state, txn, Instant.now());
    }

    private Transaction txn(String amount, Channel channel, String country, Instant at) {
        return new Transaction("t" + (++seq), "harbour", "acc-1", "cus-1", new BigDecimal(amount), "SGD",
                "m-1", "5411", channel, country, at);
    }
}
