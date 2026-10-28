package com.finsentinel.platform.knowledge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Search results (ranked chunk ids) in Redis, shared by every platform instance, keyed by the exact question after
 * normalising case, width and spacing. Never by embedding similarity: questions one word apart ("authorised" vs
 * "unauthorized" scam) embed at 0.95 cosine yet need different passages (ADR 0013).
 *
 * <p>The key also carries the corpus version and the search settings, so a re-ingest or a settings change misses
 * instead of serving stale passages. The cache is disposable: any Redis error is a miss, never a failed search. The
 * regulatory corpus is public and global, so one entry serves every tenant; the question text is hashed, not stored.
 */
@Component
class SearchCache {

    private static final Logger log = LoggerFactory.getLogger(SearchCache.class);
    private static final String PREFIX = "finsentinel:regulation-search:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final KnowledgeProperties.Cache props;
    private final String settings;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    SearchCache(StringRedisTemplate redis, JsonMapper json, KnowledgeProperties knowledge, MeterRegistry meters,
                @Value("${spring.ai.openai.embedding.model}") String embeddingModel) {
        this.redis = redis;
        this.json = json;
        this.props = knowledge.search().cache();
        KnowledgeProperties.Search s = knowledge.search();
        // Everything that changes which ids a question returns. Agent-side settings (passage length, searches per
        // case) only change what is done with them.
        this.settings = sha256(String.join("|", embeddingModel, String.valueOf(s.candidates()), String.valueOf(s.topK()),
                String.valueOf(s.rrfK()), String.valueOf(s.rerank().enabled()), s.rerank().using().name(), s.rerank().model(),
                String.valueOf(s.rerank().maxPassageChars()), s.rerank().crossEncoder().model())).substring(0, 16);
        this.hits = meters.counter("finsentinel.knowledge.search.cache", "result", "hit");
        this.misses = meters.counter("finsentinel.knowledge.search.cache", "result", "miss");
        this.errors = meters.counter("finsentinel.knowledge.search.cache", "result", "error");
    }

    /**
     * @param chunkIds the ranked result
     * @param costUsd  what producing it cost (the rerank call): what a hit saves
     */
    record Entry(List<String> chunkIds, BigDecimal costUsd) {
    }

    boolean enabled() {
        return props.enabled();
    }

    Optional<Entry> get(String query, String corpusVersion) {
        if (!props.enabled()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(key(query, corpusVersion));
            if (value == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(json.readValue(value, Entry.class));
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Search cache read failed, searching without it: {}", e.toString());
            return Optional.empty();
        }
    }

    void put(String query, String corpusVersion, Entry entry) {
        if (!props.enabled()) {
            return;
        }
        try {
            // Two instances missing at once both search and both write: last write wins, same question, same corpus.
            redis.opsForValue().set(key(query, corpusVersion), json.writeValueAsString(entry), props.ttl());
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Search cache write failed: {}", e.toString());
        }
    }

    private String key(String query, String corpusVersion) {
        return PREFIX + settings + ":" + corpusVersion + ":" + sha256(normalise(query));
    }

    /**
     * Folds only differences that cannot change the meaning: Unicode width/compatibility forms, case, runs of
     * whitespace, and trailing punctuation. Spelling variants ("unauthorised"/"unauthorized") stay distinct: a miss
     * costs one rerank, a wrong hit costs a wrong citation.
     */
    static String normalise(String query) {
        String s = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return s.replaceAll("\\s+", " ").replaceAll("[\\s?.!]+$", "").strip();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
