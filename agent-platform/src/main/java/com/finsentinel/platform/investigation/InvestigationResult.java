package com.finsentinel.platform.investigation;

import java.util.List;

/**
 * A finished, validated investigation plus the metadata needed to audit and cost it.
 *
 * @param repairedIssues    problems found in earlier drafts and fixed by a repair call; empty when the first
 *                          draft passed. Useful for spotting prompts or models that need tuning.
 * @param cachedInputTokens input tokens served from the provider's prompt cache (cheaper), when reported
 * @param llmCalls          model calls made, including repairs (tool round trips inside one call not counted)
 * @param evidence          what the tools returned to a single investigator, for the reviewer; empty for a report
 *                          written from specialists' findings. Not stored with the analysis
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
        long latencyMs,
        List<ToolEvidence> evidence) {

    public InvestigationResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public InvestigationResult(CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues, String model,
                               Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int llmCalls,
                               long latencyMs) {
        this(report, toolCalls, repairedIssues, model, inputTokens, outputTokens, cachedInputTokens, llmCalls,
                latencyMs, List.of());
    }

    static InvestigationResult of(CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues,
                                  LlmUsage usage, List<ToolEvidence> evidence) {
        return new InvestigationResult(report, toolCalls, repairedIssues, usage.model(), usage.inputTokens(),
                usage.outputTokens(), usage.cachedInputTokens(), usage.calls(), usage.latencyMs(), evidence);
    }

    public LlmUsage usage() {
        return new LlmUsage(model, inputTokens, outputTokens, cachedInputTokens, llmCalls, latencyMs);
    }

    /** The same report and evidence, with the cost of the whole analysis (other agents included). */
    public InvestigationResult withUsage(LlmUsage total) {
        return of(report, toolCalls, repairedIssues, total, evidence);
    }
}
