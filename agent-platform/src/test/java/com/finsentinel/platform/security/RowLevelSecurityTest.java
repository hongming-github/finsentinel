package com.finsentinel.platform.security;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres enforces the tenant underneath the application (ADR 0015): these queries deliberately leave out the
 * tenant filter, or pass the wrong tenant, and still see only the scope's rows.
 */
@PlatformTest
class RowLevelSecurityTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseRepository cases;
    @Autowired
    TransactionRepository transactions;

    private final String bankA = uniqueTenant();
    private final String bankB = uniqueTenant();
    private String caseA;
    private String caseB;

    @BeforeEach
    void twoBanks() {
        Instant now = Instant.now();
        transactions.insertAll(List.of(txn(bankA, "acc-a", "10.00", Channel.CARD_PRESENT, "SG", "m-1", now),
                txn(bankB, "acc-b", "20.00", Channel.CARD_PRESENT, "SG", "m-1", now)));
        caseA = intake.accept(alert(bankA, "acc-a", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t-a"), now)).orElseThrow();
        caseB = intake.accept(alert(bankB, "acc-b", AlertRule.GEO_JUMP, Severity.HIGH, List.of("t-b"), now)).orElseThrow();
    }

    @Test
    void aQueryWithoutATenantFilterSeesOnlyTheScopesRows() {
        for (String table : List.of("txn", "alert", "fraud_case")) {
            List<String> tenants = Tenancy.call(bankA, () -> jdbc.sql("select distinct tenant_id from " + table)
                    .query(String.class).list());
            assertThat(tenants).as(table).containsExactly(bankA);
        }
        List<UUID> events = Tenancy.call(bankA, () -> jdbc.sql("select distinct case_id from case_event")
                .query(UUID.class).list());
        assertThat(events).doesNotContain(UUID.fromString(caseB));
    }

    @Test
    void theWrongTenantPassedToARepositoryFindsNothing() {
        // A bug that hands bank B's id to a query running for bank A: the application filter alone would match.
        assertThat(Tenancy.call(bankA, () -> cases.find(bankB, caseB))).isEmpty();
        assertThat(Tenancy.call(bankB, () -> cases.find(bankB, caseB))).isPresent();
    }

    @Test
    void noRowForAnotherTenantCanBeWritten() {
        assertThatThrownBy(() -> Tenancy.run(bankA, () -> transactions.insertAll(List.of(
                txn(bankB, "acc-b", "99.00", Channel.TRANSFER, "SG", "payee-x", Instant.now())))))
                .rootCause().hasMessageContaining("violates row-level security policy");
        assertThat(Tenancy.call(bankA, () -> jdbc.sql("update fraud_case set analyst_note = 'x' where case_id = :id")
                .param("id", UUID.fromString(caseB)).update())).as("another bank's case is invisible to update").isZero();
    }

    @Test
    void withoutAScopeTenantTablesFail() {
        assertThatThrownBy(() -> Tenancy.within(null, () -> jdbc.sql("select count(*) from fraud_case")
                .query(Long.class).single()))
                .rootCause().hasMessageContaining("no tenant set for this database session");
        assertThat(Tenancy.within(null, () -> jdbc.sql("select count(*) from kb_document").query(Long.class).single()))
                .as("the public regulatory corpus is global").isNotNull();
    }

    /** The case assistant's tables (ADR 0028): tenant rows, fixed to a case of their bank, turns append-only. */
    @Test
    void conversationsBelongToACaseOfTheirBankAndTheirTurnsCannotBeRewritten() {
        UUID conversation = UUID.randomUUID();
        Tenancy.run(bankA, () -> insertConversation(conversation, bankA, caseA));
        Tenancy.run(bankA, () -> jdbc.sql("""
                        insert into conversation_turn (conversation_id, seq, question, standalone_question, answer,
                            cited_txn_ids, cited_chunk_ids, untrusted_instructions, case_version, llm_calls, cost_usd,
                            latency_ms, created_at)
                        values (:id, 1, 'q', 'q', 'a', '[]', '[]', '[]', 1, 1, 0, 1, now())
                        """).param("id", conversation).update());

        assertThat(Tenancy.call(bankB, () -> jdbc.sql("select count(*) from conversation_turn")
                .query(Long.class).single())).as("another bank sees no turn of it").isZero();
        assertThatThrownBy(() -> Tenancy.run(bankA, () -> insertConversation(UUID.randomUUID(), bankA, caseB)))
                .as("bank A cannot open a conversation on bank B's case").rootCause()
                .hasMessageContaining("is not a case of tenant");
        assertThatThrownBy(() -> Tenancy.run(bankA, () -> jdbc.sql(
                        "update conversation set analyst_subject = 'someone-else' where conversation_id = :id")
                .param("id", conversation).update()))
                .as("tenant, case and analyst are fixed").rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> Tenancy.run(bankA, () -> jdbc.sql(
                        "update conversation_turn set answer = 'edited' where conversation_id = :id")
                .param("id", conversation).update()))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> Tenancy.callAsSystem(() -> jdbc.sql(
                        "delete from conversation_turn where conversation_id = :id")
                .param("id", conversation).update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    private void insertConversation(UUID id, String tenant, String caseId) {
        jdbc.sql("""
                        insert into conversation (conversation_id, tenant_id, case_id, analyst_subject, analyst_username,
                                                  created_at, updated_at)
                        values (:id, :tenant, :caseId, 'sub', 'ana', now(), now())
                        """)
                .param("id", id).param("tenant", tenant).param("caseId", UUID.fromString(caseId)).update();
    }

    @Test
    void onlyTheSystemScopeSeesEveryBank() {
        List<String> tenants = Tenancy.callAsSystem(() -> jdbc.sql(
                "select distinct tenant_id from fraud_case where tenant_id in (:a, :b)")
                .param("a", bankA).param("b", bankB).query(String.class).list());

        assertThat(tenants).containsExactlyInAnyOrder(bankA, bankB);
    }

    @Test
    void thePlatformsLoginCannotBypassThePolicies() {
        record Session(String user, boolean bypass, boolean superuser) {
        }
        String query = """
                select current_user as user, r.rolbypassrls as bypass, r.rolsuper as superuser
                from pg_roles r where r.rolname = current_user
                """;
        Session tenant = Tenancy.call(bankA, () -> jdbc.sql(query).query(Session.class).single());
        Session system = Tenancy.callAsSystem(() -> jdbc.sql(query).query(Session.class).single());
        String owner = Tenancy.call(bankA, () -> jdbc.sql("select tableowner from pg_tables where tablename = 'txn'")
                .query(String.class).single());

        assertThat(tenant).isEqualTo(new Session("finsentinel_app", false, false));
        assertThat(system).isEqualTo(new Session("finsentinel_system", true, false));
        assertThat(owner).as("the login does not own the tables (owners bypass RLS)").isNotEqualTo("finsentinel_app");
    }
}
