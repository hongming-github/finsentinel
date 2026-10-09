package com.finsentinel.platform.audit;

import com.finsentinel.platform.security.Tenancy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The append-only access log (ADR 0016). Each bank has its own hash chain: an entry's hash covers the previous entry of
 * the chain, so changing, removing or reordering a row breaks every hash after it. Appending locks the chain's head row
 * for the length of one insert; banks never wait for each other.
 *
 * <p>Recording never fails the request it describes: an error is logged and counted
 * ({@code finsentinel.audit.failures}). The trade-off (availability over a request refused for want of an audit
 * row) is stated in the ADR.
 */
@Component
public class AuditLog {

    static final String NO_TENANT_CHAIN = "-";
    private static final String GENESIS = "0".repeat(64);
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Counter failures;

    AuditLog(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.failures = meters.counter("finsentinel.audit.failures");
    }

    public void record(AuditEntry entry) {
        try {
            append(entry);
        } catch (RuntimeException e) {
            failures.increment();
            log.error("Audit entry not written: {} {} {}: {}", entry.action(), entry.resource(), entry.outcome(),
                    e.toString());
        }
    }

    private void append(AuditEntry entry) {
        String chain = entry.chain();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String detail = json.writeValueAsString(entry.detail());
        transactions.executeWithoutResult(status -> {
            jdbc.sql("insert into audit_chain_head (chain, last_hash) values (:chain, :genesis) on conflict do nothing")
                    .param("chain", chain).param("genesis", GENESIS).update();
            String previous = jdbc.sql("select last_hash from audit_chain_head where chain = :chain for update")
                    .param("chain", chain).query(String.class).single();
            String hash = hash(previous, chain, at, entry, detail);
            jdbc.sql("""
                            insert into audit_log (chain, at, tenant_id, subject, username, client_id, action, resource,
                                                   outcome, detail, prev_hash, hash)
                            values (:chain, :at, :tenant, :subject, :username, :clientId, :action, :resource, :outcome,
                                    cast(:detail as jsonb), :previous, :hash)
                            """)
                    .param("chain", chain).param("at", Timestamp.from(at)).param("tenant", entry.tenant())
                    .param("subject", entry.subject()).param("username", entry.username())
                    .param("clientId", entry.clientId()).param("action", entry.action())
                    .param("resource", entry.resource()).param("outcome", entry.outcome()).param("detail", detail)
                    .param("previous", previous).param("hash", hash)
                    .update();
            jdbc.sql("update audit_chain_head set last_hash = :hash where chain = :chain")
                    .param("hash", hash).param("chain", chain).update();
        });
    }

    /** A bank's latest entries, newest first. Runs in the caller's scope: row-level security shows its own bank only. */
    public List<StoredEntry> recent(String tenant, int limit) {
        return jdbc.sql("""
                        select audit_id, at, subject, username, client_id, action, resource, outcome, detail::text as detail
                        from audit_log where tenant_id = :tenant order by audit_id desc limit :limit
                        """)
                .param("tenant", tenant).param("limit", limit)
                .query((rs, n) -> new StoredEntry(rs.getLong("audit_id"), rs.getTimestamp("at").toInstant(),
                        rs.getString("subject"), rs.getString("username"), rs.getString("client_id"),
                        rs.getString("action"), rs.getString("resource"), rs.getString("outcome"),
                        readDetail(rs.getString("detail"))))
                .list();
    }

    public record StoredEntry(long id, Instant at, String subject, String username, String clientId, String action,
                              String resource, String outcome, Map<String, String> detail) {
    }

    /**
     * Recomputes a chain from its first entry. Reads every entry of the chain, so it runs in the system scope (the
     * no-tenant chain has no bank to scope to).
     *
     * @return the entries checked and the first one whose stored hash or link does not match, if any
     */
    public Verification verify(String chain) {
        return Tenancy.callAsSystem(() -> {
            List<Row> rows = jdbc.sql("""
                            select audit_id, at, tenant_id, subject, username, client_id, action, resource, outcome,
                                   detail::text as detail, prev_hash, hash
                            from audit_log where chain = :chain order by audit_id
                            """)
                    .param("chain", chain)
                    .query((rs, n) -> new Row(rs.getLong("audit_id"), rs.getTimestamp("at").toInstant(),
                            new AuditEntry(rs.getString("tenant_id"), rs.getString("subject"), rs.getString("username"),
                                    rs.getString("client_id"), rs.getString("action"), rs.getString("resource"),
                                    rs.getString("outcome"), readDetail(rs.getString("detail"))),
                            rs.getString("prev_hash"), rs.getString("hash")))
                    .list();
            String expectedPrevious = GENESIS;
            for (Row row : rows) {
                String recomputed = hash(row.prevHash(), chain, row.at(), row.entry(),
                        json.writeValueAsString(row.entry().detail()));
                if (!row.prevHash().equals(expectedPrevious) || !row.hash().equals(recomputed)) {
                    return new Verification(chain, rows.size(), row.id(), false);
                }
                expectedPrevious = row.hash();
            }
            return new Verification(chain, rows.size(), null, true);
        });
    }

    /** @param firstBrokenId the first entry whose hash or link does not match; null when the chain is intact */
    public record Verification(String chain, int entries, Long firstBrokenId, boolean intact) {
    }

    private record Row(long id, Instant at, AuditEntry entry, String prevHash, String hash) {
    }

    private Map<String, String> readDetail(String detail) {
        return new TreeMap<>(json.readValue(detail, new TypeReference<Map<String, String>>() {
        }));
    }

    /** Over every stored field, with a separator no field contains; the detail as JSON with sorted keys. */
    private static String hash(String previous, String chain, Instant at, AuditEntry e, String detail) {
        String canonical = String.join("\u001f", previous, chain, at.toString(), String.valueOf(e.tenant()),
                String.valueOf(e.subject()), String.valueOf(e.username()), String.valueOf(e.clientId()), e.action(),
                String.valueOf(e.resource()), e.outcome(), detail);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
