package com.finsentinel.platform.assistant;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.cases.CaseEvent;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.cases.FraudCase;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.systems.BankSystem;
import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the assistant knows about the case, read from the database at every turn (ADR 0028): the case and its
 * decisions, its alerts, their transactions and any other the report cites, the latest validated report with the
 * reviewer's verdict and the tools the agent used. Never kept in the conversation history: however long it gets,
 * the assistant cannot forget or misremember the case, and a decision or a new analysis is in the very next answer.
 * Compact views, like tool results, and the same bytes while the case is unchanged, so the provider can reuse the
 * prompt prefix between turns.
 */
@Component
class CaseFacts {

    private final CaseRepository cases;
    private final AlertRepository alerts;
    private final TransactionRepository transactions;
    private final SystemsProperties systems;
    private final AssistantProperties props;
    private final JsonMapper json;

    CaseFacts(CaseRepository cases, AlertRepository alerts, TransactionRepository transactions,
              SystemsProperties systems, AssistantProperties props, JsonMapper json) {
        this.cases = cases;
        this.alerts = alerts;
        this.transactions = transactions;
        this.systems = systems;
        this.props = props;
        this.json = json;
    }

    /**
     * @param context     for the model router and the pseudonym scope, as for the case's own agents
     * @param text        the fact sheet as the model reads it
     * @param txnIds      transaction ids the sheet shows: the only ones an answer may cite
     * @param chunkIds    passages the report cites: an answer may cite them without searching again
     */
    record Facts(FraudCase fraudCase, CaseContext context, String text, Set<String> txnIds, Set<String> chunkIds) {
    }

    /** Empty when the tenant has no such case. Reads in the caller's tenant scope. */
    Optional<Facts> load(String tenantId, String caseId) {
        Optional<FraudCase> found = cases.find(tenantId, caseId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FraudCase c = found.get();
        List<Alert> caseAlerts = alerts.findByCase(tenantId, caseId).stream().map(StoredAlert::alert).toList();
        CaseContext context = new CaseContext(c.caseId(), tenantId, c.accountId(), c.customerId(), caseAlerts,
                c.analysisRun(), c.analystNote(), systems.connectedFor(tenantId));
        Optional<CaseRepository.CaseAnalysis> latest = cases.analyses(tenantId, caseId).stream().findFirst();
        // The alerts' transactions and any other the report cites (the agents also read the account's history).
        Set<String> shown = new LinkedHashSet<>(context.txnIds());
        latest.ifPresent(a -> a.report().findings().forEach(f -> shown.addAll(f.evidenceTxnIds())));
        List<TxnView> txns = transactions.findByIds(tenantId, c.accountId(), shown).stream()
                .sorted(Comparator.comparing(Transaction::eventTime).thenComparing(Transaction::txnId))
                .limit(props.maxCaseTransactions())
                .map(TxnView::of)
                .toList();
        List<CaseEvent> events = cases.events(tenantId, caseId);
        List<DecisionView> decisions = events.stream()
                .filter(e -> e.type().equals("DECIDED") || e.type().equals("REANALYSIS_REQUESTED"))
                .map(DecisionView::of).toList();
        List<String> untrusted = events.stream()
                .filter(e -> e.type().equals("UNTRUSTED_INSTRUCTIONS"))
                .flatMap(e -> e.detail().path("found").valueStream().map(n -> n.asString()))
                .distinct().toList();
        FactSheet sheet = new FactSheet(CaseView.of(c), caseAlerts.stream().map(AlertView::of).toList(), txns,
                latest.map(AnalysisView::of).orElse(null), decisions,
                context.unconnectedSystems().stream().map(BankSystem::name).toList(), untrusted);
        Set<String> txnIds = new LinkedHashSet<>();
        txns.forEach(t -> txnIds.add(t.id()));
        Set<String> chunkIds = new LinkedHashSet<>();
        latest.ifPresent(a -> a.report().regulatoryNotes().forEach(n -> chunkIds.addAll(n.sourceChunkIds())));
        return Optional.of(new Facts(c, context, json.writeValueAsString(sheet), Set.copyOf(txnIds),
                Set.copyOf(chunkIds)));
    }

    /**
     * @param latestAnalysis       null while no validated report exists
     * @param decisions            what analysts decided or sent back, with their notes, oldest first
     * @param unconnectedSystems   the bank's systems the agents could not consult
     * @param untrustedInstructions what case data tried to instruct the agents (ADR 0016); such a case is never cleared
     */
    record FactSheet(CaseView caseFile, List<AlertView> alerts, List<TxnView> transactions,
                     AnalysisView latestAnalysis, List<DecisionView> decisions, List<String> unconnectedSystems,
                     List<String> untrustedInstructions) {
    }

    record CaseView(String caseId, String stage, String priority, int version, int analysisRun, String decision,
                    String decidedBy, String analystNote, Instant openedAt, Instant closedAt) {

        static CaseView of(FraudCase c) {
            return new CaseView(c.caseId(), c.stage().name(), c.priority().name(), c.version(), c.analysisRun(),
                    c.decision() == null ? null : c.decision().name(), c.decidedBy(), c.analystNote(),
                    seconds(c.openedAt()), seconds(c.closedAt()));
        }
    }

    record AlertView(String rule, String severity, String reason, Instant eventTime, List<String> txnIds) {

        static AlertView of(Alert a) {
            return new AlertView(a.rule().name(), a.severity().name(), a.reason(), seconds(a.eventTime()), a.txnIds());
        }
    }

    record TxnView(String id, Instant time, String amount, String channel, String country, String counterparty,
                   String mcc) {

        static TxnView of(Transaction t) {
            return new TxnView(t.txnId(), seconds(t.eventTime()), t.amount().toPlainString() + " " + t.currency(),
                    t.channel().name(), t.country(), t.merchantId(), t.merchantCategory());
        }
    }

    record AnalysisView(int run, String mode, String disposition, int riskScore, String summary,
                        List<CaseReport.Finding> findings, List<String> recommendedActions,
                        List<CaseReport.RegulatoryNote> regulatoryNotes, ReviewView review, List<ToolUse> toolsUsed) {

        static AnalysisView of(CaseRepository.CaseAnalysis a) {
            CaseReport r = a.report();
            return new AnalysisView(a.run(), a.mode(), r.disposition().name(), r.riskScore(), r.summary(),
                    r.findings(), r.recommendedActions(), r.regulatoryNotes(),
                    a.review() == null ? null : ReviewView.of(a.review()),
                    a.toolCalls().stream().map(t -> new ToolUse(t.tool(), t.arguments(), t.resultCount())).toList());
        }
    }

    /** @param skippedBecause set when the risk-based review did not read the report: it is not an approval */
    record ReviewView(boolean reviewed, boolean approved, List<String> issues, String skippedBecause) {

        static ReviewView of(Review r) {
            return new ReviewView(r.reviewed(), r.approved(), r.issues(), r.skippedBecause());
        }
    }

    record ToolUse(String tool, String arguments, int results) {
    }

    record DecisionView(Instant at, String decision, String by, String note) {

        static DecisionView of(CaseEvent e) {
            JsonNode note = e.detail().path("note");
            return new DecisionView(seconds(e.at()), e.detail().path("decision").asString(null), e.actor(),
                    note.isString() ? note.asString() : null);
        }
    }

    private static Instant seconds(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.SECONDS);
    }
}
