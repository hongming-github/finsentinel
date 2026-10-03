package com.finsentinel.platform.llm;

import com.finsentinel.common.Severity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPricingTest {

    private static final LlmProperties.Models MODELS = new LlmProperties.Models("gpt-5.4-mini", "gpt-5.4-mini",
            "gpt-5.4-mini", "gpt-5.4-mini");
    private static final LlmProperties.Escalation ESCALATION = new LlmProperties.Escalation("gpt-5.5",
            Set.of(AgentRole.INVESTIGATOR), true, Set.of(Severity.HIGH));

    private final LlmPricing pricing = new LlmPricing(new LlmProperties(MODELS, ESCALATION, Map.of(
            "gpt-5.4-mini", price("0.75", "0.075", "4.50"),
            "gpt-5.5", price("5.00", "0.50", "30.00"))));

    @Test
    void cachedInputIsBilledAtTheCachedRateNotTwice() {
        // 10,000 prompt tokens of which 8,000 cached, 1,000 output:
        // 2,000 * 0.75 + 8,000 * 0.075 + 1,000 * 4.50 = 1,500 + 600 + 4,500 = 6,600 per million = 0.0066
        assertThat(pricing.cost("gpt-5.4-mini", 10_000, 8_000, 1_000)).isEqualByComparingTo("0.0066");
    }

    @Test
    void datedSnapshotIsPricedAsTheLongestConfiguredPrefix() {
        assertThat(pricing.priceKey("gpt-5.4-mini-2026-03-17")).isEqualTo("gpt-5.4-mini");
        assertThat(pricing.priceKey("gpt-5.5-2026-06-01")).isEqualTo("gpt-5.5");
        assertThat(pricing.cost("gpt-5.5-2026-06-01", 1_000_000, null, 0)).isEqualByComparingTo("5.00");
    }

    @Test
    void unknownModelCostsNothingRatherThanFailingTheAnalysis() {
        assertThat(pricing.cost("some-other-model", 1_000, 0, 1_000)).isEqualByComparingTo(BigDecimal.ZERO);
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
    void aConfiguredModelWithoutAPriceFailsAtStartup() {
        assertThatThrownBy(() -> new LlmProperties(MODELS, ESCALATION, Map.of("gpt-5.4-mini", price("1", "1", "1"))))
                .hasMessageContaining("no price for escalation model gpt-5.5");
    }

    private static LlmProperties.Price price(String in, String cached, String out) {
        return new LlmProperties.Price(new BigDecimal(in), new BigDecimal(cached), new BigDecimal(out));
    }
}
