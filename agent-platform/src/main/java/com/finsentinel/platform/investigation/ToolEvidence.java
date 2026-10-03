package com.finsentinel.platform.investigation;

/**
 * One tool call exactly as the model saw it: what it asked and the text it got back. The reviewer checks a report
 * against this, so it judges with the same information the author had.
 */
public record ToolEvidence(String tool, String input, String output) {
}
