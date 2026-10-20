package com.finsentinel.platform.llm;

/**
 * The jobs a model does here. Each has its own model setting, so cost and quality can be traded per job. ASSISTANT
 * answers an analyst's questions about a case (ADR 0028): condensing the question, answering it, summarising old turns.
 */
public enum AgentRole {
    INVESTIGATOR, SPECIALIST, WRITER, REVIEWER, ASSISTANT
}
