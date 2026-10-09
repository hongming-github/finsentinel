package com.finsentinel.platform.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.knowledge.*}.
 *
 * @param dir           folder holding sources.json and docs/; relative paths resolve against the working directory
 * @param syncOnStartup ingest new or changed documents when the platform starts
 * @param maxChunks     refuse a sync that would embed more than this many chunks: a cost guard against a wrong file
 * @param chunkChars    longest chunk before it is split into parts
 * @param minFontPt     text below this size is dropped as footnotes (the MAS documents use 12pt body, 9pt notes)
 * @param batchSize     texts per embedding request
 * @param search        retrieval settings
 */
@ConfigurationProperties("finsentinel.knowledge")
public record KnowledgeProperties(Path dir, boolean syncOnStartup, int maxChunks, int chunkChars, float minFontPt,
                                  int batchSize, Search search) {

    public KnowledgeProperties {
        if (dir == null || maxChunks <= 0 || chunkChars < 200 || minFontPt < 0 || batchSize <= 0 || search == null) {
            throw new IllegalArgumentException("finsentinel.knowledge: invalid settings");
        }
    }

    /**
     * @param candidates         results taken from each of the vector and keyword searches before fusion
     * @param topK               passages returned to the agent
     * @param rrfK               reciprocal rank fusion constant; 60 is the value from the original RRF paper
     * @param maxSearchesPerCase searches one investigation may run; each one can cost a rerank call
     * @param agentPassageChars  passage text returned to the agent per hit; long passages drown the case evidence
     * @param rerank             LLM reranking of the fused candidates
     * @param cache              exact-match cache of search results, shared by every instance
     */
    public record Search(int candidates, int topK, int rrfK, int maxSearchesPerCase, int agentPassageChars,
                         Rerank rerank, Cache cache) {

        public Search {
            if (candidates < topK || topK <= 0 || rrfK <= 0 || maxSearchesPerCase <= 0 || agentPassageChars < 200
                    || rerank == null || cache == null) {
                throw new IllegalArgumentException("finsentinel.knowledge.search: invalid settings");
            }
        }
    }

    /**
     * @param enabled         rerank on/off; decided by the retrieval evaluation, not by taste
     * @param model           a small, cheap model is enough to score relevance
     * @param maxPassageChars passage text sent to the reranker per candidate
     */
    public record Rerank(boolean enabled, String model, int maxPassageChars) {
    }

    /**
     * Results are cached under the normalised question text only, never by embedding similarity (ADR 0013).
     *
     * @param enabled on/off; off means every search runs the full pipeline
     * @param ttl     how long a result is reused; a re-ingested corpus or changed search settings miss anyway
     */
    public record Cache(boolean enabled, Duration ttl) {

        public Cache {
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("finsentinel.knowledge.search.cache: invalid settings");
            }
        }
    }
}
