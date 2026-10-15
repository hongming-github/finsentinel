package com.finsentinel.stream;

/**
 * A Kafka message as read: the value bytes and the trace context header, if any (ADR 0020).
 *
 * @param traceparent W3C trace context of the producer, or null
 */
// Public: Flink treats only public types as POJOs; a package-private record would fall back to Kryo. The bytes are
// passed through and parsed, never compared, so array identity in equals does not matter.
@SuppressWarnings("ArrayRecordComponent")
public record RawMessage(byte[] value, String traceparent) {
}
