package com.finsentinel.platform.cases;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** One line of a case's audit trail. */
public record CaseEvent(long eventId, Instant at, String type, String fromStage, String toStage, String actor,
                        JsonNode detail) {
}
