package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;

/** Helpers shared by the case tests. */
final class CaseTestSupport {

    private CaseTestSupport() {
    }

    /** Other test classes share the database; close their open cases so this test sees only its own queue. */
    static void parkOtherCases(JdbcClient jdbc) {
        jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage <> 'CLOSED'").update();
    }

    /** Skips the pause a failed attempt waits before the next one, as if it had passed. */
    static void makeDue(JdbcClient jdbc, String caseId) {
        jdbc.sql("update fraud_case set next_attempt_at = now() where case_id = cast(:id as uuid)")
                .param("id", caseId).update();
    }

    static Alert alertOn(String tenant, String account, Severity severity) {
        return alert(tenant, account, AlertRule.LARGE_AMOUNT, severity, List.of("t-" + UUID.randomUUID()), Instant.now());
    }

    static Alert alertOn(String tenant, String account, AlertRule rule, Severity severity) {
        return alert(tenant, account, rule, severity, List.of("t-" + UUID.randomUUID()), Instant.now());
    }

    static List<String> eventTypes(CaseRepository cases, String tenant, String caseId) {
        return cases.events(tenant, caseId).stream().map(CaseEvent::type).toList();
    }

    static List<String> outboxTypes(JdbcClient jdbc, String caseId) {
        return jdbc.sql("select type from outbox where aggregate_id = cast(:id as uuid) order by event_id")
                .param("id", caseId).query(String.class).list();
    }
}
