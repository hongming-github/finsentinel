package com.finsentinel.platform.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.knowledge.*}.
 *
 * @param dir           folder holding sources.json and docs/; relative paths resolve against the working directory
 * @param syncOnStartup ingest new or changed documents when the platform starts
 * @param maxChunks     refuse a sync that would embed more than this many chunks: a cost guard against a wrong file
 * @param chunkChars    longest chunk before it is split into parts
 * @param contextChars  longest "Refers to: ..." line naming what a chunk's cross-references point to, shown to the
 *                      reranker with it (ADR 0030); whole entries are dropped from the end past it
 * @param minFontPt     text below this size is dropped as footnotes (the MAS documents use 12pt body, 9pt notes)
 * @param batchSize     texts per embedding request
 * @param search        retrieval settings
 */
@ConfigurationProperties("finsentinel.knowledge")
public record KnowledgeProperties(Path dir, boolean syncOnStartup, int maxChunks, int chunkChars, int contextChars,
                                  float minFontPt, int batchSize, Search search) {

    public KnowledgeProperties {
        if (dir == null || maxChunks <= 0 || chunkChars < 200 || contextChars < 0 || minFontPt < 0 || batchSize <= 0
                || search == null) {
            throw new IllegalArgumentException("finsentinel.knowledge: invalid settings");
        }
    }

    /**
     * @param candidates         results taken from each of the vector and keyword searches before fusion
     * @param topK               passages returned to the agent
     * @param rrfK               reciprocal rank fusion constant; 60 is the value from the original RRF paper
     * @param keywordWeight      weight of the keyword ranking in the fusion, the vector ranking's being 1; between 0
     *                           (vector order, keyword candidates last) and 1 (plain RRF)
     * @param maxSearchesPerCase searches one investigation may run; each one can cost a rerank call
     * @param agentPassageChars  passage text returned to the agent per hit; long passages drown the case evidence
     * @param rerank             reranking of the fused candidates
     * @param cache              exact-match cache of search results, shared by every instance
     */
    public record Search(int candidates, int topK, int rrfK, double keywordWeight, int maxSearchesPerCase,
                         int agentPassageChars, Rerank rerank, Cache cache) {

        public Search {
            if (candidates < topK || topK <= 0 || rrfK <= 0 || !(keywordWeight >= 0 && keywordWeight <= 1)
                    || maxSearchesPerCase <= 0 || agentPassageChars < 200 || rerank == null || cache == null) {
                throw new IllegalArgumentException("finsentinel.knowledge.search: invalid settings");
            }
        }
    }

    /**
     * @param enabled         rerank on/off; decided by the retrieval evaluation, not by taste
     * @param using           which reranker the production pipeline uses (ADR 0030)
     * @param model           the LLM reranker's model; a small, cheap model is enough to score relevance
     * @param maxPassageChars passage text sent to the LLM reranker per candidate
     * @param crossEncoder    the cross-encoder service; contacted only when used
     */
    public record Rerank(boolean enabled, Using using, String model, int maxPassageChars, CrossEncoder crossEncoder) {

        public Rerank {
            if (using == null || model == null || model.isBlank() || maxPassageChars <= 0 || crossEncoder == null) {
                throw new IllegalArgumentException("finsentinel.knowledge.search.rerank: invalid settings");
            }
        }
    }

    /** LLM: a listwise grade from a chat model. CROSS_ENCODER: scores from the reranker service (reranker/). */
    public enum Using { LLM, CROSS_ENCODER }

    /**
     * @param url            base URL of the reranker service
     * @param model          the model the service must run; it refuses requests naming another, so a swapped model
     *                       cannot change rankings (or serve them from the search cache) unnoticed
     * @param connectTimeout to open a connection
     * @param timeout        for the whole answer; past it, search keeps the fused order
     */
    public record CrossEncoder(URI url, String model, Duration connectTimeout, Duration timeout) {

        public CrossEncoder {
            if (url == null || model == null || model.isBlank() || connectTimeout == null || connectTimeout.isNegative()
                    || connectTimeout.isZero() || timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("finsentinel.knowledge.search.rerank.cross-encoder: invalid settings");
            }
        }
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
