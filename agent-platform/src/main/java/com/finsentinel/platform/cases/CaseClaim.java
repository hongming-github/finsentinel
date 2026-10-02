package com.finsentinel.platform.cases;

import java.time.Instant;

/**
 * A claimed case. {@code claimedAt} is the fencing token every write must match; {@code fraudCase().analysisRun()}
 * is the run this worker is analysing.
 */
public record CaseClaim(FraudCase fraudCase, Instant claimedAt) {
}
