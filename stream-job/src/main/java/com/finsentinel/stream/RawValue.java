package com.finsentinel.stream;

import com.finsentinel.telemetry.TraceContext;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;

/**
 * Hands the record value on as bytes, with the trace context header; decoding happens in {@link ParseTransactions},
 * where a message that cannot be read goes to the DLQ instead of failing the source. A null value (tombstone) becomes
 * empty bytes for the same reason.
 */
class RawValue implements KafkaRecordDeserializationSchema<RawMessage> {

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<RawMessage> out) {
        Header trace = record.headers().lastHeader(TraceContext.HEADER);
        out.collect(new RawMessage(record.value() == null ? new byte[0] : record.value(),
                trace == null || trace.value() == null ? null : new String(trace.value(), StandardCharsets.UTF_8)));
    }

    @Override
    public TypeInformation<RawMessage> getProducedType() {
        return TypeInformation.of(RawMessage.class);
    }
}
