package com.finsentinel.platform.llm;

/** The jobs a model does here. Each has its own model setting, so cost and quality can be traded per job. */
public enum AgentRole {
    INVESTIGATOR, SPECIALIST, WRITER, REVIEWER
}
