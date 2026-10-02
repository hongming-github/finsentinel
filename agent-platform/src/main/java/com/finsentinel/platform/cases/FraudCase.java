package com.finsentinel.platform.cases;

import com.finsentinel.common.Severity;

import java.time.Instant;

/**
 * @param analysisRun bumped whenever the case needs a fresh analysis; an analysis is only accepted for the run it
 *                    was started on
 * @param version     optimistic-locking version for analyst decisions; bumped on every state change
 */
public record FraudCase(String caseId, String tenantId, String accountId, String customerId, CaseStage stage,
                        Severity priority, int attempts, int analysisRun, String analystNote, int version,
                        Instant openedAt, Instant updatedAt, Instant closedAt, Decision decision, String decidedBy) {
}
