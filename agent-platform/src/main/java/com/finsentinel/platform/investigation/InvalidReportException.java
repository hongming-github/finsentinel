package com.finsentinel.platform.investigation;

import java.util.List;

/** The model's report still failed validation after the allowed repairs; the alert goes back to the queue. */
class InvalidReportException extends RuntimeException {

    InvalidReportException(List<String> problems) {
        super("Report rejected after repair: " + String.join("; ", problems));
    }
}
