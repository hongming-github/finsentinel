package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;

import java.time.Instant;

/** An alert as the platform tracks it: the stream job's payload plus our own lifecycle fields. */
public record StoredAlert(Alert alert, AlertStatus status, Instant receivedAt) {
}
