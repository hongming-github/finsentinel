package com.finsentinel.platform.llm;

import com.openai.models.completions.CompletionUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPricingTest {

    private final LlmProperties props = TestLlm.properties(Set.of());
    private final LlmPricing pricing = new LlmPricing(props);
    private final LlmPricing withLuna = new LlmPricing(new LlmProperties(props.providers(), props.models(),
            props.escalation(), props.fallback(), withPrice(props.prices(), "gpt-6-luna",
            new LlmProperties.Price(new BigDecimal("0.10"), new BigDecimal("0.01"), new BigDecimal("0.125"),
                    new BigDecimal("0.50")))));

    private static Map<String, LlmProperties.Price> withPrice(Map<String, LlmProperties.Price> prices, String model,
                                                              LlmProperties.Price price) {
        Map<String, LlmProperties.Price> all = new HashMap<>(prices);
        all.put(model, price);
        return all;
    }

    @Test
    void cachedInputIsBilledAtTheCachedRateNotTwice() {
        // 10,000 prompt tokens of which 8,000 cached, 1,000 output:
        // 2,000 * 0.75 + 8,000 * 0.075 + 1,000 * 4.50 = 1,500 + 600 + 4,500 = 6,600 per million = 0.0066
        assertThat(pricing.cost("openai", "gpt-5.4-mini", 10_000, 8_000, null, 1_000))
                .isEqualByComparingTo("0.0066");
    }

    /** GPT-6 Luna charges for writes to the prompt cache: 1.25x input, instead of the input rate, not on top of it. */
    @Test
    void cacheWritesAreBilledAtTheirOwnRateInsteadOfTheInputRate() {
        // 10,000 prompt tokens: 2,000 cached, 6,000 written to the cache, 2,000 plain; 1,000 output:
        // 2,000 * 0.10 + 2,000 * 0.01 + 6,000 * 0.125 + 1,000 * 0.50 = 200 + 20 + 750 + 500 = 1,470 per million
        assertThat(withLuna.cost("openai", "gpt-6-luna", 10_000, 2_000, 6_000, 1_000)).isEqualByComparingTo("0.00147");
        // Without a cache-write price, writes cost what plain input costs.
        assertThat(pricing.cost("openai", "gpt-5.4-mini", 10_000, 0, 6_000, 0))
                .isEqualByComparingTo(pricing.cost("openai", "gpt-5.4-mini", 10_000, 0, null, 0));
    }

    /**
     * Spring AI 2.0.1 drops the cache-write count from OpenAI's answer (passes null); read from the SDK's usage, which
     * it keeps, the cost would otherwise come out 20% low on every written token.
     */
    @Test
    void cacheWritesAreReadFromTheProvidersOwnUsage() {
        CompletionUsage reported = CompletionUsage.builder().promptTokens(10_000).completionTokens(1_000)
                .totalTokens(11_000).promptTokensDetails(CompletionUsage.PromptTokensDetails.builder()
                        .cachedTokens(2_000).cacheWriteTokens(6_000).build())
                .build();
        ChatResponse chat = new ChatResponse(List.of(), ChatResponseMetadata.builder().model("gpt-6-luna")
                .usage(new DefaultUsage(10_000, 1_000, 11_000, reported, 2_000L, null)).build());

        LlmUsage usage = LlmUsage.of(chat, "openai", 5, withLuna);

        assertThat(usage.costUsd()).isEqualByComparingTo("0.00147");
        assertThat(usage.cachedInputTokens()).isEqualTo(2_000);
        assertThat(usage.cacheWriteTokens()).isEqualTo(6_000);
        assertThat(usage.plus(usage).cacheWriteTokens()).isEqualTo(12_000);
        assertThat(usage.plus(LlmUsage.NONE).cacheWriteTokens()).as("a call without writes adds none")
                .isEqualTo(6_000);
    }

    /**
     * Over a tool-calling exchange Spring AI 2.0.1 sums each request's usage into one without the SDK's usage, with its
     * own never-filled cache-write field summed (null, or 0 under another summing rule): unknown, not zero writes.
     */
    @Test
    void cacheWritesOfAToolCallingExchangeAreUnknownNotZero() {
        ChatResponse summed = new ChatResponse(List.of(), ChatResponseMetadata.builder().model("gpt-6-luna")
                .usage(new DefaultUsage(10_000, 1_000, 11_000, null, 2_000L, 0L)).build());

        LlmUsage usage = LlmUsage.of(summed, "openai", 5, withLuna);

        assertThat(usage.cacheWriteTokens()).isNull();
        assertThat(usage.cachedInputTokens()).isEqualTo(2_000);
        // Unknown writes are billed as plain input: 8,000 * 0.10 + 2,000 * 0.01 + 1,000 * 0.50 = 1,320 per million.
        assertThat(usage.costUsd()).isEqualByComparingTo("0.00132");
    }

    @Test
    void datedSnapshotIsPricedAsTheLongestConfiguredPrefixAtTheSameProvider() {
        assertThat(pricing.priceKey("openai", "gpt-5.4-mini-2026-03-17")).isEqualTo("gpt-5.4-mini");
        assertThat(pricing.priceKey("openai", "gpt-5.5-2026-06-01")).isEqualTo("gpt-5.5");
        assertThat(pricing.priceKey("groq", "openai/gpt-oss-120b")).isEqualTo("groq:openai/gpt-oss-120b");
        assertThat(pricing.cost("groq", "openai/gpt-oss-120b", 1_000_000, null, null, 1_000_000))
                .isEqualByComparingTo("0.75");
    }

    @Test
    void unknownModelCostsNothingRatherThanFailingTheAnalysis() {
        assertThat(pricing.cost("openai", "some-other-model", 1_000, 0, null, 1_000))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void usageAddsUpCostPerModel() {
        LlmUsage mini = new LlmUsage("gpt-5.4-mini", 10, 5, null, 1, 3, new BigDecimal("0.01"),
                Map.of("gpt-5.4-mini", new BigDecimal("0.01")));
        LlmUsage big = new LlmUsage("gpt-5.5", 10, 5, null, 1, 3, new BigDecimal("0.10"),
                Map.of("gpt-5.5", new BigDecimal("0.10")));

        LlmUsage total = mini.plus(big).plus(mini);

        assertThat(total.costUsd()).isEqualByComparingTo("0.12");
        assertThat(total.costByModel()).containsEntry("gpt-5.4-mini", new BigDecimal("0.02"));
        assertThat(total.calls()).isEqualTo(3);
    }

    @Test
    void aConfiguredModelWithoutAPriceOrProviderFailsAtStartup() {
        assertThatThrownBy(() -> new LlmProperties(props.providers(), props.models(), props.escalation(),
                props.fallback(), Map.of("gpt-5.4-mini", TestLlm.price("1", "1", "1"))))
                .hasMessageContaining("no price for model gpt-5.5");
        assertThatThrownBy(() -> new LlmProperties(props.providers(), props.models(), props.escalation(),
                new LlmProperties.Fallback("mistral:small", 1, Duration.ofSeconds(1)),
                Map.of("gpt-5.4-mini", TestLlm.price("1", "1", "1"), "gpt-5.5", TestLlm.price("1", "1", "1"),
                        "mistral:small", TestLlm.price("1", "1", "1"))))
                .hasMessageContaining("unknown provider in mistral:small");
    }
}
