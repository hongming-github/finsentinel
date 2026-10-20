package com.finsentinel.platform.assistant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.knowledge.RegulationHit;
import com.finsentinel.platform.llm.LlmUsage;

import java.util.List;

/**
 * The model's three jobs in a conversation (ADR 0028). The pipeline around them (budget, facts, retrieval, checks,
 * storage) is {@link CaseAssistant}; normal tests replace only this, so all of that runs in them without a model.
 */
public interface AssistantModel {

    /** The question rewritten to stand on its own, and whether it needs the regulations. */
    Reply<Condensed> condense(CaseContext caseContext, History history, String question);

    /**
     * @param factSheet the case as read for this turn
     * @param passages  regulation passages retrieved for this turn; empty when the question did not need them
     * @param question  the analyst's question, wrapped in the platform's warning when it carries instructions
     * @param problems  why the previous answer was refused; empty on the first attempt
     */
    Reply<Answer> answer(CaseContext caseContext, String factSheet, History history, List<RegulationHit> passages,
                         String question, List<String> problems);

    /** {@code previous} (null at first) extended with the turns being folded, under {@code maxChars}. */
    Reply<Summary> summarise(CaseContext caseContext, String previous, List<ConversationTurn> folded, int maxChars);

    /** What the model said and what saying it cost. */
    record Reply<T>(T value, LlmUsage usage) {
    }

    /**
     * What the model sees of earlier turns.
     *
     * @param summary the older turns, folded; null when there are none
     * @param recent  the latest turns, word for word, oldest first
     */
    record History(String summary, List<ConversationTurn> recent) {

        public History {
            recent = List.copyOf(recent);
        }

        public boolean isEmpty() {
            return summary == null && recent.isEmpty();
        }
    }

    record Condensed(
            @JsonPropertyDescription("The analyst's latest question rewritten so it can be understood without the "
                    + "conversation: pronouns and references such as 'it', 'that transfer' or 'the second one' "
                    + "replaced by what they refer to. Same language and intent; do not answer it")
            String standaloneQuestion,
            @JsonPropertyDescription("True only when answering needs the text of MAS regulations or guidelines "
                    + "(obligations, deadlines, liability), false for questions about the case's own facts")
            boolean needsRegulations,
            @JsonPropertyDescription("When needsRegulations: one short search question about one regulatory topic, in "
                    + "the words a regulation would use; otherwise empty")
            String searchQuery) {
    }

    record Answer(
            @JsonPropertyDescription("The answer for the analyst: short, factual, plain text")
            String answer,
            @JsonPropertyDescription("Ids of the case's transactions the answer relies on, exactly as in the fact sheet")
            List<String> citedTxnIds,
            @JsonPropertyDescription("chunkIds of the regulation passages the answer relies on, exactly as given")
            List<String> citedChunkIds) {
    }

    record Summary(
            @JsonPropertyDescription("What the analyst asked and was told in these turns, in a few sentences: facts, "
                    + "conclusions and open points. Only what the turns say; copy any id exactly")
            String summary) {
    }
}
