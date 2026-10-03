package com.finsentinel.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    @Autowired
    LlmProviders(LlmProperties props, ChatClient.Builder openAi, ToolCallingManager toolCallingManager) {
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
                        .toolCallingManager(toolCallingManager)
                        .build();
                built.put(name, new Provider(ChatClient.builder(model).build(), config, true));
            }
        });
        this.providers = Map.copyOf(built);
    }

    /** For tests: providers backed by given clients. */
    public LlmProviders(Map<String, Provider> providers) {
        this.providers = Map.copyOf(providers);
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
