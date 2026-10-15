package com.finsentinel.stream;

import com.finsentinel.common.Alert;

/** An alert and the trace context it is published with: the detection span, or the transaction's when tracing is off. */
public record TracedAlert(Alert alert, String traceparent) {
}
