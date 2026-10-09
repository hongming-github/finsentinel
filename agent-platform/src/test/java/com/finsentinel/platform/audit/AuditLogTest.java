package com.finsentinel.platform.audit;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestTokens;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.security.Tenancy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PlatformTest
class AuditLogTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AuditLog audit;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CaseIntake intake;
    @Autowired
    PostgreSQLContainer postgres;

    private final String tenant = uniqueTenant();

    @Test
    void readsAndRefusalsAreRecordedWithTheVerifiedCaller() throws Exception {
        String caseId = intake.accept(alert(tenant, "acc-1", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t-1"),
                Instant.now())).orElseThrow();
        String victim = uniqueTenant();

        mvc.perform(get("/api/tenants/{t}/cases/{id}", tenant, caseId).with(bearer(TestTokens.analyst(tenant))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/cases", victim).with(bearer(TestTokens.analyst(tenant))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tenants/{t}/llm-usage", tenant).with(bearer(TestTokens.analyst(tenant))))
                .andExpect(status().isForbidden());

        assertThat(entries(tenant)).extracting(e -> e.get("action") + " " + e.get("resource") + " " + e.get("outcome")
                        + " " + e.get("username"))
                .containsExactly(
                        "GET /api/tenants/{tenantId}/cases/{caseId} " + caseId + " 200 ana-" + tenant,
                        "GET /api/tenants/" + victim + "/cases null 404 ana-" + tenant,
                        "GET /api/tenants/" + tenant + "/llm-usage null 403 ana-" + tenant);
        assertThat(audit.verify(tenant).intact()).isTrue();
    }

    @Test
    void anUnverifiedTokenLeavesNoIdentityInTheLog() throws Exception {
        String forged = TestTokens.sign(TestTokens.claims(tenant, "mallory", "analyst").audience("someone-else").build());
        String path = "/api/tenants/" + tenant + "/cases";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(bearer(forged))).andExpect(status().isUnauthorized());

        List<Map<String, Object>> refused = Tenancy.callAsSystem(() -> jdbc.sql("""
                        select tenant_id, username, outcome from audit_log
                        where chain = '-' and action = :action order by audit_id desc limit 2
                        """).param("action", "GET " + path).query().listOfRows());
        assertThat(refused).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("tenant_id")).isNull();
            assertThat(row.get("username")).as("claims of a token that failed verification").isNull();
            assertThat(row.get("outcome")).isEqualTo("401");
        });
    }

    @Test
    void supervisorsReadTheirOwnBanksLogOnly() throws Exception {
        String other = uniqueTenant();
        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(TestTokens.analyst(tenant))));
        mvc.perform(get("/api/tenants/{t}/cases", other).with(bearer(TestTokens.analyst(other))));

        mvc.perform(get("/api/tenants/{t}/audit", tenant).with(bearer(TestTokens.supervisor(tenant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("GET /api/tenants/{tenantId}/cases"))
                .andExpect(jsonPath("$[?(@.username == 'ana-" + other + "')]").isEmpty());
        mvc.perform(get("/api/tenants/{t}/audit/verify", tenant).with(bearer(TestTokens.supervisor(tenant))))
                .andExpect(jsonPath("$.intact").value(true));
        mvc.perform(get("/api/tenants/{t}/audit", tenant).with(bearer(TestTokens.analyst(tenant))))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePlatformCannotChangeOrDeleteEntries() {
        audit.record(entry(tenant, "GET /x"));

        assertThatThrownBy(() -> Tenancy.callAsSystem(() -> jdbc.sql("update audit_log set outcome = '500' where chain = :c")
                .param("c", tenant).update())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> Tenancy.callAsSystem(() -> jdbc.sql("delete from audit_log where chain = :c")
                .param("c", tenant).update())).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void theOwnerIsStoppedByTheTriggerAndAnEditBehindItsBackBreaksTheChain() throws Exception {
        for (int i = 0; i < 3; i++) {
            audit.record(entry(tenant, "GET /x/" + i));
        }
        long second = Tenancy.callAsSystem(() -> jdbc.sql(
                "select audit_id from audit_log where chain = :c order by audit_id offset 1 limit 1")
                .param("c", tenant).query(Long.class).single());

        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement sql = owner.createStatement()) {
            assertThatThrownBy(() -> sql.executeUpdate("update audit_log set outcome = '500' where audit_id = " + second))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            // Someone with the owner's rights who also disables the trigger:
            sql.execute("alter table audit_log disable trigger audit_log_append_only");
            try {
                sql.executeUpdate("update audit_log set outcome = '500' where audit_id = " + second);
            } finally {
                sql.execute("alter table audit_log enable trigger audit_log_append_only");
            }
        }

        assertThat(audit.verify(tenant).firstBrokenId()).isEqualTo(second);
    }

    @Test
    void concurrentAppendsKeepOneUnbrokenChain() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            IntStream.range(0, 40).forEach(i -> pool.submit(() -> audit.record(entry(tenant, "GET /x/" + i))));
        }

        AuditLog.Verification verification = audit.verify(tenant);
        assertThat(verification.entries()).isEqualTo(40);
        assertThat(verification.intact()).isTrue();
    }

    private static AuditEntry entry(String tenant, String action) {
        return new AuditEntry(tenant, "sub-1", "ana", "analyst-cli", action, null, "200", Map.of("k", "v"));
    }

    private List<Map<String, Object>> entries(String tenant) {
        return Tenancy.callAsSystem(() -> jdbc.sql("""
                        select action, resource, outcome, username from audit_log where chain = :c order by audit_id
                        """).param("c", tenant).query().listOfRows());
    }
}
