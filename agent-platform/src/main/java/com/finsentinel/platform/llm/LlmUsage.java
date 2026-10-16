package com.finsentinel.platform.llm;

import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * What model calls cost: summed over every call of an agent, and over every agent of an analysis.
 *
 * @param model             the model of the latest call (an analysis may use several; see {@code costByModel})
 * @param cachedInputTokens input tokens served from the provider's prompt cache (cheaper), when reported
 * @param cacheWriteTokens  input tokens written to the provider's prompt cache (dearer on GPT-5.6 and later), when
 *                          reported; the cached and written shares of the input show whether prompts reuse the cache
 * @param calls             model calls made, including repairs (tool round trips inside one call not counted)
 * @param costUsd           at list prices, from {@code finsentinel.llm.prices}
 * @param costByModel       the same cost split by model, for routing decisions
 */
public record LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                       Integer cacheWriteTokens, int calls, long latencyMs, BigDecimal costUsd,
                       Map<String, BigDecimal> costByModel) {

    public static final LlmUsage NONE = new LlmUsage(null, null, null, null, 0, 0);

    public LlmUsage {
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        costByModel = costByModel == null ? Map.of() : Map.copyOf(costByModel);
    }

    /** Without a cost: test stand-ins and calls whose price does not matter. */
    public LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int calls,
                    long latencyMs) {
        this(model, inputTokens, outputTokens, cachedInputTokens, null, calls, latencyMs, BigDecimal.ZERO, Map.of());
    }

    /** Without cache writes: providers and models that do not report them. */
    public LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int calls,
                    long latencyMs, BigDecimal costUsd, Map<String, BigDecimal> costByModel) {
        this(model, inputTokens, outputTokens, cachedInputTokens, null, calls, latencyMs, costUsd, costByModel);
    }

    /** One call, priced by the model the provider says answered. */
    public static LlmUsage of(ChatResponse chat, String provider, long latencyMs, LlmPricing pricing) {
        Usage usage = chat == null ? null : chat.getMetadata().getUsage();
        String model = chat == null ? null : chat.getMetadata().getModel();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer cached = usage == null || usage.getCacheReadInputTokens() == null ? null
                : Math.toIntExact(usage.getCacheReadInputTokens());
        Integer written = cacheWriteTokens(usage);
        BigDecimal cost = pricing.cost(provider, model, input, cached, written, output);
        String key = pricing.priceKey(provider, model);
        return new LlmUsage(key, input, output, cached, written, 1, latencyMs, cost, Map.of(key, cost));
    }

    /**
     * Prompt tokens written to the provider's cache, or null when unknown. Spring AI 2.0.1's OpenAI client drops the
     * count (it passes null as the cache-write tokens) but keeps the SDK's usage, where Chat Completions reports it. That
     * works for a single request only: over a tool-calling exchange Spring AI sums the usage of each request, drops the
     * SDK's usage, and sums its own empty cache-write field to 0. So a 0 without the SDK's usage means unknown, never
     * "nothing written"; such calls bill their unknown writes at the input rate (ADR 0023). Cached tokens are summed
     * correctly either way.
     */
    static Integer cacheWriteTokens(Usage usage) {
        if (usage == null) {
            return null;
        }
        if (usage.getNativeUsage() instanceof CompletionUsage completion) {
            return completion.promptTokensDetails().flatMap(CompletionUsage.PromptTokensDetails::cacheWriteTokens)
                    .map(Math::toIntExact).orElse(null);
        }
        Long reported = usage.getCacheWriteInputTokens();
        return reported == null || reported == 0 ? null : Math.toIntExact(reported);
    }

    public LlmUsage plus(LlmUsage other) {
        Map<String, BigDecimal> byModel = new HashMap<>(costByModel);
        other.costByModel.forEach((m, c) -> byModel.merge(m, c, BigDecimal::add));
        return new LlmUsage(other.model != null ? other.model : model, sum(inputTokens, other.inputTokens),
                sum(outputTokens, other.outputTokens), sum(cachedInputTokens, other.cachedInputTokens),
                sum(cacheWriteTokens, other.cacheWriteTokens), calls + other.calls, latencyMs + other.latencyMs,
                costUsd.add(other.costUsd), byModel);
    }

    public LlmUsage withLatency(long latencyMs) {
        return new LlmUsage(model, inputTokens, outputTokens, cachedInputTokens, cacheWriteTokens, calls, latencyMs,
                costUsd, costByModel);
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
