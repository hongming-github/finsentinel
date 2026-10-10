package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AlertEvent;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.util.function.SerializableSupplier;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;

/**
 * Avro {@code AlertEvent} (ADR 0018), keyed by tenant|account so all alerts for one account land on one partition, in
 * order. The sink fails at open, before any alert is written, if the alert schema is not registered.
 */
class AlertRecordSerializer implements KafkaRecordSerializationSchema<Alert> {

    private final String topic;
    private final SerializableSupplier<SchemaRegistry> registry;
    private transient AvroSerde<AlertEvent> serde;

    AlertRecordSerializer(String topic, SerializableSupplier<SchemaRegistry> registry) {
        this.topic = topic;
        this.registry = registry;
    }

    @Override
    public void open(SerializationSchema.InitializationContext context, KafkaSinkContext sinkContext) {
        serde = AvroSerde.forSubject(registry.get(), Contracts.ALERTS_SUBJECT, AlertEvent.class);
    }

    @Override
    public ProducerRecord<byte[], byte[]> serialize(Alert alert, KafkaSinkContext context, Long timestamp) {
        byte[] key = (alert.tenantId() + "|" + alert.accountId()).getBytes(StandardCharsets.UTF_8);
        return new ProducerRecord<>(topic, null, timestamp, key, serde.serialize(Contracts.toAvro(alert)));
    }
}
