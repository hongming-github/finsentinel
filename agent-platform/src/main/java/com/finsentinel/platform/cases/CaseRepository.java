package com.finsentinel.platform.cases;

import com.finsentinel.platform.observability.Traces;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.investigation.ToolCall;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The case lifecycle: the outer layer of ADR 0008. The table is also the work queue; every transition is one
 * transaction that also writes the audit event and, where downstream systems care, an outbox message.
 */
@Repository
public class CaseRepository {

    /**
     * Arbitrary but fixed key for the advisory lock that serialises budget-checked claims across instances.
     * Advisory lock keys share one namespace per database: register new ones here to avoid collisions.
     */
    static final long CLAIM_LOCK_KEY = 7_301_001L;

    private static final String PRIORITY_ORDER =
            "case priority when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxRepository outbox;

    CaseRepository(JdbcClient jdbc, JsonMapper json, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    // ---------------------------------------------------------------- intake

    /**
     * Finds or opens the account's open case for an incoming alert and returns its id. Must run in the intake
     * transaction: the case row stays locked until the alert has been inserted.
     *
     * @param traceParent the alert's trace: the run this alert starts continues it (ADR 0020); null if untraced
     */
    String attach(Alert alert, String traceParent) {
        Optional<String> created = jdbc.sql("""
                        insert into fraud_case (case_id, tenant_id, account_id, customer_id, stage, priority,
                                                trace_parent)
                        values (:caseId, :tenantId, :accountId, :customerId, 'ANALYSIS', :priority, :traceParent)
                        on conflict (tenant_id, account_id) where stage <> 'CLOSED' do nothing
                        returning case_id
                        """)
                .param("caseId", UUID.randomUUID())
                .param("tenantId", alert.tenantId())
                .param("accountId", alert.accountId())
                .param("customerId", alert.customerId())
                .param("priority", alert.severity().name())
                .param("traceParent", traceParent)
                .query(String.class)
                .optional();
        if (created.isPresent()) {
            event(created.get(), "OPENED", null, CaseStage.ANALYSIS, "system", alertDetail(alert));
            return created.get();
        }
        FraudCase open = jdbc.sql("""
                        select * from fraud_case
                        where tenant_id = :tenantId and account_id = :accountId and stage <> 'CLOSED'
                        for update
                        """)
                .param("tenantId", alert.tenantId())
                .param("accountId", alert.accountId())
                .query((rs, n) -> map(rs))
                .single();
        boolean reopen = open.stage() != CaseStage.ANALYSIS;
        // Any new evidence invalidates the analysis in progress or on the table: bump the run.
        jdbc.sql("""
                        update fraud_case
                        set stage = 'ANALYSIS',
                            priority = case when (case :severity when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end)
                                                 < %s
                                            then :severity else priority end,
                            analysis_run = analysis_run + 1,
                            attempts = case when stage = 'ANALYSIS' then attempts else 0 end,
                            claimed_at = case when stage = 'ANALYSIS' then claimed_at end,
                            trace_parent = :traceParent,
                            version = version + 1,
                            updated_at = now()
                        where case_id = :caseId
                        """.formatted(PRIORITY_ORDER))
                .param("severity", alert.severity().name())
                .param("traceParent", traceParent)
                .param("caseId", UUID.fromString(open.caseId()))
                .update();
        event(open.caseId(), reopen ? "REOPENED" : "ALERT_ADDED", open.stage(), CaseStage.ANALYSIS, "system",
                alertDetail(alert));
        return open.caseId();
    }

    /**
     * Where the current run's trace comes from (ADR 0020): the case's own trace context (what started this run) and
     * those of all its alerts, oldest first.
     */
    public RunTrace runTrace(String tenantId, String caseId) {
        String parent = jdbc.sql("select trace_parent from fraud_case where tenant_id = :tenantId and case_id = :caseId")
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query(String.class)
                .optional()
                .orElse(null);
        List<String> alerts = jdbc.sql("""
                        select trace_parent from alert
                        where tenant_id = :tenantId and case_id = :caseId and trace_parent is not null
                        order by received_at
                        """)
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query(String.class)
                .list();
        return new RunTrace(parent, alerts);
    }

    /** @param parent null when what started the run was not traced */
    public record RunTrace(String parent, List<String> alerts) {
    }

    // ---------------------------------------------------------------- work queue

    /** Analyses finished in the last hour plus analyses started and still running. */
    int startedInLastHour() {
        return jdbc.sql("""
                        select (select count(*) from case_analysis where created_at > now() - interval '1 hour')
                             + (select count(*) from fraud_case where stage = 'ANALYSIS'
                                  and claimed_at > now() - interval '1 hour')
                        """)
                .query(Integer.class)
                .single();
    }

    /**
     * Claims cases within the hourly budget, exactly, across any number of instances. Check-and-claim runs under a
     * transaction-scoped advisory lock (ADR 0004); waiting for it is bounded by {@code lockWaitTimeout}.
     *
     * @throws org.springframework.dao.DataAccessException on lock timeout; recognise it with {@link #isLockTimeout}
     */
    @Transactional
    public ClaimBatch claimWithinBudget(int batchSize, Duration claimTimeout, int maxAttempts, int maxPerHour,
                                        Duration lockWaitTimeout) {
        int limit = batchSize;
        if (maxPerHour > 0) {
            // set_config(..., true) is SET LOCAL: this transaction only, and it takes a bound parameter.
            jdbc.sql("select set_config('lock_timeout', :timeout, true)")
                    .param("timeout", lockWaitTimeout.toMillis() + "ms")
                    .query(rs -> {
                    });
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CLAIM_LOCK_KEY).query(rs -> {
            });
            int remaining = maxPerHour - startedInLastHour();
            if (remaining <= 0) {
                return new ClaimBatch(List.of(), true);
            }
            limit = Math.min(limit, remaining);
        }
        return new ClaimBatch(claim(limit, claimTimeout, maxAttempts), false);
    }

    /** HIGH priority first, then oldest; also takes back claims that timed out. */
    List<CaseClaim> claim(int limit, Duration claimTimeout, int maxAttempts) {
        return jdbc.sql("""
                        update fraud_case
                        set claimed_at = clock_timestamp(), attempts = attempts + 1, updated_at = now()
                        where case_id in (
                            select case_id from fraud_case
                            where stage = 'ANALYSIS' and attempts < :maxAttempts
                              and (claimed_at is null or claimed_at < now() - make_interval(secs => :timeoutSecs))
                            order by %s, opened_at
                            limit :limit
                            for update skip locked)
                        returning *
                        """.formatted(PRIORITY_ORDER))
                .param("maxAttempts", maxAttempts)
                .param("timeoutSecs", claimTimeout.toSeconds())
                .param("limit", limit)
                .query((rs, n) -> new CaseClaim(map(rs), rs.getTimestamp("claimed_at").toInstant()))
                .list();
    }

    /**
     * Stores the analysis and moves the case on, but only while this worker still holds the claim.
     * If new evidence arrived during the analysis (the run moved on), the analysis is kept for the record and the
     * case goes back to the queue instead of to the analyst.
     */
    @Transactional
    public Completion complete(CaseClaim claim, AnalysisMode mode, InvestigationResult result, Review review) {
        FraudCase claimed = claim.fraudCase();
        Optional<Integer> currentRun = jdbc.sql("""
                        select analysis_run from fraud_case
                        where case_id = :caseId and stage = 'ANALYSIS' and claimed_at = :claimedAt
                        for update
                        """)
                .param("caseId", UUID.fromString(claimed.caseId()))
                .param("claimedAt", Timestamp.from(claim.claimedAt()))
                .query(Integer.class)
                .optional();
        if (currentRun.isEmpty()) {
            return Completion.LOST_CLAIM;
        }
        insertAnalysis(claimed.caseId(), claimed.analysisRun(), mode, result, review);
        if (!result.untrustedInstructions().isEmpty()) {
            // On the case, from the platform: the analyst sees it whether or not the report mentions it (ADR 0016).
            event(claimed.caseId(), "UNTRUSTED_INSTRUCTIONS", CaseStage.ANALYSIS, CaseStage.ANALYSIS, "system",
                    Map.of("run", claimed.analysisRun(), "found", result.untrustedInstructions()));
        }
        // This run is over either way; earlier runs' leftovers (a failed attempt never resumed) go with it.
        deleteCheckpoints(claimed.caseId(), claimed.analysisRun());
        if (currentRun.get() != claimed.analysisRun()) {
            jdbc.sql("""
                            update fraud_case set claimed_at = null, attempts = 0, updated_at = now()
                            where case_id = :caseId
                            """)
                    .param("caseId", UUID.fromString(claimed.caseId()))
                    .update();
            event(claimed.caseId(), "ANALYSIS_SUPERSEDED", CaseStage.ANALYSIS, CaseStage.ANALYSIS, "system",
                    Map.of("run", claimed.analysisRun(), "currentRun", currentRun.get()));
            return Completion.SUPERSEDED;
        }
        jdbc.sql("""
                        update fraud_case
                        set stage = 'AWAITING_DECISION', claimed_at = null, attempts = 0, analyst_note = null,
                            version = version + 1, updated_at = now()
                        where case_id = :caseId
                        """)
                .param("caseId", UUID.fromString(claimed.caseId()))
                .update();
        Map<String, Object> detail = Map.of("run", claimed.analysisRun(),
                "disposition", result.report().disposition().name(), "riskScore", result.report().riskScore(),
                "reviewApproved", review.approved(), "untrustedInstructions", !result.untrustedInstructions().isEmpty());
        event(claimed.caseId(), "ANALYSED", CaseStage.ANALYSIS, CaseStage.AWAITING_DECISION, "system", detail);
        outbox.append(claimed.caseId(), "case.ready_for_decision", payload(claimed, detail));
        return Completion.READY_FOR_DECISION;
    }

    /** Releases a failed claim: back to the queue, or FAILED when attempts are used up. */
    @Transactional
    public void fail(CaseClaim claim, int maxAttempts, String error) {
        FraudCase claimed = claim.fraudCase();
        Optional<String> stage = jdbc.sql("""
                        update fraud_case
                        set stage = case when attempts >= :maxAttempts then 'FAILED' else stage end,
                            claimed_at = null, version = version + 1, updated_at = now()
                        where case_id = :caseId and stage = 'ANALYSIS' and claimed_at = :claimedAt
                        returning stage
                        """)
                .param("maxAttempts", maxAttempts)
                .param("caseId", UUID.fromString(claimed.caseId()))
                .param("claimedAt", Timestamp.from(claim.claimedAt()))
                .query(String.class)
                .optional();
        if (stage.isPresent()) {
            failed(claimed, CaseStage.valueOf(stage.get()), error);
        }
    }

    /** Timed-out claims with no attempts left would otherwise sit in ANALYSIS forever. */
    @Transactional
    public int failAbandoned(Duration claimTimeout, int maxAttempts) {
        List<FraudCase> abandoned = jdbc.sql("""
                        update fraud_case set stage = 'FAILED', claimed_at = null, version = version + 1, updated_at = now()
                        where stage = 'ANALYSIS' and attempts >= :maxAttempts
                          and claimed_at < now() - make_interval(secs => :timeoutSecs)
                        returning *
                        """)
                .param("maxAttempts", maxAttempts)
                .param("timeoutSecs", claimTimeout.toSeconds())
                .query((rs, n) -> map(rs))
                .list();
        abandoned.forEach(c -> failed(c, CaseStage.FAILED, "claim abandoned after " + maxAttempts + " attempts"));
        return abandoned.size();
    }

    private void failed(FraudCase c, CaseStage to, String error) {
        event(c.caseId(), to == CaseStage.FAILED ? "FAILED" : "ANALYSIS_ATTEMPT_FAILED", CaseStage.ANALYSIS, to,
                "system", Map.of("error", error));
        if (to == CaseStage.FAILED) {
            // A re-analysis of a FAILED case starts a new run, so these could never be resumed.
            deleteCheckpoints(c.caseId(), Integer.MAX_VALUE);
            outbox.append(c.caseId(), "case.failed", payload(c, Map.of("error", error)));
        }
    }

    // ---------------------------------------------------------------- analyst decision

    /**
     * Applies an analyst decision if the case is still in the version the analyst looked at.
     * A new alert or a re-analysis bumps the version, so a decision made on stale evidence is refused.
     */
    @Transactional
    public DecisionResult decide(String tenantId, String caseId, int expectedVersion, Decision decision, String analyst,
                                 String note) {
        CaseStage to = decision.nextStage();
        Optional<FraudCase> updated = jdbc.sql("""
                        update fraud_case
                        set stage = :to,
                            decision = case when :to = 'CLOSED' then :decision end,
                            decided_by = case when :to = 'CLOSED' then :analyst end,
                            closed_at = case when :to = 'CLOSED' then now() end,
                            analysis_run = analysis_run + case when :to = 'ANALYSIS' then 1 else 0 end,
                            analyst_note = case when :to = 'ANALYSIS' then :note end,
                            trace_parent = case when :to = 'ANALYSIS' then :traceParent else trace_parent end,
                            attempts = 0, claimed_at = null, version = version + 1, updated_at = now()
                        where tenant_id = :tenantId and case_id = :caseId and version = :version
                          and stage in ('AWAITING_DECISION', 'FAILED')
                        returning *
                        """)
                .param("to", to.name())
                .param("decision", decision.name())
                .param("analyst", analyst)
                .param("note", note)
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .param("version", expectedVersion)
                // The analyst's request starts the next run: the analysis continues its trace.
                .param("traceParent", Traces.current())
                .query((rs, n) -> map(rs))
                .optional();
        if (updated.isEmpty()) {
            return find(tenantId, caseId)
                    .<DecisionResult>map(DecisionResult.Conflict::new)
                    .orElseGet(DecisionResult.NotFound::new);
        }
        FraudCase c = updated.get();
        Map<String, Object> detail = note == null ? Map.of("decision", decision.name())
                : Map.of("decision", decision.name(), "note", note);
        event(caseId, decision == Decision.REQUEST_REANALYSIS ? "REANALYSIS_REQUESTED" : "DECIDED", null, to,
                analyst, detail);
        if (to == CaseStage.CLOSED) {
            outbox.append(caseId, "case.decided", payload(c, Map.of("decision", decision.name(), "decidedBy", analyst)));
        }
        return new DecisionResult.Applied(c);
    }

    public sealed interface DecisionResult {
        record Applied(FraudCase fraudCase) implements DecisionResult {
        }

        /** The case exists but is in another version or stage than the analyst saw. */
        record Conflict(FraudCase current) implements DecisionResult {
        }

        record NotFound() implements DecisionResult {
        }
    }

    // ---------------------------------------------------------------- reads (tenant-scoped)

    public Optional<FraudCase> find(String tenantId, String caseId) {
        return jdbc.sql("select * from fraud_case where tenant_id = :tenantId and case_id = :caseId")
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query((rs, n) -> map(rs))
                .optional();
    }

    public List<FraudCase> list(String tenantId, CaseStage stage, int limit) {
        return jdbc.sql("""
                        select * from fraud_case
                        where tenant_id = :tenantId and (cast(:stage as text) is null or stage = :stage)
                        order by %s, updated_at desc
                        limit :limit
                        """.formatted(PRIORITY_ORDER))
                .param("tenantId", tenantId)
                .param("stage", stage == null ? null : stage.name())
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Like {@link #list}, with each case's latest analysis verdict, in one query (a lateral join, not a query per
     * case).
     */
    public List<CaseListing> listWithLatestVerdict(String tenantId, CaseStage stage, int limit) {
        return jdbc.sql("""
                        select c.*, a.disposition as latest_disposition, a.risk_score as latest_risk_score
                        from fraud_case c
                        left join lateral (
                            select disposition, risk_score from case_analysis a
                            where a.case_id = c.case_id order by a.run desc limit 1
                        ) a on true
                        where c.tenant_id = :tenantId and (cast(:stage as text) is null or c.stage = :stage)
                        order by %s, c.updated_at desc
                        limit :limit
                        """.formatted(PRIORITY_ORDER.replace("priority", "c.priority")))
                .param("tenantId", tenantId)
                .param("stage", stage == null ? null : stage.name())
                .param("limit", limit)
                .query((rs, n) -> new CaseListing(map(rs), rs.getString("latest_disposition"),
                        (Integer) rs.getObject("latest_risk_score")))
                .list();
    }

    /** @param latestDisposition null until the first analysis is stored */
    public record CaseListing(FraudCase fraudCase, String latestDisposition, Integer latestRiskScore) {
    }

    public List<CaseEvent> events(String tenantId, String caseId) {
        return jdbc.sql("""
                        select e.* from case_event e join fraud_case c using (case_id)
                        where c.tenant_id = :tenantId and e.case_id = :caseId
                        order by e.event_id
                        """)
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query((rs, n) -> new CaseEvent(rs.getLong("event_id"), rs.getTimestamp("at").toInstant(),
                        rs.getString("type"), rs.getString("from_stage"), rs.getString("to_stage"),
                        rs.getString("actor"), json.readTree(rs.getString("detail"))))
                .list();
    }

    /** Every analysis of the case, newest run first. */
    public List<CaseAnalysis> analyses(String tenantId, String caseId) {
        return jdbc.sql("""
                        select a.* from case_analysis a join fraud_case c using (case_id)
                        where c.tenant_id = :tenantId and a.case_id = :caseId
                        order by a.run desc
                        """)
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query((rs, n) -> new CaseAnalysis(rs.getInt("run"),
                        json.readValue(rs.getString("report"), CaseReport.class),
                        json.readValue(rs.getString("tool_calls"), new TypeReference<List<ToolCall>>() {
                        }),
                        List.of((String[]) rs.getArray("repaired_issues").getArray()),
                        rs.getString("model"), (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"), (Integer) rs.getObject("cached_input_tokens"),
                        rs.getInt("llm_calls"), rs.getLong("latency_ms"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("mode"),
                        rs.getString("review") == null ? null : json.readValue(rs.getString("review"), Review.class),
                        rs.getBigDecimal("cost_usd")))
                .list();
    }

    /**
     * @param mode   how the analysis was produced; null for analyses from before phase 4 step C2
     * @param review the reviewer's last verdict; null for analyses from before phase 4 step C2
     * @param costUsd at list prices; null for analyses from before phase 5
     */
    public record CaseAnalysis(int run, CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues,
                               String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                               int llmCalls, long latencyMs, Instant createdAt, String mode, Review review,
                               BigDecimal costUsd) {
    }

    // ---------------------------------------------------------------- helpers

    private void insertAnalysis(String caseId, int run, AnalysisMode mode, InvestigationResult result, Review review) {
        jdbc.sql("""
                        insert into case_analysis (case_id, run, disposition, risk_score, report, tool_calls,
                                                   repaired_issues, model, input_tokens, output_tokens,
                                                   cached_input_tokens, llm_calls, latency_ms, mode, review,
                                                   cost_usd, cost_by_model)
                        values (:caseId, :run, :disposition, :riskScore, cast(:report as jsonb),
                                cast(:toolCalls as jsonb), :repaired, :model, :inputTokens, :outputTokens,
                                :cachedInputTokens, :llmCalls, :latencyMs, :mode, cast(:review as jsonb),
                                :costUsd, cast(:costByModel as jsonb))
                        on conflict (case_id, run) do nothing
                        """)
                .param("caseId", UUID.fromString(caseId))
                .param("run", run)
                .param("disposition", result.report().disposition().name())
                .param("riskScore", result.report().riskScore())
                .param("report", json.writeValueAsString(result.report()))
                .param("toolCalls", json.writeValueAsString(result.toolCalls()))
                .param("repaired", result.repairedIssues().toArray(String[]::new))
                .param("model", result.model())
                .param("inputTokens", result.inputTokens())
                .param("outputTokens", result.outputTokens())
                .param("cachedInputTokens", result.cachedInputTokens())
                .param("llmCalls", result.llmCalls())
                .param("latencyMs", result.latencyMs())
                .param("mode", mode.name())
                .param("review", json.writeValueAsString(review))
                .param("costUsd", result.costUsd())
                .param("costByModel", json.writeValueAsString(result.costByModel()))
                .update();
    }

    /**
     * Analysis graph checkpoints (ADR 0008) belong to a run in progress; the lifecycle deletes them when the run ends,
     * in the same transaction. A failed attempt keeps them so the next attempt resumes.
     */
    private void deleteCheckpoints(String caseId, int upToRun) {
        jdbc.sql("delete from graph_checkpoint where case_id = :caseId and run <= :run")
                .param("caseId", UUID.fromString(caseId))
                .param("run", upToRun)
                .update();
    }

    private void event(String caseId, String type, CaseStage from, CaseStage to, String actor, Map<String, ?> detail) {
        jdbc.sql("""
                        insert into case_event (case_id, type, from_stage, to_stage, actor, detail)
                        values (:caseId, :type, :from, :to, :actor, cast(:detail as jsonb))
                        """)
                .param("caseId", UUID.fromString(caseId))
                .param("type", type)
                .param("from", from == null ? null : from.name())
                .param("to", to == null ? null : to.name())
                .param("actor", actor)
                .param("detail", json.writeValueAsString(detail))
                .update();
    }

    private static Map<String, Object> alertDetail(Alert alert) {
        return Map.of("alertId", alert.alertId(), "rule", alert.rule().name(), "severity", alert.severity().name());
    }

    private static Map<String, Object> payload(FraudCase c, Map<String, ?> extra) {
        return Map.of("caseId", c.caseId(), "tenantId", c.tenantId(), "accountId", c.accountId(),
                "customerId", c.customerId(), "priority", c.priority().name(), "detail", extra);
    }

    static FraudCase map(ResultSet rs) throws SQLException {
        String decision = rs.getString("decision");
        return new FraudCase(
                rs.getString("case_id"), rs.getString("tenant_id"), rs.getString("account_id"),
                rs.getString("customer_id"), CaseStage.valueOf(rs.getString("stage")),
                Severity.valueOf(rs.getString("priority")), rs.getInt("attempts"), rs.getInt("analysis_run"),
                rs.getString("analyst_note"), rs.getInt("version"), instant(rs.getTimestamp("opened_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("closed_at")),
                decision == null ? null : Decision.valueOf(decision), rs.getString("decided_by"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** Postgres reports lock_timeout as SQLSTATE 55P03; Spring 7 leaves it uncategorised, so check the state. */
    static boolean isLockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** @param budgetExhausted true when nothing was claimed because the hourly budget is used up */
    public record ClaimBatch(List<CaseClaim> claims, boolean budgetExhausted) {
    }

    public enum Completion { READY_FOR_DECISION, SUPERSEDED, LOST_CLAIM }
}
