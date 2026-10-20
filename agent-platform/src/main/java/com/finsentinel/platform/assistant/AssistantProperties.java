package com.finsentinel.platform.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code finsentinel.assistant.*}: the case assistant's budget and context window (ADR 0028). Invalid values
 * fail at startup.
 *
 * @param maxQuestionChars          longest question accepted
 * @param maxTurnsPerConversation   turns after which the analyst starts a new conversation
 * @param maxTurnsPerAnalystPerHour spend guard per analyst, counted in Postgres across instances
 * @param maxTurnsPerTenantPerHour  spend guard per bank, counted in Postgres across instances
 * @param windowTurns               most recent turns always sent word for word
 * @param foldTurns                 turns beyond the window folded into the summary at once: folding in batches keeps
 *                                  the prompt's cached prefix stable for several turns instead of changing every turn
 * @param historyMaxTokens          estimated tokens the word-for-word turns may take; older ones are folded early
 * @param summaryMaxChars           longest summary kept
 * @param maxCaseTransactions       alert transactions shown to the model per turn
 * @param maxRepairs                extra answer calls when an answer cites what it was not given
 * @param maxConversationsListed    conversations listed per case
 */
@ConfigurationProperties("finsentinel.assistant")
public record AssistantProperties(int maxQuestionChars, int maxTurnsPerConversation, int maxTurnsPerAnalystPerHour,
                                  int maxTurnsPerTenantPerHour, int windowTurns, int foldTurns, int historyMaxTokens,
                                  int summaryMaxChars, int maxCaseTransactions, int maxRepairs,
                                  int maxConversationsListed) {

    public AssistantProperties {
        require(maxQuestionChars > 0, "max-question-chars must be positive");
        require(maxTurnsPerConversation > 0, "max-turns-per-conversation must be positive");
        // A budget, never off (CLAUDE.md: every LLM-calling path has one).
        require(maxTurnsPerAnalystPerHour > 0, "max-turns-per-analyst-per-hour must be positive");
        require(maxTurnsPerTenantPerHour >= maxTurnsPerAnalystPerHour,
                "max-turns-per-tenant-per-hour must be at least max-turns-per-analyst-per-hour");
        require(windowTurns > 0, "window-turns must be positive");
        require(foldTurns > 0, "fold-turns must be positive");
        require(historyMaxTokens > 0, "history-max-tokens must be positive");
        require(summaryMaxChars > 0, "summary-max-chars must be positive");
        require(maxCaseTransactions > 0, "max-case-transactions must be positive");
        require(maxRepairs >= 0, "max-repairs must be 0 or positive");
        require(maxConversationsListed > 0, "max-conversations-listed must be positive");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("finsentinel.assistant." + message);
        }
    }
}
