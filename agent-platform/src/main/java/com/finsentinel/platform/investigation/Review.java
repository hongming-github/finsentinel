package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.LlmUsage;

import java.util.List;

/**
 * The reviewer's verdict on a report (the four-eyes check before an analyst sees it).
 *
 * @param issues         what must change, phrased for the report writer; empty when approved
 * @param skippedBecause set when no review took place (risk-based review), so nobody mistakes the report for a
 *                       reviewed one; null when a reviewer read it
 */
public record Review(boolean approved, List<String> issues, LlmUsage usage, String skippedBecause) {

    public Review {
        issues = List.copyOf(issues);
    }

    public Review(boolean approved, List<String> issues, LlmUsage usage) {
        this(approved, issues, usage, null);
    }

    public static Review skipped(String because) {
        return new Review(true, List.of(), LlmUsage.NONE, because);
    }

    public boolean reviewed() {
        return skippedBecause == null;
    }

    /** One line for logs: skipped, approved, or what the reviewer asked for. */
    public String verdict() {
        if (!reviewed()) {
            return "skipped (" + skippedBecause + ")";
        }
        return approved ? "approved" : "sent back " + issues;
    }
}
