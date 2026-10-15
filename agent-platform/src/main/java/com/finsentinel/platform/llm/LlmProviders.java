package com.finsentinel.platform.llm;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * A chat client per configured provider. {@code openai} is Spring AI's auto-configured client; every other provider is
 * an OpenAI-compatible endpoint (Groq) with its own base URL and key. A provider without a key is unavailable: calls
 * skip it, so the platform runs with whichever providers it has credentials for.
 */
@Component
public class LlmProviders {

    private static final Logger log = LoggerFactory.getLogger(LlmProviders.class);

    private final Map<String, Provider> providers;
    private final ObservationRegistry observations;

    @Autowired
    LlmProviders(LlmProperties props, ChatClient.Builder openAi, ObjectProvider<ObservationRegistry> observations) {
        // The builder's own tool-calling manager inherits this registry, so tool calls on these providers are traced too.
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.observations = registry;
        Map<String, Provider> built = new HashMap<>();
        props.providers().forEach((name, config) -> {
            if (ModelRef.DEFAULT_PROVIDER.equals(name)) {
                built.put(name, new Provider(openAi.build(), config, true));
            } else if (config.apiKey() == null || config.apiKey().isBlank()) {
                log.warn("LLM provider {} has no API key: unavailable", name);
                built.put(name, new Provider(null, config, false));
            } else {
                OpenAiChatModel model = OpenAiChatModel.builder()
                        .options(OpenAiChatOptions.builder().baseUrl(config.baseUrl()).apiKey(config.apiKey())
                                .extraBody(config.extraBody().isEmpty() ? null : config.extraBody())
                                .build())
                        .observationRegistry(registry)
                        .build();
                built.put(name, new Provider(ChatClient.builder(model).build(), config, true));
            }
        });
        this.providers = Map.copyOf(built);
    }

    /** For tests: providers backed by given clients. */
    public LlmProviders(Map<String, Provider> providers) {
        this.providers = Map.copyOf(providers);
        this.observations = ObservationRegistry.NOOP;
    }

    /** Where the gateway records each call (ADR 0020): a span per call in traces, a timer per model in metrics. */
    ObservationRegistry observations() {
        return observations;
    }

    public Provider get(String name) {
        Provider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("unknown LLM provider " + name);
        }
        return provider;
    }

    public record Provider(ChatClient chat, LlmProperties.Provider config, boolean available) {
    }
}
