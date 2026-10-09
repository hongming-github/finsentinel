package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.LlmUsage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A finished, validated investigation plus the metadata needed to audit and cost it.
 *
 * @param repairedIssues    problems found in earlier drafts and fixed by a repair call; empty when the first
 *                          draft passed. Useful for spotting prompts or models that need tuning.
 * @param cachedInputTokens input tokens served from the provider's prompt cache (cheaper), when reported
 * @param llmCalls          model calls made, including repairs (tool round trips inside one call not counted)
 * @param evidence          what the tools returned to a single investigator, for the reviewer; empty for a report
 *                          written from specialists' findings. Not stored with the analysis
 * @param costUsd           at list prices: every model call of the analysis, reranking included
 * @param costByModel       the same cost split by model
 * @param untrustedInstructions tool results that tried to instruct the agents, as flagged by the platform (ADR 0016);
 *                          recorded on the case for the analyst
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
        List<ToolEvidence> evidence,
        BigDecimal costUsd,
        Map<String, BigDecimal> costByModel,
        List<String> untrustedInstructions) {

    public InvestigationResult {
        untrustedInstructions = untrustedInstructions == null ? List.of() : List.copyOf(untrustedInstructions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        costByModel = costByModel == null ? Map.of() : Map.copyOf(costByModel);
    }

    public InvestigationResult(CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues, String model,
                               Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int llmCalls,
                               long latencyMs) {
        this(report, toolCalls, repairedIssues, model, inputTokens, outputTokens, cachedInputTokens, llmCalls,
                latencyMs, List.of(), BigDecimal.ZERO, Map.of(), List.of());
    }

    static InvestigationResult of(CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues,
                                  LlmUsage usage, List<ToolEvidence> evidence) {
        return of(report, toolCalls, repairedIssues, usage, evidence, List.of());
    }

    static InvestigationResult of(CaseReport report, List<ToolCall> toolCalls, List<String> repairedIssues,
                                  LlmUsage usage, List<ToolEvidence> evidence, List<String> untrustedInstructions) {
        return new InvestigationResult(report, toolCalls, repairedIssues, usage.model(), usage.inputTokens(),
                usage.outputTokens(), usage.cachedInputTokens(), usage.calls(), usage.latencyMs(), evidence,
                usage.costUsd(), usage.costByModel(), untrustedInstructions);
    }

    public LlmUsage usage() {
        return new LlmUsage(model, inputTokens, outputTokens, cachedInputTokens, llmCalls, latencyMs, costUsd,
                costByModel);
    }

    /** The same report and evidence, with the cost of the whole analysis (other agents included). */
    public InvestigationResult withUsage(LlmUsage total) {
        return of(report, toolCalls, repairedIssues, total, evidence, untrustedInstructions);
    }
}
