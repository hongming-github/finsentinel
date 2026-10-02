package com.finsentinel.platform.cases;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox. {@link #append} runs inside the transaction that changes the case, so the message exists if
 * and only if the change committed; {@link OutboxRelay} publishes it afterwards.
 */
@Repository
class OutboxRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void append(String aggregateId, String type, Map<String, ?> payload) {
        jdbc.sql("insert into outbox (aggregate_id, type, payload) values (:id, :type, cast(:payload as jsonb))")
                .param("id", UUID.fromString(aggregateId))
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }

    /** Locks a batch of unpublished messages; other relay instances skip them. Call inside a transaction. */
    List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                        select event_id, aggregate_id, type, payload::text as payload, created_at from outbox
                        where published_at is null
                        order by event_id
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(rs.getLong("event_id"), rs.getString("aggregate_id"),
                        rs.getString("type"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    void markPublished(List<Long> eventIds) {
        jdbc.sql("update outbox set published_at = now() where event_id = any(:ids)")
                .param("ids", eventIds.toArray(Long[]::new))
                .update();
    }

    record OutboxMessage(long eventId, String aggregateId, String type, String payload, java.time.Instant createdAt) {
    }
}
