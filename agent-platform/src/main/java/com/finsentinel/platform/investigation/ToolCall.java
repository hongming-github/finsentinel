package com.finsentinel.platform.investigation;

/** Audit record of one tool invocation made by the agent. */
public record ToolCall(String tool, String arguments, int resultCount, long durationMs) {
}
