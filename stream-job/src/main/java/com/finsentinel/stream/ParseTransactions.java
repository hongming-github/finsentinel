package com.finsentinel.stream;

import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.TransactionEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.function.SerializableSupplier;

/**
 * Decodes Avro transactions (ADR 0018). A message that can never be read goes to a side output (and from there, as the
 * original bytes, to the DLQ topic) instead of failing the job: one bad producer must not stop detection. A schema
 * registry that cannot answer is different: the exception fails the task, Flink restarts it from the last checkpoint,
 * and no good transaction is discarded.
 */
class ParseTransactions extends ProcessFunction<RawMessage, TracedTransaction> {

    static final OutputTag<byte[]> INVALID = new OutputTag<>("invalid-transactions") {
    };

    private final SerializableSupplier<SchemaRegistry> registry;
    private transient AvroSerde<TransactionEvent> serde;

    ParseTransactions(SerializableSupplier<SchemaRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void open(OpenContext openContext) {
        serde = AvroSerde.forSubject(registry.get(), Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
    }

    @Override
    public void processElement(RawMessage raw, Context ctx, Collector<TracedTransaction> out) {
        Transaction txn;
        try {
            txn = Contracts.fromAvro(serde.deserialize(raw.value()));
        } catch (AvroSerde.InvalidMessageException e) {
            ctx.output(INVALID, raw.value());
            return;
        }
        out.collect(new TracedTransaction(txn, raw.traceparent()));
    }
}
