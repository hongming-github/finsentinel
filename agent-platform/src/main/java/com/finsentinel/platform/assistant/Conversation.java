package com.finsentinel.platform.assistant;

import java.time.Instant;

/**
 * An analyst's conversation about one case (ADR 0028). Tenant, case and analyst are fixed when it is created; the
 * conversation id is only a handle, every request is checked against the token again.
 *
 * @param turns          turns stored so far: the version a new turn must name in {@code If-Match}
 * @param summary        older turns folded into a summary; null until the first fold
 * @param summaryThrough turns 1..summaryThrough are in the summary, the rest are sent word for word
 */
public record Conversation(String conversationId, String tenantId, String caseId, String analystSubject,
                           String analystUsername, int turns, String summary, int summaryThrough, Instant createdAt,
                           Instant updatedAt) {
}
