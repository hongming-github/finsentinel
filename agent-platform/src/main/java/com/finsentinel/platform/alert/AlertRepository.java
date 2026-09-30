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
import java.util.List;
import java.util.UUID;

@Repository
public class AlertRepository {

    private final JdbcClient jdbc;

    AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the alert unless one with the same id exists.
     *
     * @return true if this call stored it, false if it was a redelivery
     */
    public boolean insertIfAbsent(Alert alert) {
        int rows = jdbc.sql("""
                        insert into alert (alert_id, tenant_id, account_id, customer_id, rule, severity, reason,
                                           txn_ids, event_time, detected_at)
                        values (:alertId, :tenantId, :accountId, :customerId, :rule, :severity, :reason,
                                :txnIds, :eventTime, :detectedAt)
                        on conflict (alert_id) do nothing
                        """)
                .param("alertId", UUID.fromString(alert.alertId()))
                .param("tenantId", alert.tenantId())
                .param("accountId", alert.accountId())
                .param("customerId", alert.customerId())
                .param("rule", alert.rule().name())
                .param("severity", alert.severity().name())
                .param("reason", alert.reason())
                .param("txnIds", alert.txnIds().toArray(String[]::new))
                .param("eventTime", Timestamp.from(alert.eventTime()))
                .param("detectedAt", Timestamp.from(alert.detectedAt()))
                .update();
        return rows == 1;
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

    private static StoredAlert map(ResultSet rs) throws SQLException {
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
        return new StoredAlert(alert, AlertStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("received_at").toInstant());
    }
}
