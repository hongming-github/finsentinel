package com.finsentinel.batch;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AccountBaselineEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.spark.api.java.function.ForeachPartitionFunction;
import org.apache.spark.sql.Row;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Publishes baselines to {@link Topics#ACCOUNT_BASELINES} as registry Avro, keyed by tenant|account (the stream job's
 * key; the topic is compacted, so the latest baseline per account stays). Runs on the executors, one producer per
 * partition, and waits for every acknowledgement: a failed send fails the task, Spark retries it, and a duplicate
 * baseline is harmless (the stream job keeps the newest by as-of date).
 */
final class BaselinePublisher implements ForeachPartitionFunction<Row> {

    /** How executors get a registry client: serialisable, so it travels with the task. */
    interface RegistryFactory extends Supplier<SchemaRegistry>, Serializable {
    }

    private final String bootstrapServers;
    private final RegistryFactory registry;

    BaselinePublisher(String bootstrapServers, RegistryFactory registry) {
        this.bootstrapServers = bootstrapServers;
        this.registry = registry;
    }

    @Override
    public void call(Iterator<Row> rows) throws ExecutionException, InterruptedException {
        AvroSerde<AccountBaselineEvent> serde = AvroSerde.forSubject(registry.get(),
                Contracts.ACCOUNT_BASELINES_SUBJECT, AccountBaselineEvent.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.CLIENT_ID_CONFIG, "batch-job-baselines"),
                new StringSerializer(), new ByteArraySerializer())) {
            List<Future<RecordMetadata>> sends = new ArrayList<>();
            while (rows.hasNext()) {
                AccountBaseline baseline = toBaseline(rows.next());
                sends.add(producer.send(new ProducerRecord<>(Topics.ACCOUNT_BASELINES, baseline.accountKey(),
                        serde.serialize(Contracts.toAvro(baseline)))));
            }
            producer.flush();
            for (Future<RecordMetadata> send : sends) {
                send.get();
            }
        }
    }

    static AccountBaseline toBaseline(Row row) {
        return new AccountBaseline(
                row.getAs("tenantId"), row.getAs("accountId"),
                date(row.getAs("asOf")).atStartOfDay(ZoneOffset.UTC).toInstant(),
                row.<Integer>getAs("windowDays"), row.<Long>getAs("sampleSize"),
                row.<Double>getAs("medianAmount"), row.<Double>getAs("robustStdDev"),
                row.<Double>getAs("meanAmount"), row.<Double>getAs("p95Amount"), row.<Double>getAs("maxAmount"),
                instant(row.getAs("computedAt")));
    }

    // Spark hands out java.sql types, or java.time ones with spark.sql.datetime.java8API.enabled; accept both.
    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp t ? t.toInstant() : (Instant) value;
    }
}
