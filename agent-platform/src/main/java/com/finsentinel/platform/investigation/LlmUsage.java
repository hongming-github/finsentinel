package com.finsentinel.platform.investigation;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * What model calls cost: summed over every call of an agent, and over every agent of an analysis.
 *
 * @param cachedInputTokens input tokens served from the provider's prompt cache (cheaper), when reported
 * @param calls             model calls made, including repairs (tool round trips inside one call not counted)
 */
public record LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int calls,
                       long latencyMs) {

    public static final LlmUsage NONE = new LlmUsage(null, null, null, null, 0, 0);

    static LlmUsage of(ChatResponse chat, long latencyMs) {
        Usage usage = chat == null ? null : chat.getMetadata().getUsage();
        return new LlmUsage(chat == null ? "unknown" : chat.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null || usage.getCacheReadInputTokens() == null ? null
                        : Math.toIntExact(usage.getCacheReadInputTokens()),
                1, latencyMs);
    }

    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(other.model != null ? other.model : model, sum(inputTokens, other.inputTokens),
                sum(outputTokens, other.outputTokens), sum(cachedInputTokens, other.cachedInputTokens),
                calls + other.calls, latencyMs + other.latencyMs);
    }

    // Not a nested ternary: "a == null ? b : ... a + b" types as int and unboxes a null b.
    private static Integer sum(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }
}
