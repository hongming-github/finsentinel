package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.llm.LlmUsage;

import java.util.List;

/** Orders candidate passages by relevance to a query. */
public interface Reranker {

    /** @return the candidate ids, most relevant first; ids judged irrelevant may be dropped */
    List<String> rerank(String query, List<Candidate> candidates);

    /** The same ranking with what producing it cost; free for rerankers that call no model. */
    default Ranking rankWithUsage(String query, List<Candidate> candidates) {
        return new Ranking(rerank(query, candidates), LlmUsage.NONE);
    }

    record Ranking(List<String> ids, LlmUsage usage) {
    }

    record Candidate(String id, String text) {
    }
}
