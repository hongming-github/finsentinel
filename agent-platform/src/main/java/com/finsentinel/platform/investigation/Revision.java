package com.finsentinel.platform.investigation;

import java.util.List;

/** A report the reviewer sent back, with what must change. */
public record Revision(CaseReport previousReport, List<String> issues) {

    public Revision {
        issues = List.copyOf(issues);
    }
}
