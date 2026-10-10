package com.finsentinel.stream;

import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Hands the record value on as bytes; decoding happens in {@link ParseTransactions}, where a message that cannot be
 * read goes to the DLQ instead of failing the source. A null value (tombstone) becomes empty bytes for the same reason.
 */
class RawValue implements KafkaRecordDeserializationSchema<byte[]> {

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<byte[]> out) {
        out.collect(record.value() == null ? new byte[0] : record.value());
    }

    @Override
    public TypeInformation<byte[]> getProducedType() {
        return PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO;
    }
}
