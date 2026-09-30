package com.finsentinel.stream;

import com.finsentinel.common.Transaction;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * Parses raw JSON. Anything unparseable or missing a key field goes to a side output (and from
 * there to the DLQ topic) instead of failing the job: one bad producer must not stop detection.
 */
class ParseTransactions extends ProcessFunction<String, Transaction> {

    static final OutputTag<String> INVALID = new OutputTag<>("invalid-transactions") {
    };

    @Override
    public void processElement(String raw, Context ctx, Collector<Transaction> out) {
        Transaction txn;
        try {
            txn = Json.MAPPER.readValue(raw, Transaction.class);
        } catch (RuntimeException e) {
            ctx.output(INVALID, raw);
            return;
        }
        if (txn.txnId() == null || txn.tenantId() == null || txn.accountId() == null
                || txn.amount() == null || txn.eventTime() == null) {
            ctx.output(INVALID, raw);
            return;
        }
        out.collect(txn);
    }
}
