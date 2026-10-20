package com.finsentinel.platform.assistant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations and their turns (ADR 0028). Every read names the tenant and the analyst, on top of row-level security:
 * a conversation is found only by the analyst who started it, in their own bank. No transaction is held while a model
 * answers: a turn is appended afterwards, and only if the conversation has not moved on in the meantime.
 */
@Repository
class ConversationRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ConversationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A new conversation on a case of the tenant; the database refuses a case of another bank. */
    Conversation create(String tenantId, String caseId, String analystSubject, String analystUsername) {
        return jdbc.sql("""
                        insert into conversation (conversation_id, tenant_id, case_id, analyst_subject, analyst_username,
                                                  created_at, updated_at)
                        values (:id, :tenantId, :caseId, :subject, :username, now(), now())
                        returning *
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("caseId", UUID.fromString(caseId))
                .param("subject", analystSubject)
                .param("username", analystUsername)
                .query(ConversationRepository::conversation)
                .single();
    }

    Optional<Conversation> find(String tenantId, String analystSubject, String conversationId) {
        return jdbc.sql("""
                        select * from conversation
                        where tenant_id = :tenantId and analyst_subject = :subject and conversation_id = :id
                        """)
                .param("tenantId", tenantId)
                .param("subject", analystSubject)
                .param("id", UUID.fromString(conversationId))
                .query(ConversationRepository::conversation)
                .optional();
    }

    /** The analyst's conversations on the case, newest first. */
    List<Conversation> list(String tenantId, String analystSubject, String caseId, int limit) {
        return jdbc.sql("""
                        select * from conversation
                        where tenant_id = :tenantId and analyst_subject = :subject and case_id = :caseId
                        order by created_at desc limit :limit
                        """)
                .param("tenantId", tenantId)
                .param("subject", analystSubject)
                .param("caseId", UUID.fromString(caseId))
                .param("limit", limit)
                .query(ConversationRepository::conversation)
                .list();
    }

    /** Oldest first. Only for a conversation already found for its tenant and analyst. */
    List<ConversationTurn> turns(String tenantId, String conversationId) {
        return jdbc.sql("""
                        select t.* from conversation_turn t join conversation c using (conversation_id)
                        where c.tenant_id = :tenantId and t.conversation_id = :id
                        order by t.seq
                        """)
                .param("tenantId", tenantId)
                .param("id", UUID.fromString(conversationId))
                .query(this::turn)
                .list();
    }

    /**
     * Stores {@code turn} as the next turn if the conversation still has {@code turn.seq() - 1} turns: false when
     * another request (another tab, another instance) stored a turn first. The counter's conditional update and the
     * primary key both decide it in the database; nothing waits for a model while holding a lock.
     */
    @Transactional
    boolean append(String tenantId, String conversationId, ConversationTurn turn) {
        int moved = jdbc.sql("""
                        update conversation set turns = :seq, updated_at = now()
                        where tenant_id = :tenantId and conversation_id = :id and turns = :previous
                        """)
                .param("seq", turn.seq())
                .param("previous", turn.seq() - 1)
                .param("tenantId", tenantId)
                .param("id", UUID.fromString(conversationId))
                .update();
        if (moved == 0) {
            return false;
        }
        jdbc.sql("""
                        insert into conversation_turn (conversation_id, seq, question, standalone_question,
                            search_query, answer, cited_txn_ids, cited_chunk_ids, untrusted_instructions,
                            case_version, model, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens,
                            llm_calls, cost_usd, latency_ms, created_at)
                        values (:id, :seq, :question, :standalone, :searchQuery, :answer, cast(:txns as jsonb),
                            cast(:chunks as jsonb), cast(:untrusted as jsonb), :caseVersion, :model, :inputTokens,
                            :outputTokens, :cachedInputTokens, :cacheWriteTokens, :llmCalls, :costUsd, :latencyMs, now())
                        """)
                .param("id", UUID.fromString(conversationId))
                .param("seq", turn.seq())
                .param("question", turn.question())
                .param("standalone", turn.standaloneQuestion())
                .param("searchQuery", turn.searchQuery())
                .param("answer", turn.answer())
                .param("txns", json.writeValueAsString(turn.citedTxnIds()))
                .param("chunks", json.writeValueAsString(turn.citedChunkIds()))
                .param("untrusted", json.writeValueAsString(turn.untrustedInstructions()))
                .param("caseVersion", turn.caseVersion())
                .param("model", turn.model())
                .param("inputTokens", turn.inputTokens())
                .param("outputTokens", turn.outputTokens())
                .param("cachedInputTokens", turn.cachedInputTokens())
                .param("cacheWriteTokens", turn.cacheWriteTokens())
                .param("llmCalls", turn.llmCalls())
                .param("costUsd", turn.costUsd())
                .param("latencyMs", turn.latencyMs())
                .update();
        return true;
    }

    /**
     * Replaces the summary with one that covers turns 1..{@code through}, if nobody folded since it was read
     * ({@code previousThrough}); false otherwise, and the other fold stands.
     */
    boolean fold(String tenantId, String conversationId, int previousThrough, int through, String summary) {
        return jdbc.sql("""
                        update conversation set summary = :summary, summary_through = :through, updated_at = now()
                        where tenant_id = :tenantId and conversation_id = :id and summary_through = :previous
                        """)
                .param("summary", summary)
                .param("through", through)
                .param("tenantId", tenantId)
                .param("id", UUID.fromString(conversationId))
                .param("previous", previousThrough)
                .update() == 1;
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getString("conversation_id"), rs.getString("tenant_id"), rs.getString("case_id"),
                rs.getString("analyst_subject"), rs.getString("analyst_username"), rs.getInt("turns"),
                rs.getString("summary"), rs.getInt("summary_through"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ConversationTurn turn(ResultSet rs, int row) throws SQLException {
        return new ConversationTurn(rs.getInt("seq"), rs.getString("question"), rs.getString("standalone_question"),
                rs.getString("search_query"), rs.getString("answer"),
                json.readValue(rs.getString("cited_txn_ids"), STRINGS),
                json.readValue(rs.getString("cited_chunk_ids"), STRINGS),
                json.readValue(rs.getString("untrusted_instructions"), STRINGS), rs.getInt("case_version"),
                rs.getString("model"), (Integer) rs.getObject("input_tokens"), (Integer) rs.getObject("output_tokens"),
                (Integer) rs.getObject("cached_input_tokens"), (Integer) rs.getObject("cache_write_tokens"),
                rs.getInt("llm_calls"), rs.getBigDecimal("cost_usd"), rs.getLong("latency_ms"),
                rs.getTimestamp("created_at").toInstant());
    }
}
