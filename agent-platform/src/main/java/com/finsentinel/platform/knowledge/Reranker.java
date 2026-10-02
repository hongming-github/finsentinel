package com.finsentinel.platform.knowledge;

import java.util.List;

/** Orders candidate passages by relevance to a query. */
public interface Reranker {

    /** @return the candidate ids, most relevant first; ids judged irrelevant may be dropped */
    List<String> rerank(String query, List<Candidate> candidates);

    record Candidate(String id, String text) {
    }
}
