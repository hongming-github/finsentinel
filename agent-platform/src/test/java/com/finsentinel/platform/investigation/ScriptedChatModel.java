package com.finsentinel.platform.investigation;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A model that returns the given answers in order, as JSON text, and records what it was asked and which tools it
 * was offered. Drives agents without network, cost or randomness.
 */
class ScriptedChatModel implements ChatModel {

    private final JsonMapper json;
    private final Deque<Object> replies;
    final List<String> prompts = new CopyOnWriteArrayList<>();
    final List<String> systemPrompts = new CopyOnWriteArrayList<>();
    final List<Set<String>> toolsOffered = new CopyOnWriteArrayList<>();
    final List<String> modelsAsked = new CopyOnWriteArrayList<>();

    ScriptedChatModel(JsonMapper json, Object... replies) {
        this.json = json;
        this.replies = new ArrayDeque<>(List.of(replies));
    }

    /**
     * Like a real tool-capable model; otherwise ChatClient builds plain options and drops the tool callbacks. Spring AI
     * 2.0 reads {@code getOptions()}; {@code getDefaultOptions()} is the 1.x name.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt.getUserMessage().getText());
        systemPrompts.add(prompt.getSystemMessage().getText());
        toolsOffered.add(prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                ? options.getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet())
                : Set.of());
        String model = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        modelsAsked.add(model);
        String reply = json.writeValueAsString(replies.removeFirst());
        // Report usage like a provider does, so cost accounting has something to price.
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))),
                ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(5, 3)).build());
    }
}
