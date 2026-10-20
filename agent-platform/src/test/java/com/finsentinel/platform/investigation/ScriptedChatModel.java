package com.finsentinel.platform.investigation;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
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
public class ScriptedChatModel implements ChatModel {

    private final JsonMapper json;
    private final Deque<Object> replies;
    public final List<String> prompts = new CopyOnWriteArrayList<>();
    public final List<String> systemPrompts = new CopyOnWriteArrayList<>();
    public final List<Set<String>> toolsOffered = new CopyOnWriteArrayList<>();
    public final List<String> modelsAsked = new CopyOnWriteArrayList<>();
    /** Every message of each call after the system message, as "USER: text" or "ASSISTANT: text". */
    public final List<List<String>> conversations = new CopyOnWriteArrayList<>();
    /** Whether each call asked the provider to enforce a JSON Schema (native structured output). */
    public final List<Boolean> schemaEnforced = new CopyOnWriteArrayList<>();

    public ScriptedChatModel(JsonMapper json, Object... replies) {
        this.json = json;
        this.replies = new ArrayDeque<>(List.of(replies));
    }

    /** Like a real tool-capable model; otherwise ChatClient builds plain options and drops the tool callbacks. */
    @Override
    public ChatOptions getOptions() {
        return OpenAiChatOptions.builder().build();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt.getUserMessage().getText());
        systemPrompts.add(prompt.getSystemMessage().getText());
        conversations.add(prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() != org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .map(m -> m.getMessageType().name() + ": " + m.getText()).toList());
        toolsOffered.add(prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                ? options.getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet())
                : Set.of());
        String model = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        modelsAsked.add(model);
        schemaEnforced.add(prompt.getOptions() instanceof StructuredOutputChatOptions s && s.getOutputSchema() != null);
        String reply = json.writeValueAsString(replies.removeFirst());
        // Report usage like a provider does, so cost accounting has something to price.
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))),
                ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(5, 3)).build());
    }
}
