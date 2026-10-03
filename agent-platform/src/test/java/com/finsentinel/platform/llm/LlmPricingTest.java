package com.finsentinel.platform.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPricingTest {

    private final LlmProperties props = TestLlm.properties(Set.of());
    private final LlmPricing pricing = new LlmPricing(props);

    @Test
    void cachedInputIsBilledAtTheCachedRateNotTwice() {
        // 10,000 prompt tokens of which 8,000 cached, 1,000 output:
        // 2,000 * 0.75 + 8,000 * 0.075 + 1,000 * 4.50 = 1,500 + 600 + 4,500 = 6,600 per million = 0.0066
        assertThat(pricing.cost("openai", "gpt-5.4-mini", 10_000, 8_000, 1_000)).isEqualByComparingTo("0.0066");
    }

    @Test
    void datedSnapshotIsPricedAsTheLongestConfiguredPrefixAtTheSameProvider() {
        assertThat(pricing.priceKey("openai", "gpt-5.4-mini-2026-03-17")).isEqualTo("gpt-5.4-mini");
        assertThat(pricing.priceKey("openai", "gpt-5.5-2026-06-01")).isEqualTo("gpt-5.5");
        assertThat(pricing.priceKey("groq", "openai/gpt-oss-120b")).isEqualTo("groq:openai/gpt-oss-120b");
        assertThat(pricing.cost("groq", "openai/gpt-oss-120b", 1_000_000, null, 1_000_000)).isEqualByComparingTo("0.75");
    }

    @Test
    void unknownModelCostsNothingRatherThanFailingTheAnalysis() {
        assertThat(pricing.cost("openai", "some-other-model", 1_000, 0, 1_000)).isEqualByComparingTo(BigDecimal.ZERO);
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
