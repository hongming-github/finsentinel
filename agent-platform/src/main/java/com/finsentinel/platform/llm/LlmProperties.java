package com.finsentinel.platform.llm;

import com.finsentinel.common.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bound from {@code finsentinel.llm.*}: providers, which model does which job, when a case is escalated to a stronger
 * one, where calls go when a provider fails, and list prices. Models are written {@code provider:model} (a bare name is
 * OpenAI). Invalid values fail at startup, including a model without a price or at an unknown provider.
 *
 * @param providers  OpenAI-compatible endpoints by name; {@code openai} is the auto-configured client
 * @param models     the model of each role on an ordinary case
 * @param escalation the stronger model and the roles that use it on escalated cases
 * @param fallback   where a call goes when its provider fails or its circuit is open
 * @param prices     USD per million tokens, keyed like model references; a dated snapshot is priced by the longest
 *                   configured name it starts with, at the same provider
 */
@ConfigurationProperties("finsentinel.llm")
public record LlmProperties(Map<String, Provider> providers, Models models, Escalation escalation, Fallback fallback,
                            Map<String, Price> prices) {

    public LlmProperties {
        require(providers != null && models != null && escalation != null && fallback != null && prices != null,
                "providers, models, escalation, fallback and prices are required");
        require(providers.containsKey(ModelRef.DEFAULT_PROVIDER), "providers.openai is required");
        List<String> refs = new ArrayList<>(models.byRole().values());
        refs.add(escalation.model());
        refs.add(fallback.model());
        for (String ref : refs) {
            require(prices.containsKey(ModelRef.parse(ref).toString()), "no price for model " + ref);
            require(providers.containsKey(ModelRef.parse(ref).provider()), "unknown provider in " + ref);
        }
    }

    /** The configured model reference of a role on an ordinary case. */
    public ModelRef model(AgentRole role) {
        return ModelRef.parse(models.byRole().get(role));
    }

    /**
     * @param baseUrl                    OpenAI-compatible endpoint; null for {@code openai} (spring.ai.openai.*)
     * @param apiKey                     null for {@code openai}; a blank key marks the provider unavailable
     * @param structuredOutputWithTools  whether the provider can enforce a JSON Schema in a request that also offers
     *                                   tools (Groq cannot); without it the format is described in the prompt and the
     *                                   report validator checks the result
     * @param extraBody                  provider-specific request fields sent with every call
     * @param schemaFormatsOnNonStrings  whether the provider accepts the JSON Schema "format" keyword on numbers
     *                                   (OpenAI does; Groq rejects "format": "int32" and fails the call). Default true
     */
    public record Provider(String baseUrl, String apiKey, boolean structuredOutputWithTools,
                           Map<String, Object> extraBody, Boolean schemaFormatsOnNonStrings) {

        public Provider {
            extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
            schemaFormatsOnNonStrings = schemaFormatsOnNonStrings == null || schemaFormatsOnNonStrings;
        }
    }

    public record Models(String investigator, String specialist, String writer, String reviewer, String assistant) {

        public Models {
            require(investigator != null && specialist != null && writer != null && reviewer != null
                    && assistant != null, "a model for every role is required");
        }

        Map<AgentRole, String> byRole() {
            Map<AgentRole, String> out = new EnumMap<>(AgentRole.class);
            out.put(AgentRole.INVESTIGATOR, investigator);
            out.put(AgentRole.SPECIALIST, specialist);
            out.put(AgentRole.WRITER, writer);
            out.put(AgentRole.REVIEWER, reviewer);
            out.put(AgentRole.ASSISTANT, assistant);
            return out;
        }
    }

    /**
     * @param roles      roles that switch to {@code model} on an escalated case; empty turns escalation off
     * @param onTransfer escalate cases that involve a transfer
     * @param onSeverity escalate cases with an alert of one of these severities
     */
    public record Escalation(String model, Set<AgentRole> roles, boolean onTransfer, Set<Severity> onSeverity) {

        public Escalation {
            require(model != null, "escalation.model is required");
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            onSeverity = onSeverity == null ? Set.of() : Set.copyOf(onSeverity);
        }
    }

    /**
     * @param model            the model every role falls back to; at another provider than the primary models
     * @param failureThreshold consecutive provider failures that open a provider's circuit
     * @param openFor          how long an open circuit sends calls straight to the fallback before one trial call
     */
    public record Fallback(String model, int failureThreshold, Duration openFor) {

        public Fallback {
            require(model != null, "fallback.model is required");
            require(failureThreshold > 0, "fallback.failure-threshold must be positive");
            require(openFor != null && !openFor.isNegative() && !openFor.isZero(), "fallback.open-for must be positive");
        }
    }

    /**
     * @param cacheWrite input tokens written to the provider's prompt cache, for models that charge for it (OpenAI
     *                   from GPT-5.6 on, at 1.25x input); absent means writes cost the input rate
     */
    public record Price(BigDecimal input, BigDecimal cachedInput, BigDecimal cacheWrite, BigDecimal output) {

        public Price {
            require(input != null && cachedInput != null && output != null
                    && input.signum() >= 0 && cachedInput.signum() >= 0 && output.signum() >= 0
                    && (cacheWrite == null || cacheWrite.signum() >= 0),
                    "prices must be zero or positive");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("finsentinel.llm." + message);
        }
    }
}
