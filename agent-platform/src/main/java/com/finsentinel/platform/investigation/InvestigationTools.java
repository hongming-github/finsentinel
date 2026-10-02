package com.finsentinel.platform.investigation;

import com.finsentinel.common.Transaction;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.transaction.AccountProfile;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The agent's tools for one case, created by {@link InvestigationToolsFactory}. Tenant and account are fixed
 * at construction, so no tool parameter can point the model at another customer's data: scoping is a
 * property of the object, not a rule in the prompt.
 *
 * <p>Also records every call and every transaction id it returned, so the report can be checked
 * against what the agent actually saw.
 */
class InvestigationTools {

    private final StoredAlert alert;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;
    private final InvestigationProperties.Tools limits;
    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();
    private final Set<String> seenTxnIds = ConcurrentHashMap.newKeySet();

    InvestigationTools(StoredAlert alert, TransactionRepository transactions, AlertRepository alerts,
                       InvestigationProperties.Tools limits) {
        this.alert = alert;
        this.transactions = transactions;
        this.alerts = alerts;
        this.limits = limits;
    }

    @Tool(description = "Get the transactions that triggered this alert, oldest first.")
    List<TxnView> getAlertTransactions() {
        long start = System.nanoTime();
        List<Transaction> result = transactions.findByIds(tenant(), account(), alert.alert().txnIds());
        return record("getAlertTransactions", "", result, start);
    }

    @Tool(description = "Get this account's transactions from the given number of days before the alert "
            + "up to now, newest first.")
    List<TxnView> getRecentTransactions(
            @ToolParam(description = "How many days before the alert to look back; large values are capped") int days,
            @ToolParam(description = "Maximum transactions to return; large values are capped") int limit) {
        long start = System.nanoTime();
        int d = Math.clamp(days, 1, limits.maxDays());
        int l = Math.clamp(limit, 1, limits.maxTransactions());
        List<Transaction> result = transactions.findRecent(tenant(), account(), anchor().minus(Duration.ofDays(d)), l);
        return record("getRecentTransactions", "days=%d, limit=%d".formatted(d, l), result, start);
    }

    @Tool(description = "Get this account's normal behaviour in the given number of days before the alert: "
            + "amount statistics, channel mix, and countries of card-present payments. Excludes the alert's own "
            + "transactions and anything after the alert, so it describes the baseline, not the suspicious activity.")
    AccountProfile getAccountProfile(
            @ToolParam(description = "How many days before the alert to summarise; large values are capped") int days) {
        long start = System.nanoTime();
        int d = Math.clamp(days, 1, limits.maxDays());
        AccountProfile profile = transactions.profile(tenant(), account(), anchor().minus(Duration.ofDays(d)), anchor(),
                alert.alert().txnIds());
        calls.add(new ToolCall("getAccountProfile", "days=" + d, (int) profile.amounts().transactionCount(), millisSince(start)));
        return profile;
    }

    @Tool(description = "For each transaction in this alert, say whether the account has dealt with that "
            + "counterparty (merchant, or payee for transfers) before the alert, how often, since when, and the "
            + "largest earlier amount. A priorCount of 0 means a counterparty the customer has never used.")
    List<CounterpartyCheck> getCounterpartyHistory() {
        long start = System.nanoTime();
        List<Transaction> alertTxns = transactions.findByIds(tenant(), account(), alert.alert().txnIds());
        alertTxns.forEach(t -> seenTxnIds.add(t.txnId()));
        Map<String, TransactionRepository.CounterpartyStats> history = transactions.counterpartyHistory(tenant(), account(),
                alertTxns.stream().map(Transaction::merchantId).filter(Objects::nonNull).distinct().toList(),
                alertTxns.stream().map(Transaction::eventTime).min(Instant::compareTo).orElse(anchor()),
                alert.alert().txnIds());
        List<CounterpartyCheck> result = alertTxns.stream().map(t -> {
            TransactionRepository.CounterpartyStats stats = history.get(t.merchantId());
            return stats == null
                    ? new CounterpartyCheck(t.txnId(), t.channel().name(), t.merchantId(), 0, null, null)
                    : new CounterpartyCheck(t.txnId(), t.channel().name(), t.merchantId(), stats.priorCount(),
                    stats.firstSeen(), stats.maxAmount());
        }).toList();
        calls.add(new ToolCall("getCounterpartyHistory", "", result.size(), millisSince(start)));
        return result;
    }

    @Tool(description = "Get other alerts raised on this account in the given number of days before this alert.")
    List<PriorAlert> getPriorAlerts(
            @ToolParam(description = "How many days before the alert to look back; large values are capped") int days) {
        long start = System.nanoTime();
        int d = Math.clamp(days, 1, limits.maxDays());
        List<PriorAlert> result = alerts.findForAccount(tenant(), account(), anchor().minus(Duration.ofDays(d)),
                        alert.alert().alertId()).stream()
                .map(a -> new PriorAlert(a.alert().rule().name(), a.alert().severity().name(), a.alert().reason(),
                        a.alert().eventTime(), a.status().name()))
                .toList();
        calls.add(new ToolCall("getPriorAlerts", "days=" + d, result.size(), millisSince(start)));
        return result;
    }

    List<ToolCall> calls() {
        return List.copyOf(calls);
    }

    Set<String> seenTxnIds() {
        return Set.copyOf(seenTxnIds);
    }

    private List<TxnView> record(String tool, String args, List<Transaction> result, long start) {
        result.forEach(t -> seenTxnIds.add(t.txnId()));
        calls.add(new ToolCall(tool, args, result.size(), millisSince(start)));
        return result.stream().map(TxnView::of).toList();
    }

    private String tenant() {
        return alert.alert().tenantId();
    }

    private String account() {
        return alert.alert().accountId();
    }

    private Instant anchor() {
        return alert.alert().eventTime();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * What the model sees of a transaction. Tenant, account and customer are the same for every row of a
     * case, so repeating them would only burn tokens; time is cut to the second for the same reason.
     */
    record TxnView(String id, Instant time, String amount, String channel, String country, String counterparty,
                   String mcc) {

        static TxnView of(Transaction t) {
            return new TxnView(t.txnId(), t.eventTime().truncatedTo(ChronoUnit.SECONDS),
                    t.amount().toPlainString() + " " + t.currency(), t.channel().name(), t.country(),
                    t.merchantId(), t.merchantCategory());
        }
    }

    record PriorAlert(String rule, String severity, String reason, Instant eventTime, String status) {
    }

    record CounterpartyCheck(String txnId, String channel, String counterparty, long priorCount, Instant firstSeen,
                             BigDecimal largestPriorAmount) {
    }
}
