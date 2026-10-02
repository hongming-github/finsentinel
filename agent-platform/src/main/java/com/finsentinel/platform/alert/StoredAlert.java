package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;

import java.time.Instant;

/** An alert as the platform keeps it: the stream job's payload plus the case it was filed under. */
public record StoredAlert(Alert alert, String caseId, Instant receivedAt) {
}
