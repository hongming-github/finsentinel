package com.finsentinel.platform.investigation;

import com.finsentinel.platform.alert.AlertRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The investigation work queue lives in the alert table itself: claim with {@code FOR UPDATE SKIP LOCKED}
 * so several platform instances can poll without handing out the same alert twice.
 */
@Repository
public class InvestigationRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    InvestigationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Claims up to {@code limit} alerts, HIGH severity first, then oldest. Also takes back claims that
     * timed out (worker crashed or hung) as long as they have attempts left.
     */
    List<Claim> claim(int limit, Duration claimTimeout, int maxAttempts) {
        return jdbc.sql("""
                        update alert
                        set status = 'INVESTIGATING', claimed_at = clock_timestamp(), attempts = attempts + 1
                        where alert_id in (
                            select alert_id from alert
                            where attempts < :maxAttempts
                              and (status = 'NEW'
                                   or (status = 'INVESTIGATING' and claimed_at < now() - make_interval(secs => :timeoutSecs)))
                            order by case severity when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end, detected_at
                            limit :limit
                            for update skip locked)
                        returning *
                        """)
                .param("maxAttempts", maxAttempts)
                .param("timeoutSecs", claimTimeout.toSeconds())
                .param("limit", limit)
                .query((rs, n) -> new Claim(AlertRepository.map(rs), rs.getTimestamp("claimed_at").toInstant()))
                .list();
    }

    /**
     * Writes the report and marks the alert REPORTED, but only if this worker still holds the claim.
     *
     * @return false if the claim was lost (timed out and re-claimed); nothing is written in that case
     */
    @Transactional
    public boolean complete(Claim claim, InvestigationResult result) {
        int updated = jdbc.sql("""
                        update alert set status = 'REPORTED'
                        where alert_id = :alertId and status = 'INVESTIGATING' and claimed_at = :claimedAt
                        """)
                .param("alertId", UUID.fromString(claim.alert().alert().alertId()))
                .param("claimedAt", Timestamp.from(claim.claimedAt()))
                .update();
        if (updated == 0) {
            return false;
        }
        CaseReport report = result.report();
        jdbc.sql("""
                        insert into case_report (alert_id, tenant_id, disposition, risk_score, report, tool_calls,
                                                 repaired_issues, model, input_tokens, output_tokens,
                                                 cached_input_tokens, llm_calls, latency_ms)
                        values (:alertId, :tenantId, :disposition, :riskScore, cast(:report as jsonb),
                                cast(:toolCalls as jsonb), :repaired, :model, :inputTokens, :outputTokens,
                                :cachedInputTokens, :llmCalls, :latencyMs)
                        on conflict (alert_id) do nothing
                        """)
                .param("alertId", UUID.fromString(claim.alert().alert().alertId()))
                .param("tenantId", claim.alert().alert().tenantId())
                .param("disposition", report.disposition().name())
                .param("riskScore", report.riskScore())
                .param("report", json.writeValueAsString(report))
                .param("toolCalls", json.writeValueAsString(result.toolCalls()))
                .param("repaired", result.repairedIssues().toArray(String[]::new))
                .param("model", result.model())
                .param("inputTokens", result.inputTokens())
                .param("outputTokens", result.outputTokens())
                .param("cachedInputTokens", result.cachedInputTokens())
                .param("llmCalls", result.llmCalls())
                .param("latencyMs", result.latencyMs())
                .update();
        return true;
    }

    /** Releases a failed claim: back to NEW for another attempt, or FAILED when attempts are used up. */
    void fail(Claim claim, int maxAttempts) {
        jdbc.sql("""
                        update alert
                        set status = case when attempts >= :maxAttempts then 'FAILED' else 'NEW' end,
                            claimed_at = null
                        where alert_id = :alertId and status = 'INVESTIGATING' and claimed_at = :claimedAt
                        """)
                .param("maxAttempts", maxAttempts)
                .param("alertId", UUID.fromString(claim.alert().alert().alertId()))
                .param("claimedAt", Timestamp.from(claim.claimedAt()))
                .update();
    }

    /** Timed-out claims with no attempts left would otherwise sit in INVESTIGATING forever. */
    int failAbandoned(Duration claimTimeout, int maxAttempts) {
        return jdbc.sql("""
                        update alert set status = 'FAILED'
                        where status = 'INVESTIGATING' and attempts >= :maxAttempts
                          and claimed_at < now() - make_interval(secs => :timeoutSecs)
                        """)
                .param("maxAttempts", maxAttempts)
                .param("timeoutSecs", claimTimeout.toSeconds())
                .update();
    }

    /** Investigations started in the last hour: finished reports plus claims still in flight. */
    int startedInLastHour() {
        return jdbc.sql("""
                        select (select count(*) from case_report where created_at > now() - interval '1 hour')
                             + (select count(*) from alert where status = 'INVESTIGATING'
                                  and claimed_at > now() - interval '1 hour')
                        """)
                .query(Integer.class)
                .single();
    }

    public Optional<CaseReportView> findReport(String tenantId, String alertId) {
        return jdbc.sql("select * from case_report where tenant_id = :tenantId and alert_id = :alertId")
                .param("tenantId", tenantId)
                .param("alertId", UUID.fromString(alertId))
                .query((rs, n) -> new CaseReportView(
                        rs.getString("alert_id"),
                        json.readValue(rs.getString("report"), CaseReport.class),
                        json.readValue(rs.getString("tool_calls"), new TypeReference<List<ToolCall>>() {
                        }),
                        List.of((String[]) rs.getArray("repaired_issues").getArray()),
                        rs.getString("model"),
                        (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"),
                        (Integer) rs.getObject("cached_input_tokens"),
                        rs.getInt("llm_calls"),
                        rs.getLong("latency_ms"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public record CaseReportView(String alertId, CaseReport report, List<ToolCall> toolCalls,
                                 List<String> repairedIssues, String model, Integer inputTokens,
                                 Integer outputTokens, Integer cachedInputTokens, int llmCalls, long latencyMs,
                                 Instant createdAt) {
    }
}
