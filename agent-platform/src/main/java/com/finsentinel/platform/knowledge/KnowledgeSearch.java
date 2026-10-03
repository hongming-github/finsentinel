package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval over the regulatory corpus: vector and keyword candidates fused with reciprocal rank fusion,
 * then optionally reranked by an LLM. {@link Mode} exists so the retrieval evaluation can compare the stages.
 */
@Component
public class KnowledgeSearch {

    public enum Mode { VECTOR, KEYWORD, HYBRID, HYBRID_RERANK }

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearch.class);

    private final KnowledgeRepository repository;
    private final Embedder embedder;
    private final Reranker reranker;
    private final KnowledgeProperties.Search props;

    KnowledgeSearch(KnowledgeRepository repository, Embedder embedder, Reranker reranker, KnowledgeProperties props) {
        this.repository = repository;
        this.embedder = embedder;
        this.reranker = reranker;
        this.props = props.search();
    }

    /** The configured production pipeline. */
    public List<RegulationHit> search(String query) {
        return searchWithUsage(query).hits();
    }

    /** The production pipeline, with what the reranking model cost (embedding the query is not counted). */
    public SearchResult searchWithUsage(String query) {
        return run(query, props.rerank().enabled() ? Mode.HYBRID_RERANK : Mode.HYBRID, props.topK());
    }

    public List<RegulationHit> search(String query, Mode mode, int topK) {
        return run(query, mode, topK).hits();
    }

    public record SearchResult(List<RegulationHit> hits, LlmUsage usage) {
    }

    private SearchResult run(String query, Mode mode, int topK) {
        Reranker.Ranking ranking = switch (mode) {
            case VECTOR -> free(repository.vectorSearch(embedder.embed(query), props.candidates()));
            case KEYWORD -> free(repository.keywordSearch(query, props.candidates()));
            case HYBRID -> free(hybrid(query));
            case HYBRID_RERANK -> rerank(query, hybrid(query));
        };
        List<String> ranked = ranking.ids();
        return new SearchResult(hits(ranked.subList(0, Math.min(topK, ranked.size()))), ranking.usage());
    }

    private static Reranker.Ranking free(List<String> ids) {
        return new Reranker.Ranking(ids, LlmUsage.NONE);
    }

    private List<String> hybrid(String query) {
        List<String> keyword = repository.keywordSearch(query, props.candidates());
        List<String> vector;
        try {
            vector = repository.vectorSearch(embedder.embed(query), props.candidates());
        } catch (RuntimeException e) {
            // The embedding provider is down and the question is not in the embedding cache: keyword search alone is
            // a worse ranking, not a failed search. Same rule as for reranking.
            log.warn("Embedding failed, searching by keyword only: {}", e.toString());
            return keyword;
        }
        return fuse(List.of(vector, keyword), props.rrfK());
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

    private Reranker.Ranking rerank(String query, List<String> fused) {
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
            return free(fused);
        }
        if (reranked.size() < props.topK()) {
            // Never return fewer passages than plain fusion would because the grader dropped some.
            fused.stream().filter(id -> !reranked.contains(id)).limit(props.topK() - reranked.size()).forEach(reranked::add);
        }
        return new Reranker.Ranking(reranked, usage);
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
