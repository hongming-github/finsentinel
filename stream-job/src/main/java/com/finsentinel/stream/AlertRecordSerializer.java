package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;

/** Keyed by tenant|account so all alerts for one account land on one partition, in order. */
class AlertRecordSerializer implements KafkaRecordSerializationSchema<Alert> {

    private final String topic;

    AlertRecordSerializer(String topic) {
        this.topic = topic;
    }

    @Override
    public ProducerRecord<byte[], byte[]> serialize(Alert alert, KafkaSinkContext context, Long timestamp) {
        byte[] key = (alert.tenantId() + "|" + alert.accountId()).getBytes(StandardCharsets.UTF_8);
        return new ProducerRecord<>(topic, null, timestamp, key, Json.MAPPER.writeValueAsBytes(alert));
    }
}
