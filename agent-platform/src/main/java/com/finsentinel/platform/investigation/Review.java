package com.finsentinel.platform.investigation;

import java.util.List;

/**
 * The reviewer's verdict on a report (the four-eyes check before an analyst sees it).
 *
 * @param issues what must change, phrased for the report writer; empty when approved
 */
public record Review(boolean approved, List<String> issues, LlmUsage usage) {

    public Review {
        issues = List.copyOf(issues);
    }
}
