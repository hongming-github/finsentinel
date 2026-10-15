package com.finsentinel.stream;

/**
 * A Kafka message as read: the value bytes and the trace context header, if any (ADR 0020).
 *
 * @param traceparent W3C trace context of the producer, or null
 */
// Public: Flink treats only public types as POJOs; a package-private record would fall back to Kryo.
public record RawMessage(byte[] value, String traceparent) {
}
