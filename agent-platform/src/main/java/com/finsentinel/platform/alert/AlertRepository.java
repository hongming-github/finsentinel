package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AlertRepository {

    private final JdbcClient jdbc;

    AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the alert into the given case unless an alert with the same id exists.
     *
     * @param traceParent the trace the alert arrived in, or null
     * @return true if this call stored it, false if it was a redelivery
     */
    public boolean insertIfAbsent(Alert alert, String caseId, String traceParent) {
        int rows = jdbc.sql("""
                        insert into alert (alert_id, case_id, tenant_id, account_id, customer_id, rule, severity, reason,
                                           txn_ids, event_time, detected_at, trace_parent)
                        values (:alertId, :caseId, :tenantId, :accountId, :customerId, :rule, :severity, :reason,
                                :txnIds, :eventTime, :detectedAt, :traceParent)
                        on conflict (alert_id) do nothing
                        """)
                .param("alertId", UUID.fromString(alert.alertId()))
                .param("caseId", UUID.fromString(caseId))
                .param("tenantId", alert.tenantId())
                .param("accountId", alert.accountId())
                .param("customerId", alert.customerId())
                .param("rule", alert.rule().name())
                .param("severity", alert.severity().name())
                .param("reason", alert.reason())
                .param("txnIds", alert.txnIds().toArray(String[]::new))
                .param("eventTime", Timestamp.from(alert.eventTime()))
                .param("detectedAt", Timestamp.from(alert.detectedAt()))
                .param("traceParent", traceParent)
                .update();
        return rows == 1;
    }

    public boolean exists(String alertId) {
        return jdbc.sql("select exists (select 1 from alert where alert_id = :alertId)")
                .param("alertId", UUID.fromString(alertId))
                .query(Boolean.class)
                .single();
    }

    public List<StoredAlert> findRecent(String tenantId, int limit) {
        return jdbc.sql("""
                        select * from alert
                        where tenant_id = :tenantId
                        order by detected_at desc
                        limit :limit
                        """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<StoredAlert> findById(String tenantId, String alertId) {
        return jdbc.sql("select * from alert where tenant_id = :tenantId and alert_id = :alertId")
                .param("tenantId", tenantId)
                .param("alertId", UUID.fromString(alertId))
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The alerts filed under a case, oldest first. */
    public List<StoredAlert> findByCase(String tenantId, String caseId) {
        return jdbc.sql("select * from alert where tenant_id = :tenantId and case_id = :caseId order by event_time")
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Alerts on the same account since {@code since} that belong to other cases, newest first, with their outcome. */
    public List<PriorAlert> findPrior(String tenantId, String accountId, Instant since, String excludeCaseId) {
        return jdbc.sql("""
                        select a.*, c.stage as case_stage, c.decision as case_decision
                        from alert a join fraud_case c using (case_id)
                        where a.tenant_id = :tenantId and a.account_id = :accountId
                          and a.event_time >= :since and a.case_id <> :exclude
                        order by a.event_time desc
                        limit 50
                        """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("since", Timestamp.from(since))
                .param("exclude", UUID.fromString(excludeCaseId))
                .query((rs, n) -> new PriorAlert(map(rs).alert(), rs.getString("case_stage"), rs.getString("case_decision")))
                .list();
    }

    public static StoredAlert map(ResultSet rs) throws SQLException {
        Array txnIds = rs.getArray("txn_ids");
        Alert alert = new Alert(
                rs.getString("alert_id"),
                rs.getString("tenant_id"),
                rs.getString("account_id"),
                rs.getString("customer_id"),
                AlertRule.valueOf(rs.getString("rule")),
                Severity.valueOf(rs.getString("severity")),
                rs.getString("reason"),
                List.of((String[]) txnIds.getArray()),
                rs.getTimestamp("event_time").toInstant(),
                rs.getTimestamp("detected_at").toInstant());
        return new StoredAlert(alert, rs.getString("case_id"), rs.getTimestamp("received_at").toInstant());
    }
}
