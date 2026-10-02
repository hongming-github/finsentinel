package com.finsentinel.platform.investigation;

import com.finsentinel.common.Transaction;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.knowledge.KnowledgeSearch;
import com.finsentinel.platform.knowledge.RegulationHit;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    private final KnowledgeSearch knowledge;
    private final InvestigationProperties.Tools limits;
    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();
    private final Set<String> seenTxnIds = ConcurrentHashMap.newKeySet();
    private final Set<String> seenChunkIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger searches = new AtomicInteger();

    InvestigationTools(StoredAlert alert, TransactionRepository transactions, AlertRepository alerts,
                       KnowledgeSearch knowledge, InvestigationProperties.Tools limits) {
        this.alert = alert;
        this.transactions = transactions;
        this.alerts = alerts;
        this.knowledge = knowledge;
        this.limits = limits;
    }

    @Tool(description = "Search MAS regulations and guidelines (E-Payments User Protection Guidelines, Shared "
            + "Responsibility Framework, MAS Notice 626 on AML/CFT, Technology Risk Management Guidelines) for what "
            + "the bank must do next. One short question about one topic per call; limited calls per case.")
    RegulationSearchResult searchRegulations(
            @ToolParam(description = "One short question, e.g. 'cooling off period before adding a new payee'") String query) {
        long start = System.nanoTime();
        if (searches.incrementAndGet() > knowledge.maxSearchesPerCase()) {
            calls.add(new ToolCall("searchRegulations", "limit reached", 0, millisSince(start)));
            return new RegulationSearchResult(List.of(), "Search limit for this case reached; use the passages already found.");
        }
        List<RegulationHit> hits = knowledge.search(query).stream().map(this::trimmed).toList();
        hits.forEach(h -> seenChunkIds.add(h.chunkId()));
        calls.add(new ToolCall("searchRegulations", query, hits.size(), millisSince(start)));
        return new RegulationSearchResult(hits, hits.isEmpty() ? "No matching passages." : null);
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

    /** Long passages crowd out the case evidence in the model's context; the citation still points to the full text. */
    private RegulationHit trimmed(RegulationHit hit) {
        int max = knowledge.maxPassageCharsForAgent();
        return hit.text().length() <= max ? hit
                : new RegulationHit(hit.chunkId(), hit.citation(), hit.section(), hit.page(), hit.text().substring(0, max) + " [...]");
    }

    List<ToolCall> calls() {
        return List.copyOf(calls);
    }

    Set<String> seenTxnIds() {
        return Set.copyOf(seenTxnIds);
    }

    Set<String> seenChunkIds() {
        return Set.copyOf(seenChunkIds);
    }

    /** Read from the data, not from what the model said, so the transfer rule cannot be argued away. */
    boolean involvesTransfer() {
        return transactions.findByIds(tenant(), account(), alert.alert().txnIds()).stream()
                .anyMatch(t -> t.channel() == com.finsentinel.common.Channel.TRANSFER);
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
     * What the model sees of a transaction. Tool results are serialised to JSON and every field costs tokens.
     * <pre>
     * Transaction: {"txnId":"68c3…","tenantId":"straits","accountId":"straits-acc-0484","customerId":"straits-cus-0484",
     *               "amount":84.20,"currency":"SGD","merchantId":"m-601","merchantCategory":"5411",
     *               "channel":"CARD_PRESENT","country":"SG","eventTime":"2026-09-30T12:31:38.114530Z"}
     * TxnView:     {"id":"68c3…","time":"2026-09-30T12:31:38Z","amount":"84.20 SGD","channel":"CARD_PRESENT",
     *               "country":"SG","counterparty":"m-601","mcc":"5411"}
     * </pre>
     * Tenant, account and customer are identical on every row of a case, sub-second time does not matter for
     * fraud, and amount plus currency read naturally as one value. About 17% fewer input tokens per case.
     */
    record TxnView(String id, Instant time, String amount, String channel, String country, String counterparty,
                   String mcc) {

        static TxnView of(Transaction t) {
            return new TxnView(t.txnId(), t.eventTime().truncatedTo(ChronoUnit.SECONDS),
                    t.amount().toPlainString() + " " + t.currency(), t.channel().name(), t.country(),
                    t.merchantId(), t.merchantCategory());
        }
    }

    record RegulationSearchResult(List<RegulationHit> passages, String note) {
    }

    record PriorAlert(String rule, String severity, String reason, Instant eventTime, String status) {
    }

    record CounterpartyCheck(String txnId, String channel, String counterparty, long priorCount, Instant firstSeen,
                             BigDecimal largestPriorAmount) {
    }
}
