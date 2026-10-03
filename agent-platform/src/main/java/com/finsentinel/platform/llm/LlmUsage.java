package com.finsentinel.platform.llm;

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
 * @param calls             model calls made, including repairs (tool round trips inside one call not counted)
 * @param costUsd           at list prices, from {@code finsentinel.llm.prices}
 * @param costByModel       the same cost split by model, for routing decisions
 */
public record LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int calls,
                       long latencyMs, BigDecimal costUsd, Map<String, BigDecimal> costByModel) {

    public static final LlmUsage NONE = new LlmUsage(null, null, null, null, 0, 0);

    public LlmUsage {
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        costByModel = costByModel == null ? Map.of() : Map.copyOf(costByModel);
    }

    /** Without a cost: test stand-ins and calls whose price does not matter. */
    public LlmUsage(String model, Integer inputTokens, Integer outputTokens, Integer cachedInputTokens, int calls,
                    long latencyMs) {
        this(model, inputTokens, outputTokens, cachedInputTokens, calls, latencyMs, BigDecimal.ZERO, Map.of());
    }

    /** One call, priced by the model the provider says answered. */
    public static LlmUsage of(ChatResponse chat, String provider, long latencyMs, LlmPricing pricing) {
        Usage usage = chat == null ? null : chat.getMetadata().getUsage();
        String model = chat == null ? null : chat.getMetadata().getModel();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer cached = usage == null || usage.getCacheReadInputTokens() == null ? null
                : Math.toIntExact(usage.getCacheReadInputTokens());
        BigDecimal cost = pricing.cost(provider, model, input, cached, output);
        String key = pricing.priceKey(provider, model);
        return new LlmUsage(key, input, output, cached, 1, latencyMs, cost, Map.of(key, cost));
    }

    public LlmUsage plus(LlmUsage other) {
        Map<String, BigDecimal> byModel = new HashMap<>(costByModel);
        other.costByModel.forEach((m, c) -> byModel.merge(m, c, BigDecimal::add));
        return new LlmUsage(other.model != null ? other.model : model, sum(inputTokens, other.inputTokens),
                sum(outputTokens, other.outputTokens), sum(cachedInputTokens, other.cachedInputTokens),
                calls + other.calls, latencyMs + other.latencyMs, costUsd.add(other.costUsd), byModel);
    }

    public LlmUsage withLatency(long latencyMs) {
        return new LlmUsage(model, inputTokens, outputTokens, cachedInputTokens, calls, latencyMs, costUsd, costByModel);
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
