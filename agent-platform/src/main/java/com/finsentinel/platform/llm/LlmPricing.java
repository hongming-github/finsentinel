package com.finsentinel.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * Turns token counts into dollars at list price. The provider reports a dated snapshot ("gpt-5.4-mini-2026-03-17"),
 * priced by the longest configured name it starts with. Cached input is part of the prompt tokens and is billed at
 * the cached rate instead of the input rate.
 */
@Component
public class LlmPricing {

    private static final Logger log = LoggerFactory.getLogger(LlmPricing.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmProperties props;

    public LlmPricing(LlmProperties props) {
        this.props = props;
    }

    /** The configured model name this response model is priced as; the response name itself if none matches. */
    public String priceKey(String model) {
        if (model == null) {
            return "unknown";
        }
        return props.prices().keySet().stream()
                .filter(model::startsWith)
                .max(Comparator.comparingInt(String::length))
                .orElse(model);
    }

    public BigDecimal cost(String model, Integer inputTokens, Integer cachedInputTokens, Integer outputTokens) {
        LlmProperties.Price price = props.prices().get(priceKey(model));
        if (price == null) {
            log.warn("No price for model {}; its calls are counted at zero cost", model);
            return BigDecimal.ZERO;
        }
        long input = inputTokens == null ? 0 : inputTokens;
        long cached = cachedInputTokens == null ? 0 : Math.min(cachedInputTokens, input);
        long output = outputTokens == null ? 0 : outputTokens;
        return price.input().multiply(BigDecimal.valueOf(input - cached))
                .add(price.cachedInput().multiply(BigDecimal.valueOf(cached)))
                .add(price.output().multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
    }
}
