package com.finsentinel.platform.investigation;

import java.util.List;

/**
 * A finished, validated investigation plus the metadata needed to audit and cost it.
 *
 * @param repairedIssues    problems found in earlier drafts and fixed by a repair call; empty when the first
 *                          draft passed. Useful for spotting prompts or models that need tuning.
 * @param cachedInputTokens input tokens served from the provider's prompt cache (cheaper), when reported
 * @param llmCalls          model calls made, including repairs (tool round trips inside one call not counted)
 */
public record InvestigationResult(
        CaseReport report,
        List<ToolCall> toolCalls,
        List<String> repairedIssues,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Integer cachedInputTokens,
        int llmCalls,
        long latencyMs) {
}
