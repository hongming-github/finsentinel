package com.finsentinel.platform.llm;

import com.finsentinel.common.Severity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** LLM configuration and a gateway over given chat models, for tests that must not reach a provider. */
public final class TestLlm {

    public static final LlmProperties.Provider OPENAI = new LlmProperties.Provider(null, null, true, Map.of(), true);
    public static final LlmProperties.Provider GROQ = new LlmProperties.Provider("https://groq.test", "key", false,
            Map.of(), false);

    private TestLlm() {
    }

    public static LlmProperties properties(Set<AgentRole> escalatedRoles) {
        return new LlmProperties(Map.of("openai", OPENAI, "groq", GROQ),
                new LlmProperties.Models("gpt-5.4-mini", "gpt-5.4-mini", "gpt-5.4-mini", "gpt-5.4-mini",
                        "gpt-5.4-mini"),
                new LlmProperties.Escalation("gpt-5.5", escalatedRoles, true, Set.of(Severity.HIGH)),
                new LlmProperties.Fallback("groq:openai/gpt-oss-120b", 2, Duration.ofSeconds(60)),
                Map.of("gpt-5.4-mini", price("0.75", "0.075", "4.50"),
                        "gpt-5.5", price("5.00", "0.50", "30.00"),
                        "groq:openai/gpt-oss-120b", price("0.15", "0.15", "0.60")));
    }

    /** A gateway whose OpenAI and Groq are the given models. */
    public static LlmGateway gateway(ChatModel openai, ChatModel groq, LlmProperties props, Clock clock) {
        LlmProviders providers = new LlmProviders(Map.of(
                "openai", new LlmProviders.Provider(ChatClient.builder(openai).build(), OPENAI, true),
                "groq", new LlmProviders.Provider(ChatClient.builder(groq).build(), GROQ, true)));
        return new LlmGateway(providers, new LlmPricing(props), props, clock);
    }

    public static LlmProperties.Price price(String in, String cached, String out) {
        return new LlmProperties.Price(new BigDecimal(in), new BigDecimal(cached), null, new BigDecimal(out));
    }
}
