package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.LlmPricing;
import com.finsentinel.platform.llm.LlmUsage;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * One model call whose answer is a record, constrained by its JSON Schema (OpenAI structured outputs, strict mode),
 * optionally with tools. Shared by every agent.
 */
final class StructuredOutput {

    private StructuredOutput() {
    }

    record Answer<T>(T value, LlmUsage usage) {
    }

    /**
     * @param model overrides only the model: ChatClient starts from the model's configured options (such as
     *              {@code reasoning-effort: none}) and combines this onto them
     */
    static <T> Answer<T> ask(ChatClient chat, String model, String system, String user, List<ToolCallback> tools,
                             Class<T> type, LlmPricing pricing) {
        long start = System.nanoTime();
        ChatClient.ChatClientRequestSpec request = chat.prompt()
                .system(system)
                .user(user)
                .options(ChatOptions.builder().model(model))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
        if (!tools.isEmpty()) {
            request = request.toolCallbacks(tools);
        }
        ResponseEntity<ChatResponse, T> response = request.call().responseEntity(type);
        if (response.entity() == null) {
            // Refusal or truncated output: nothing to repair, let the queue retry.
            throw new IllegalStateException("Model returned no parseable " + type.getSimpleName());
        }
        return new Answer<>(response.entity(),
                LlmUsage.of(response.response(), (System.nanoTime() - start) / 1_000_000, pricing));
    }
}
