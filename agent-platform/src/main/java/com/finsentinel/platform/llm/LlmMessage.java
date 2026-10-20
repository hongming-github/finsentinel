package com.finsentinel.platform.llm;

/**
 * One message after the system message of a model call: the user's, or an earlier answer of the model. A conversation
 * goes out as these messages in order, so the provider can reuse the cached prefix from one turn to the next (it caches
 * at message boundaries, ADR 0023).
 */
public record LlmMessage(Role role, String text) {

    public enum Role { USER, ASSISTANT }

    public LlmMessage {
        if (role == null || text == null) {
            throw new IllegalArgumentException("a message needs a role and a text");
        }
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text);
    }

    public static LlmMessage assistant(String text) {
        return new LlmMessage(Role.ASSISTANT, text);
    }
}
