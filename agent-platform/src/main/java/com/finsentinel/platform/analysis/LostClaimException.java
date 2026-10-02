package com.finsentinel.platform.analysis;

/**
 * The worker no longer holds the case's claim (it timed out and another worker took the case, or the case left
 * ANALYSIS). The worker must stop: nothing it produces from here on may be stored.
 */
public class LostClaimException extends RuntimeException {

    LostClaimException(CaseRun run) {
        super("claim on case " + run.caseId() + " (run " + run.run() + ", claimed at " + run.claimedAt() + ") is lost");
    }
}
