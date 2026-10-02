package com.finsentinel.platform.investigation;

import com.finsentinel.platform.alert.StoredAlert;

import java.time.Instant;

/** A claimed alert. {@code claimedAt} is the fencing token that must match when the result is written. */
record Claim(StoredAlert alert, Instant claimedAt) {
}
