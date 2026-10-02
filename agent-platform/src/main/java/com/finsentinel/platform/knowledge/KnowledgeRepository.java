package com.finsentinel.platform.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

@Repository
class KnowledgeRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    KnowledgeRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    Map<String, String> documentHashes() {
        return jdbc.sql("select doc_id, sha256 from kb_document")
                .query(rs -> {
                    Map<String, String> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                    return out;
                });
    }

    /** Replaces a document and all its chunks atomically, so searches never see half a document. */
    @Transactional
    void replace(SourceDocument doc, String sha256, List<RegulationChunk> chunks, List<float[]> embeddings) {
        jdbc.sql("delete from kb_document where doc_id = :id").param("id", doc.id()).update();
        jdbc.sql("""
                        insert into kb_document (doc_id, short_name, title, source_url, sha256, chunk_count)
                        values (:id, :shortName, :title, :url, :sha256, :count)
                        """)
                .param("id", doc.id()).param("shortName", doc.shortName()).param("title", doc.title())
                .param("url", doc.url()).param("sha256", sha256).param("count", chunks.size())
                .update();
        if (chunks.isEmpty()) {
            return;
        }
        List<Integer> rows = IntStream.range(0, chunks.size()).boxed().toList();
        jdbcTemplate.batchUpdate("""
                        insert into kb_chunk (chunk_id, doc_id, ref, section, page, text, embedding)
                        values (?, ?, ?, ?, ?, ?, cast(? as vector))
                        """, rows, rows.size(), (ps, i) -> {
                    RegulationChunk c = chunks.get(i);
                    ps.setString(1, c.chunkId());
                    ps.setString(2, c.docId());
                    ps.setString(3, c.ref());
                    ps.setString(4, c.section());
                    ps.setInt(5, c.page());
                    ps.setString(6, c.text());
                    ps.setString(7, vectorLiteral(embeddings.get(i)));
                });
    }

    void deleteDocumentsNotIn(Collection<String> docIds) {
        jdbc.sql("delete from kb_document where doc_id <> all(:ids)")
                .param("ids", docIds.toArray(String[]::new))
                .update();
    }

    /** Nearest chunks by cosine distance (HNSW index), best first. */
    List<String> vectorSearch(float[] query, int limit) {
        return jdbc.sql("select chunk_id from kb_chunk order by embedding <=> cast(:q as vector) limit :limit")
                .param("q", vectorLiteral(query))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /**
     * Full-text search ranked by cover density. The query's terms are OR-ed: plainto_tsquery stems and drops stop
     * words but ANDs everything, which makes a natural-language question match almost nothing.
     */
    List<String> keywordSearch(String query, int limit) {
        return jdbc.sql("""
                        with q as (
                            select nullif(replace(plainto_tsquery('english', :query)::text, '&', '|'), '')::tsquery as tsq
                        )
                        select chunk_id from kb_chunk, q
                        where q.tsq is not null and tsv @@ q.tsq
                        order by ts_rank(tsv, q.tsq, 1) desc
                        limit :limit
                        """)
                .param("query", query)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    List<StoredPassage> findPassages(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select c.chunk_id, c.doc_id, d.short_name, c.ref, c.section, c.page, c.text
                        from kb_chunk c join kb_document d using (doc_id)
                        where c.chunk_id in (:ids)
                        """)
                .param("ids", chunkIds)
                .query((rs, n) -> new StoredPassage(rs.getString("chunk_id"), rs.getString("doc_id"),
                        rs.getString("short_name"), rs.getString("ref"), rs.getString("section"), rs.getInt("page"),
                        rs.getString("text")))
                .list();
    }

    Optional<Integer> chunkCount() {
        return Optional.ofNullable(jdbc.sql("select count(*) from kb_chunk").query(Integer.class).single());
    }

    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    record StoredPassage(String chunkId, String docId, String shortName, String ref, String section, int page,
                         String text) {
    }
}
