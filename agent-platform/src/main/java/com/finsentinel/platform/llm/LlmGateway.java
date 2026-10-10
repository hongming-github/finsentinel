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
        try {
            if (!tools.isEmpty() && !provider.config().structuredOutputWithTools()) {
                return inTwoSteps(provider, ref, system, user, tools, type, start);
            }
            // Schema enforced by the provider, with or without tools.
            ChatClient.ChatClientRequestSpec request = prompt(provider, ref, system, user)
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
            if (!tools.isEmpty()) {
                request = request.toolCallbacks(tools);
            }
            ResponseEntity<ChatResponse, T> response = request.call()
                    .responseEntity(ProviderOutputConverter.of(type, provider.config()));
            return new Answer<>(entity(response, type), LlmUsage.of(response.response(), ref.provider(),
                    (System.nanoTime() - start) / 1_000_000, pricing), ref);
        } catch (RuntimeException e) {
            if (isProviderFailure(e)) {
                throw new ProviderFailure(e);
            }
            throw e;
        }
    }

    static final String NOTES_INSTRUCTION = "\n\nWhen you have finished with the tools, do not answer in JSON. Write "
            + "your conclusion as plain notes: the disposition and risk score, each finding with the exact transaction "
            + "ids that support it, the recommended actions, and each regulatory point with the exact chunkIds it "
            + "relies on.";
    static final String STRUCTURE_INSTRUCTION = "\n\nTurn your notes into the required answer. Use only the facts, "
            + "transaction ids and chunkIds that appear in them.";

    /**
     * For a provider that cannot enforce a schema while offering tools (Groq): the tools run in a first call that ends
     * in plain notes, and a second call without tools turns the notes into the schema-enforced answer. Describing the
     * format in the prompt instead made gpt-oss "call a tool named json", which Groq rejects: every analysis failed
     * (phase 7 evaluation). The caller's validation still checks every id against what the tools returned.
     */
    private <T> Answer<T> inTwoSteps(LlmProviders.Provider provider, ModelRef ref, String system, String user,
                                     List<ToolCallback> tools, Class<T> type, long start) {
        ChatResponse investigation = prompt(provider, ref, system, user + NOTES_INSTRUCTION)
                .toolCallbacks(tools).call().chatResponse();
        String notes = investigation == null || investigation.getResult() == null ? ""
                : investigation.getResult().getOutput().getText();
        ResponseEntity<ChatResponse, T> structured = prompt(provider, ref, system,
                user + "\n\nYour notes:\n" + notes + STRUCTURE_INSTRUCTION)
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .call().responseEntity(ProviderOutputConverter.of(type, provider.config()));
        long latency = (System.nanoTime() - start) / 1_000_000;
        LlmUsage usage = LlmUsage.of(investigation, ref.provider(), 0, pricing)
                .plus(LlmUsage.of(structured.response(), ref.provider(), 0, pricing)).withLatency(latency);
        return new Answer<>(entity(structured, type), usage, ref);
    }

    /** Only the model is set per call: ChatClient starts from the provider's configured options and combines this. */
    private static ChatClient.ChatClientRequestSpec prompt(LlmProviders.Provider provider, ModelRef ref, String system,
                                                           String user) {
        return provider.chat().prompt().system(system).user(user).options(ChatOptions.builder().model(ref.model()));
    }

    private static <T> T entity(ResponseEntity<ChatResponse, T> response, Class<T> type) {
        if (response.entity() == null) {
            // Refusal or truncated output: nothing to repair, let the queue retry.
            throw new IllegalStateException("Model returned no parseable " + type.getSimpleName());
        }
        return response.entity();
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
