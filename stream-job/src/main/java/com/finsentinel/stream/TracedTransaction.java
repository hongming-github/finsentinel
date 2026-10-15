package com.finsentinel.stream;

import com.finsentinel.common.Transaction;

/** A transaction and the trace it arrived in (null when the producer did not trace). */
public record TracedTransaction(Transaction txn, String traceparent) {
}
