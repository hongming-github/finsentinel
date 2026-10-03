package com.finsentinel.platform.investigation;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
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

    static <T> Answer<T> ask(ChatClient chat, String system, String user, List<ToolCallback> tools, Class<T> type) {
        long start = System.nanoTime();
        ChatClient.ChatClientRequestSpec request = chat.prompt()
                .system(system)
                .user(user)
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
        if (!tools.isEmpty()) {
            request = request.toolCallbacks(tools);
        }
        ResponseEntity<ChatResponse, T> response = request.call().responseEntity(type);
        if (response.entity() == null) {
            // Refusal or truncated output: nothing to repair, let the queue retry.
            throw new IllegalStateException("Model returned no parseable " + type.getSimpleName());
        }
        return new Answer<>(response.entity(), LlmUsage.of(response.response(), (System.nanoTime() - start) / 1_000_000));
    }
}
