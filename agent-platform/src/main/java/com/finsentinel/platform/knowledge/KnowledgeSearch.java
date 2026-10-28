package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval over the regulatory corpus: vector and keyword candidates fused with reciprocal rank fusion,
 * then optionally reranked by an LLM or a cross-encoder (ADR 0030). {@link Mode} exists so the retrieval evaluation can compare the stages.
 * The production pipeline goes through {@link SearchCache}; the evaluation modes never do.
 */
@Component
public class KnowledgeSearch {

    /**
     * HYBRID_RERANK is the production pipeline, with the configured reranker; the others exist for the retrieval
     * evaluation's comparison. HYBRID_CROSS_ENCODER reranks with the cross-encoder whatever is configured.
     */
    public enum Mode { VECTOR, KEYWORD, HYBRID, VECTOR_RERANK, HYBRID_RERANK, HYBRID_CROSS_ENCODER }

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearch.class);

    private final KnowledgeRepository repository;
    private final Embedder embedder;
    private final Reranker reranker;
    private final Reranker crossEncoder;
    private final SearchCache cache;
    private final KnowledgeProperties.Search props;

    @Autowired
    KnowledgeSearch(KnowledgeRepository repository, Embedder embedder, LlmReranker llm,
                    CrossEncoderReranker crossEncoder, SearchCache cache, KnowledgeProperties props) {
        this(repository, embedder, props.search().rerank().using() == KnowledgeProperties.Using.CROSS_ENCODER
                ? crossEncoder : llm, crossEncoder, cache, props);
    }

    /** @param reranker the production pipeline's reranker; crossEncoder serves HYBRID_CROSS_ENCODER */
    KnowledgeSearch(KnowledgeRepository repository, Embedder embedder, Reranker reranker, Reranker crossEncoder,
                    SearchCache cache, KnowledgeProperties props) {
        this.repository = repository;
        this.embedder = embedder;
        this.reranker = reranker;
        this.crossEncoder = crossEncoder;
        this.cache = cache;
        this.props = props.search();
    }

    /** The configured production pipeline. */
    public List<RegulationHit> search(String query) {
        return searchWithUsage(query).hits();
    }

    /**
     * The production pipeline, with what the reranking model cost (embedding the query is not counted). A cached
     * result costs nothing and reports what producing it had cost.
     */
    public SearchResult searchWithUsage(String query) {
        String corpus = cache.enabled() ? repository.corpusVersion() : null;
        Optional<SearchCache.Entry> cached = cache.get(query, corpus);
        if (cached.isPresent()) {
            List<RegulationHit> hits = hits(cached.get().chunkIds());
            if (hits.size() == cached.get().chunkIds().size()) {
                return new SearchResult(hits, LlmUsage.NONE, true, cached.get().costUsd());
            }
        }
        Ranked ranked = run(query, props.rerank().enabled() ? Mode.HYBRID_RERANK : Mode.HYBRID, props.topK());
        if (!ranked.degraded() && !ranked.hits().isEmpty()) {
            // A degraded ranking (provider down) is served but not remembered: it would outlive the outage.
            cache.put(query, corpus, new SearchCache.Entry(
                    ranked.hits().stream().map(RegulationHit::chunkId).toList(), ranked.usage().costUsd()));
        }
        return new SearchResult(ranked.hits(), ranked.usage(), cache.enabled() ? false : null, null);
    }

    public List<RegulationHit> search(String query, Mode mode, int topK) {
        return run(query, mode, topK).hits();
    }

    /** For the retrieval evaluation: also what reranking cost and whether a stage fell back. */
    Ranked evaluate(String query, Mode mode, int topK) {
        return run(query, mode, topK);
    }

    /**
     * @param cacheHit served from {@link SearchCache}; null when the cache is off
     * @param savedUsd on a hit, what the cached search had cost to produce
     */
    public record SearchResult(List<RegulationHit> hits, LlmUsage usage, Boolean cacheHit, BigDecimal savedUsd) {
    }

    /** @param degraded a stage failed and was skipped (keyword-only, or fused order instead of reranked) */
    record Ranked(List<RegulationHit> hits, LlmUsage usage, boolean degraded) {
    }

    private record Fused(List<String> ids, boolean degraded) {
    }

    private Ranked run(String query, Mode mode, int topK) {
        Fused fused = switch (mode) {
            case VECTOR, VECTOR_RERANK ->
                    new Fused(repository.vectorSearch(embedder.embed(query), props.candidates()), false);
            case KEYWORD -> new Fused(repository.keywordSearch(query, props.candidates()), false);
            case HYBRID, HYBRID_RERANK, HYBRID_CROSS_ENCODER -> hybrid(query);
        };
        Reranker.Ranking ranking = new Reranker.Ranking(fused.ids(), LlmUsage.NONE);
        boolean degraded = fused.degraded();
        if (mode == Mode.HYBRID_RERANK || mode == Mode.VECTOR_RERANK || mode == Mode.HYBRID_CROSS_ENCODER) {
            Optional<Reranker.Ranking> reranked = rerank(mode == Mode.HYBRID_CROSS_ENCODER ? crossEncoder : reranker,
                    query, fused.ids());
            degraded |= reranked.isEmpty();
            ranking = reranked.orElse(ranking);
        }
        List<String> ranked = ranking.ids();
        return new Ranked(hits(ranked.subList(0, Math.min(topK, ranked.size()))), ranking.usage(), degraded);
    }

    private Fused hybrid(String query) {
        List<String> keyword = repository.keywordSearch(query, props.candidates());
        List<String> vector;
        try {
            vector = repository.vectorSearch(embedder.embed(query), props.candidates());
        } catch (RuntimeException e) {
            // The embedding provider is down and the question is not in the embedding cache: keyword search alone is
            // a worse ranking, not a failed search. Same rule as for reranking.
            log.warn("Embedding failed, searching by keyword only: {}", e.toString());
            return new Fused(keyword, true);
        }
        return new Fused(fuse(List.of(vector, keyword), props.rrfK()), false);
    }

    /**
     * Reciprocal rank fusion: score(d) = sum over lists of 1 / (k + rank). Uses ranks only, so the incomparable
     * scales of cosine distance and ts_rank never have to be normalised against each other.
     */
    static List<String> fuse(List<List<String>> rankings, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Empty when the reranker failed: the caller keeps the fused order. */
    private Optional<Reranker.Ranking> rerank(Reranker reranker, String query, List<String> fused) {
        List<String> top = fused.subList(0, Math.min(props.candidates(), fused.size()));
        Map<String, KnowledgeRepository.StoredPassage> passages = byId(top);
        List<Reranker.Candidate> candidates = top.stream()
                .filter(passages::containsKey)
                .map(id -> new Reranker.Candidate(id, passages.get(id).shortName() + " §" + passages.get(id).ref()
                        + " (" + passages.get(id).section() + ")\n" + passages.get(id).text()))
                .toList();
        List<String> reranked;
        LlmUsage usage;
        try {
            Reranker.Ranking ranking = reranker.rankWithUsage(query, candidates);
            reranked = new ArrayList<>(ranking.ids());
            usage = ranking.usage();
        } catch (RuntimeException e) {
            // Reranking improves order; it must never take search down with it.
            log.warn("Rerank failed, using fused order: {}", e.toString());
            return Optional.empty();
        }
        if (reranked.size() < props.topK()) {
            // Never return fewer passages than plain fusion would because the grader dropped some.
            fused.stream().filter(id -> !reranked.contains(id)).limit(props.topK() - reranked.size()).forEach(reranked::add);
        }
        return Optional.of(new Reranker.Ranking(reranked, usage));
    }

    /** Full passages by chunk id, in the order asked; unknown ids are left out. */
    public List<RegulationHit> passages(List<String> chunkIds) {
        return hits(chunkIds);
    }

    private List<RegulationHit> hits(List<String> ids) {
        Map<String, KnowledgeRepository.StoredPassage> passages = byId(ids);
        return ids.stream().filter(passages::containsKey).map(passages::get)
                .map(p -> new RegulationHit(p.chunkId(), p.shortName() + " §" + p.ref(), p.section(), p.page(), p.text()))
                .toList();
    }

    private Map<String, KnowledgeRepository.StoredPassage> byId(List<String> ids) {
        return repository.findPassages(ids).stream()
                .collect(Collectors.toMap(KnowledgeRepository.StoredPassage::chunkId, Function.identity(), (a, b) -> a,
                        HashMap::new));
    }

    public boolean isEmpty() {
        return repository.chunkCount().orElse(0) == 0;
    }

    public int maxSearchesPerCase() {
        return props.maxSearchesPerCase();
    }

    public int maxPassageCharsForAgent() {
        return props.agentPassageChars();
    }
}
