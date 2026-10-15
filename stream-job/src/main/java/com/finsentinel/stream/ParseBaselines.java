package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AccountBaselineEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.function.SerializableSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes Avro account baselines from the batch job (ADR 0019). An unreadable baseline is logged and skipped: the
 * account keeps its previous baseline (or its streaming history) and tomorrow's run replaces it, so it is not worth a
 * dead-letter topic. A registry outage fails the task, as for transactions.
 */
class ParseBaselines extends ProcessFunction<RawMessage, AccountBaseline> {

    private static final Logger log = LoggerFactory.getLogger(ParseBaselines.class);

    private final SerializableSupplier<SchemaRegistry> registry;
    private transient AvroSerde<AccountBaselineEvent> serde;

    ParseBaselines(SerializableSupplier<SchemaRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void open(OpenContext openContext) {
        serde = AvroSerde.forSubject(registry.get(), Contracts.ACCOUNT_BASELINES_SUBJECT, AccountBaselineEvent.class);
    }

    @Override
    public void processElement(RawMessage raw, Context ctx, Collector<AccountBaseline> out) {
        try {
            out.collect(Contracts.fromAvro(serde.deserialize(raw.value())));
        } catch (AvroSerde.InvalidMessageException e) {
            log.warn("Skipping unreadable account baseline: {}", e.getMessage());
        }
    }
}
