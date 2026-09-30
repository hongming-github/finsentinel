package com.finsentinel.stream.rules;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure rule logic with no Flink dependency, so it can be unit-tested directly.
 * Each call checks the transaction against the account's prior state, then folds it into that state.
 */
public class RuleEngine implements Serializable {

    private final RuleConfig config;

    public RuleEngine(RuleConfig config) {
        this.config = config;
    }

    public List<Alert> evaluate(AccountRiskState state, Transaction txn, Instant now) {
        List<Alert> alerts = new ArrayList<>(1);
        checkVelocity(state, txn, now, alerts);
        checkLargeAmount(state, txn, now, alerts);
        checkGeoJump(state, txn, now, alerts);
        return alerts;
    }

    private void checkVelocity(AccountRiskState state, Transaction txn, Instant now, List<Alert> out) {
        long t = txn.eventTime().toEpochMilli();
        long windowMs = config.velocityWindow().toMillis();
        insertSorted(state, t, txn.txnId());
        pruneBefore(state, t - windowMs);

        int count = state.recentTimesMs.length;
        boolean cooledDown = state.lastVelocityAlertMs == Long.MIN_VALUE
                || t - state.lastVelocityAlertMs >= windowMs;
        if (count >= config.velocityThreshold() && cooledDown) {
            state.lastVelocityAlertMs = t;
            Severity severity = count >= 2 * config.velocityThreshold() ? Severity.HIGH : Severity.MEDIUM;
            String reason = "%d transactions within %s".formatted(count, human(config.velocityWindow()));
            out.add(alert(txn, AlertRule.VELOCITY, severity, reason, List.of(state.recentTxnIds), now));
        }
    }

    private void checkLargeAmount(AccountRiskState state, Transaction txn, Instant now, List<Alert> out) {
        double amount = txn.amount().doubleValue();
        boolean overHardLimit = txn.amount().compareTo(config.hardLimit()) >= 0;
        boolean outlier = state.amountCount >= config.largeAmountMinHistory()
                && amount > state.amountMean + config.largeAmountStdDevs() * state.amountStdDev()
                && amount > state.amountMean * config.largeAmountMeanMultiple();

        if (overHardLimit || outlier) {
            String reason = overHardLimit
                    ? "amount %s %s at or above hard limit %s".formatted(txn.amount(), txn.currency(), config.hardLimit())
                    : "amount %s %s is %.1fx the account mean of %.2f over %d transactions".formatted(
                            txn.amount(), txn.currency(), amount / state.amountMean, state.amountMean, state.amountCount);
            out.add(alert(txn, AlertRule.LARGE_AMOUNT, overHardLimit ? Severity.HIGH : Severity.MEDIUM,
                    reason, List.of(txn.txnId()), now));
        } else {
            // Flagged amounts stay out of the baseline so one fraud does not make the next look normal.
            state.recordAmount(amount);
        }
    }

    private void checkGeoJump(AccountRiskState state, Transaction txn, Instant now, List<Alert> out) {
        if (txn.channel() != Channel.CARD_PRESENT) {
            return;
        }
        long t = txn.eventTime().toEpochMilli();
        if (state.lastCardPresentCountry != null
                && !state.lastCardPresentCountry.equals(txn.country())
                && Math.abs(t - state.lastCardPresentTimeMs) <= config.geoJumpWindow().toMillis()) {
            Duration gap = Duration.ofMillis(Math.abs(t - state.lastCardPresentTimeMs));
            String reason = "card present in %s then %s within %s".formatted(
                    state.lastCardPresentCountry, txn.country(), human(gap));
            out.add(alert(txn, AlertRule.GEO_JUMP, Severity.HIGH, reason,
                    List.of(state.lastCardPresentTxnId, txn.txnId()), now));
        }
        if (t >= state.lastCardPresentTimeMs) {
            state.lastCardPresentCountry = txn.country();
            state.lastCardPresentTimeMs = t;
            state.lastCardPresentTxnId = txn.txnId();
        }
    }

    private static Alert alert(Transaction txn, AlertRule rule, Severity severity, String reason,
                               List<String> txnIds, Instant now) {
        return new Alert(
                Alert.idFor(txn.tenantId(), txn.accountId(), rule, txn.txnId()),
                txn.tenantId(), txn.accountId(), txn.customerId(),
                rule, severity, reason, txnIds, txn.eventTime(), now);
    }

    /** Keeps the window arrays sorted by event time; tolerates out-of-order arrivals. */
    private static void insertSorted(AccountRiskState state, long t, String txnId) {
        int n = state.recentTimesMs.length;
        int pos = n;
        while (pos > 0 && state.recentTimesMs[pos - 1] > t) {
            pos--;
        }
        long[] times = new long[n + 1];
        String[] ids = new String[n + 1];
        System.arraycopy(state.recentTimesMs, 0, times, 0, pos);
        System.arraycopy(state.recentTxnIds, 0, ids, 0, pos);
        times[pos] = t;
        ids[pos] = txnId;
        System.arraycopy(state.recentTimesMs, pos, times, pos + 1, n - pos);
        System.arraycopy(state.recentTxnIds, pos, ids, pos + 1, n - pos);
        state.recentTimesMs = times;
        state.recentTxnIds = ids;
    }

    private static void pruneBefore(AccountRiskState state, long cutoffExclusive) {
        int first = 0;
        while (first < state.recentTimesMs.length && state.recentTimesMs[first] <= cutoffExclusive) {
            first++;
        }
        if (first > 0) {
            state.recentTimesMs = Arrays.copyOfRange(state.recentTimesMs, first, state.recentTimesMs.length);
            state.recentTxnIds = Arrays.copyOfRange(state.recentTxnIds, first, state.recentTxnIds.length);
        }
    }

    private static String human(Duration d) {
        return d.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
