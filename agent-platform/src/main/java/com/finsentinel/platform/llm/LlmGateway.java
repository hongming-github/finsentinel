package com.finsentinel.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every model call of the platform: one structured answer (a record, constrained by its JSON Schema where the
 * provider can), optionally with tools, priced at list price. When the model's provider fails (any OpenAI SDK error:
 * HTTP error status, rate limit, out of credit, network) or its circuit is open, the call goes to the fallback model
 * at another provider. A bad answer from a healthy provider is not a provider failure: it goes back to the caller's
 * validation and repair, not to another provider.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final LlmProviders providers;
    private final LlmPricing pricing;
    private final ModelRef fallback;
    private final LlmProperties.Fallback fallbackConfig;
    private final Clock clock;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    LlmGateway(LlmProviders providers, LlmPricing pricing, LlmProperties props) {
        this(providers, pricing, props, Clock.systemUTC());
    }

    public LlmGateway(LlmProviders providers, LlmPricing pricing, LlmProperties props, Clock clock) {
        this.providers = providers;
        this.pricing = pricing;
        this.fallback = ModelRef.parse(props.fallback().model());
        this.fallbackConfig = props.fallback();
        this.clock = clock;
    }

    /**
     * @param model the model for this call; the fallback model takes over if its provider fails
     * @return the answer, its cost, and the model that actually answered
     */
    public <T> Answer<T> ask(ModelRef model, String system, String user, List<ToolCallback> tools, Class<T> type) {
        List<ModelRef> route = model.provider().equals(fallback.provider()) ? List.of(model) : List.of(model, fallback);
        RuntimeException lastFailure = null;
        for (ModelRef ref : route) {
            LlmProviders.Provider provider = providers.get(ref.provider());
            CircuitBreaker breaker = breaker(ref.provider());
            boolean lastResort = ref == route.getLast();
            if (!provider.available() || (!breaker.allowsCall() && !lastResort)) {
                continue;
            }
            try {
                Answer<T> answer = call(provider, ref, system, user, tools, type);
                breaker.success();
                if (ref != model) {
                    log.warn("{} answered in place of {} (provider failure or open circuit)", ref, model);
                }
                return answer;
            } catch (ProviderFailure e) {
                breaker.failure();
                lastFailure = e;
                log.warn("Provider {} failed for {}: {}", ref.provider(), ref, e.getCause().toString());
            }
        }
        throw new IllegalStateException("no LLM provider answered for " + model, lastFailure);
    }

    public record Answer<T>(T value, LlmUsage usage, ModelRef answeredBy) {
    }

    private <T> Answer<T> call(LlmProviders.Provider provider, ModelRef ref, String system, String user,
                               List<ToolCallback> tools, Class<T> type) {
        long start = System.nanoTime();
        // Only the model is set per call: ChatClient starts from the provider's configured options and combines this.
        ChatClient.ChatClientRequestSpec request = provider.chat().prompt()
                .system(system)
                .user(user)
                .options(ChatOptions.builder().model(ref.model()));
        if (tools.isEmpty() || provider.config().structuredOutputWithTools()) {
            // Schema enforced by the provider. Otherwise Spring AI describes the format in the prompt, and the
            // caller's validation checks what comes back.
            request = request.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
        }
        if (!tools.isEmpty()) {
            request = request.toolCallbacks(tools);
        }
        ResponseEntity<ChatResponse, T> response;
        try {
            response = request.call().responseEntity(type);
        } catch (RuntimeException e) {
            if (isProviderFailure(e)) {
                throw new ProviderFailure(e);
            }
            throw e;
        }
        if (response.entity() == null) {
            // Refusal or truncated output: nothing to repair, let the queue retry.
            throw new IllegalStateException("Model returned no parseable " + type.getSimpleName());
        }
        return new Answer<>(response.entity(),
                LlmUsage.of(response.response(), ref.provider(), (System.nanoTime() - start) / 1_000_000, pricing), ref);
    }

    /** Errors raised by the OpenAI SDK (which also serves the OpenAI-compatible providers) anywhere in the chain. */
    static boolean isProviderFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.openai.errors.OpenAIException) {
                return true;
            }
        }
        return false;
    }

    private CircuitBreaker breaker(String provider) {
        return breakers.computeIfAbsent(provider,
                p -> new CircuitBreaker(fallbackConfig.failureThreshold(), fallbackConfig.openFor(), clock));
    }

    private static final class ProviderFailure extends RuntimeException {
        ProviderFailure(Throwable cause) {
            super(cause);
        }
    }
}
