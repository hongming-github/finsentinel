package com.finsentinel.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * Turns token counts into dollars at list price. The provider reports the model that answered, often a dated snapshot
 * ("gpt-5.4-mini-2026-03-17"), priced by the longest configured name at the same provider that it starts with. Cached
 * input is part of the prompt tokens and is billed at the cached rate instead of the input rate.
 */
@Component
public class LlmPricing {

    private static final Logger log = LoggerFactory.getLogger(LlmPricing.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmProperties props;

    public LlmPricing(LlmProperties props) {
        this.props = props;
    }

    /** The configured price key for a model a provider reported; the reported reference itself if none matches. */
    public String priceKey(String provider, String reportedModel) {
        if (reportedModel == null) {
            return provider + ":unknown";
        }
        String reported = new ModelRef(provider, reportedModel).toString();
        return props.prices().keySet().stream()
                .filter(reported::startsWith)
                .max(Comparator.comparingInt(String::length))
                .orElse(reported);
    }

    /** Whether a configured model reference has a price of its own. */
    public boolean hasPrice(ModelRef model) {
        return props.prices().containsKey(model.toString());
    }

    public BigDecimal cost(String provider, String reportedModel, Integer inputTokens, Integer cachedInputTokens,
                           Integer outputTokens) {
        LlmProperties.Price price = props.prices().get(priceKey(provider, reportedModel));
        if (price == null) {
            log.warn("No price for {}:{}; its calls are counted at zero cost", provider, reportedModel);
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
