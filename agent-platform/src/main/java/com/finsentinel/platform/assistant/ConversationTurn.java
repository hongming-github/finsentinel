package com.finsentinel.platform.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One question and its answer, as stored (append-only).
 *
 * @param standaloneQuestion     the question rewritten with the history, so it reads on its own
 * @param searchQuery            what the regulation search was asked, from the condensed question; null when the
 *                               question did not need the regulations
 * @param untrustedInstructions  what the question did that pasted data should not do; empty for an ordinary question
 * @param caseVersion            the case version the answer was given on: a later decision or analysis shows as newer
 */
public record ConversationTurn(int seq, String question, String standaloneQuestion, String searchQuery,
                               String answer, List<String> citedTxnIds, List<String> citedChunkIds,
                               List<String> untrustedInstructions, int caseVersion, String model, Integer inputTokens,
                               Integer outputTokens, Integer cachedInputTokens, Integer cacheWriteTokens, int llmCalls,
                               BigDecimal costUsd, long latencyMs, Instant createdAt) {

    public ConversationTurn {
        citedTxnIds = List.copyOf(citedTxnIds);
        citedChunkIds = List.copyOf(citedChunkIds);
        untrustedInstructions = List.copyOf(untrustedInstructions);
    }
}
