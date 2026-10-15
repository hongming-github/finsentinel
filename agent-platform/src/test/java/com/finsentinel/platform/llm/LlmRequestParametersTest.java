package com.finsentinel.platform.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway actually sends to an OpenAI-compatible provider. Newer OpenAI models reject sampling parameters
 * (temperature, top_p); the platform must not send any, so a model upgrade is a configuration change. Checked on the
 * wire against a local stand-in for the provider: no model is called.
 */
class LlmRequestParametersTest {

    private static final String COMPLETION = """
            {"id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-5.4-mini",
             "choices": [{"index": 0, "finish_reason": "stop", "logprobs": null,
                          "message": {"role": "assistant", "content": "{\\"verdict\\": \\"ok\\"}", "refusal": null}}],
             "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}}
            """;

    private final List<String> requests = new ArrayList<>();
    private HttpServer provider;

    @BeforeEach
    void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
    }

    @AfterEach
    void stopProvider() {
        provider.stop(0);
    }

    record Verdict(String verdict) {
    }

    @Test
    void noSamplingParametersAreSent() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl("http://localhost:" + provider.getAddress().getPort() + "/v1")
                        .apiKey("test-key")
                        .build())
                .build();
        LlmProperties props = TestLlm.properties(Set.of());
        LlmGateway gateway = TestLlm.gateway(model, model, props, Clock.systemUTC());

        LlmGateway.Answer<Verdict> answer = gateway.ask(ModelRef.parse("gpt-5.4-mini"), "system", "user", List.of(),
                Verdict.class);

        assertThat(answer.value().verdict()).isEqualTo("ok");
        JsonNode sent = JsonMapper.builder().build().readTree(requests.getFirst());
        assertThat(sent.get("model").asString()).isEqualTo("gpt-5.4-mini");
        for (String sampling : List.of("temperature", "top_p", "frequency_penalty", "presence_penalty", "seed")) {
            assertThat(sent.has(sampling)).as(sampling + " in " + sent).isFalse();
        }
    }
}
