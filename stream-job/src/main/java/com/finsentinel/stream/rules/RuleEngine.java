package com.finsentinel.stream.rules;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.common.TravelFeasibility;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    private final TravelFeasibility travel;

    public RuleEngine(RuleConfig config) {
        this.config = config;
        this.travel = new TravelFeasibility(config.geoJumpMaxSpeedKmh());
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

    /**
     * Keeps the newest batch baseline for the account (ADR 0019). An older one arriving late (a re-run of a past day)
     * is ignored.
     */
    public void applyBaseline(AccountRiskState state, AccountBaseline baseline) {
        long asOf = LocalDate.ofInstant(baseline.asOf(), ZoneOffset.UTC).toEpochDay();
        if (asOf < state.baselineAsOfEpochDay) {
            return;
        }
        state.baselineAsOfEpochDay = asOf;
        state.baselineSampleSize = baseline.sampleSize();
        state.baselineMedian = baseline.medianAmount();
        state.baselineRobustStdDev = baseline.robustStdDev();
    }

    private void checkLargeAmount(AccountRiskState state, Transaction txn, Instant now, List<Alert> out) {
        double amount = txn.amount().doubleValue();
        boolean overHardLimit = txn.amount().compareTo(config.hardLimit()) >= 0;
        Profile profile = profile(state, txn);
        boolean outlier = profile != null
                && amount > profile.centre + config.largeAmountStdDevs() * profile.spread
                && amount > profile.centre * config.largeAmountMeanMultiple();

        if (overHardLimit || outlier) {
            String reason = overHardLimit
                    ? "amount %s %s at or above hard limit %s".formatted(txn.amount(), txn.currency(), config.hardLimit())
                    : "amount %s %s is %.1fx the account %s".formatted(
                            txn.amount(), txn.currency(), amount / profile.centre, profile.description);
            out.add(alert(txn, AlertRule.LARGE_AMOUNT, overHardLimit ? Severity.HIGH : Severity.MEDIUM,
                    reason, List.of(txn.txnId()), now));
        } else {
            // Flagged amounts stay out of the baseline so one fraud does not make the next look normal.
            state.recordAmount(amount);
        }
    }

    /**
     * What "normal" means for this account: its own streaming history once there is enough of it; until then (a new
     * job without its state, an account idle past the state TTL) a fresh batch baseline; otherwise nothing, and only
     * the hard limit applies.
     */
    private Profile profile(AccountRiskState state, Transaction txn) {
        if (state.amountCount >= config.largeAmountMinHistory()) {
            return new Profile(state.amountMean, state.amountStdDev(),
                    "mean of %.2f over %d transactions".formatted(state.amountMean, state.amountCount));
        }
        if (state.baselineAsOfEpochDay == Long.MIN_VALUE
                || state.baselineSampleSize < config.largeAmountMinHistory()) {
            return null;
        }
        Instant baselineDate = LocalDate.ofEpochDay(state.baselineAsOfEpochDay).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        if (Duration.between(baselineDate, txn.eventTime()).compareTo(config.baselineMaxAge()) > 0) {
            return null;
        }
        return new Profile(state.baselineMedian, state.baselineRobustStdDev,
                "median of %.2f over %d transactions (batch baseline as of %s)".formatted(state.baselineMedian,
                        state.baselineSampleSize, LocalDate.ofEpochDay(state.baselineAsOfEpochDay)));
    }

    private record Profile(double centre, double spread, String description) {
    }

    /**
     * Two card-present payments in different countries sooner than any flight between them allows. Countries
     * connected by land never fire (no bound). A country without flight data falls back to the plain window, and the
     * reason says so.
     */
    private void checkGeoJump(AccountRiskState state, Transaction txn, Instant now, List<Alert> out) {
        if (txn.channel() != Channel.CARD_PRESENT) {
            return;
        }
        long t = txn.eventTime().toEpochMilli();
        if (state.lastCardPresentCountry != null && !state.lastCardPresentCountry.equals(txn.country())) {
            Duration gap = Duration.ofMillis(Math.abs(t - state.lastCardPresentTimeMs));
            // Out-of-order arrivals: the reason and the transaction ids follow event time.
            boolean arrivedLate = t < state.lastCardPresentTimeMs;
            List<String> txnIds = arrivedLate ? List.of(txn.txnId(), state.lastCardPresentTxnId)
                    : List.of(state.lastCardPresentTxnId, txn.txnId());
            TravelFeasibility.Check check = arrivedLate ? travel.check(txn.country(), state.lastCardPresentCountry, gap)
                    : travel.check(state.lastCardPresentCountry, txn.country(), gap);
            String reason = switch (check.verdict()) {
                case IMPOSSIBLE -> "card present in %s then %s %s apart; the fastest possible trip takes %s (%s)"
                        .formatted(check.from(), check.to(), human(gap), human(check.minimum()), check.basis());
                case UNKNOWN -> gap.compareTo(config.geoJumpFallbackWindow()) <= 0
                        ? "card present in %s then %s within %s (%s, so judged by the %s window)".formatted(
                                check.from(), check.to(), human(gap), check.basis(),
                                human(config.geoJumpFallbackWindow()))
                        : null;
                case POSSIBLE -> null;
            };
            if (reason != null) {
                out.add(alert(txn, AlertRule.GEO_JUMP, Severity.HIGH, reason, txnIds, now));
            }
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

    /** "10h41m" rather than "PT10H41M51S": seconds do not matter at this scale. */
    private static String human(Duration d) {
        Duration rounded = d.compareTo(Duration.ofMinutes(1)) >= 0 ? d.truncatedTo(ChronoUnit.MINUTES) : d;
        return rounded.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
