package com.finsentinel.platform.transaction;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every method takes tenant and account; there is deliberately no unscoped read. */
@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    TransactionRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Batch insert; replays of the same transaction are ignored. */
    public void insertAll(List<Transaction> txns) {
        jdbcTemplate.batchUpdate("""
                        insert into txn (tenant_id, txn_id, account_id, customer_id, amount, currency, merchant_id,
                                         merchant_category, channel, country, event_time)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (tenant_id, txn_id) do nothing
                        """, txns, txns.size(), (ps, t) -> {
                    ps.setString(1, t.tenantId());
                    ps.setString(2, t.txnId());
                    ps.setString(3, t.accountId());
                    ps.setString(4, t.customerId());
                    ps.setBigDecimal(5, t.amount());
                    ps.setString(6, t.currency());
                    ps.setString(7, t.merchantId());
                    ps.setString(8, t.merchantCategory());
                    ps.setString(9, t.channel().name());
                    ps.setString(10, t.country());
                    ps.setTimestamp(11, Timestamp.from(t.eventTime()));
                });
    }

    public List<Transaction> findByIds(String tenantId, String accountId, Collection<String> txnIds) {
        if (txnIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select * from txn
                        where tenant_id = :tenantId and account_id = :accountId and txn_id in (:txnIds)
                        order by event_time
                        """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("txnIds", txnIds)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<Transaction> findRecent(String tenantId, String accountId, Instant since, int limit) {
        return jdbc.sql("""
                        select * from txn
                        where tenant_id = :tenantId and account_id = :accountId and event_time >= :since
                        order by event_time desc
                        limit :limit
                        """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Baseline over {@code [since, until)}, leaving out {@code excludeTxnIds}. Callers pass the alert's own
     * transactions as exclusions so the "normal" profile is not contaminated by the activity under review.
     */
    public AccountProfile profile(String tenantId, String accountId, Instant since, Instant until,
                                  Collection<String> excludeTxnIds) {
        Map<String, Object> params = Map.of("tenantId", tenantId, "accountId", accountId,
                "since", Timestamp.from(since), "until", Timestamp.from(until),
                // "not in ()" is invalid SQL; a sentinel keeps the query shape fixed.
                "exclude", excludeTxnIds.isEmpty() ? List.of("") : excludeTxnIds,
                "cardPresent", Channel.CARD_PRESENT.name());
        String scope = """
                where tenant_id = :tenantId and account_id = :accountId
                  and event_time >= :since and event_time < :until and txn_id not in (:exclude)
                """;

        AccountProfile.AmountStats amounts = jdbc.sql("""
                        select count(*) as n,
                               coalesce(avg(amount), 0) as mean,
                               coalesce(percentile_cont(0.5) within group (order by amount), 0) as median,
                               coalesce(percentile_cont(0.95) within group (order by amount), 0) as p95,
                               coalesce(max(amount), 0) as max,
                               min(event_time) as first_seen,
                               max(event_time) as last_seen
                        from txn
                        """ + scope)
                .params(params)
                .query((rs, n) -> new AccountProfile.AmountStats(
                        rs.getLong("n"),
                        scale(rs.getBigDecimal("mean")),
                        scale(rs.getBigDecimal("median")),
                        scale(rs.getBigDecimal("p95")),
                        scale(rs.getBigDecimal("max")),
                        instant(rs.getTimestamp("first_seen")),
                        instant(rs.getTimestamp("last_seen"))))
                .single();

        Map<String, Long> channels = counts("select channel as k, count(*) as c from txn " + scope
                + "group by channel order by c desc", params);
        Map<String, Long> cardPresentCountries = counts("select country as k, count(*) as c from txn " + scope
                + "and channel = :cardPresent group by country order by c desc", params);

        return new AccountProfile(amounts, channels, cardPresentCountries);
    }

    /**
     * How often the account dealt with each counterparty before {@code until}, not counting
     * {@code excludeTxnIds}. Counterparties never seen before are absent from the result.
     */
    public Map<String, CounterpartyStats> counterpartyHistory(String tenantId, String accountId,
                                                             Collection<String> counterparties, Instant until,
                                                             Collection<String> excludeTxnIds) {
        Map<String, CounterpartyStats> out = new LinkedHashMap<>();
        if (counterparties.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select merchant_id, count(*) as n, min(event_time) as first_seen, max(amount) as max_amount
                        from txn
                        where tenant_id = :tenantId and account_id = :accountId and merchant_id in (:counterparties)
                          and event_time < :until and txn_id not in (:exclude)
                        group by merchant_id
                        """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("counterparties", counterparties)
                .param("until", Timestamp.from(until))
                .param("exclude", excludeTxnIds.isEmpty() ? List.of("") : excludeTxnIds)
                .query(rs -> {
                    out.put(rs.getString("merchant_id"), new CounterpartyStats(rs.getLong("n"),
                            rs.getTimestamp("first_seen").toInstant(), scale(rs.getBigDecimal("max_amount"))));
                });
        return out;
    }

    public record CounterpartyStats(long priorCount, Instant firstSeen, BigDecimal maxAmount) {
    }

    private Map<String, Long> counts(String sql, Map<String, Object> params) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql(sql).params(params).query(rs -> {
            out.put(rs.getString("k"), rs.getLong("c"));
        });
        return out;
    }

    private static Transaction map(ResultSet rs) throws SQLException {
        return new Transaction(
                rs.getString("txn_id"),
                rs.getString("tenant_id"),
                rs.getString("account_id"),
                rs.getString("customer_id"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("merchant_id"),
                rs.getString("merchant_category"),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("country"),
                rs.getTimestamp("event_time").toInstant());
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
