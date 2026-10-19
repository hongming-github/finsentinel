package com.finsentinel.platform.alert;

import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only an unreadable message may be dead-lettered (ADR 0018); every other failure is retried without a limit
 * (ADR 0026), including the ones Spring Kafka sends straight to the recoverer by default. Real Kafka and Spring test
 * doubles: the recoverer publishes to a {@link MockProducer}, the container is never started.
 */
class KafkaErrorHandlingTest {

    private final MockProducer<Object, Object> producer =
            new MockProducer<>(true, null, (topic, key) -> new byte[0], (topic, value) -> new byte[0]);
    private final MockConsumer<String, byte[]> consumer = new MockConsumer<>("earliest");
    private final KafkaMessageListenerContainer<String, byte[]> container = new KafkaMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<>(Map.of()), new ContainerProperties(Topics.ALERTS));
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final DefaultErrorHandler handler;

    KafkaErrorHandlingTest() {
        scheduler.initialize();
        handler = new KafkaErrorHandling().kafkaErrorHandler(new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)),
                new AlertProperties(200, Duration.ofSeconds(1), 2, Duration.ofSeconds(10)), scheduler);
    }

    @AfterEach
    void stopScheduler() {
        scheduler.shutdown();
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicAtOnce() {
        assertThat(fails(new AvroSerde.InvalidMessageException("not a schema-registry Avro message"))).isTrue();

        assertThat(producer.history()).singleElement()
                .satisfies(dead -> assertThat(dead.topic()).isEqualTo(Topics.ALERTS_DLT));
    }

    @Test
    void anyOtherFailureIsRetriedForeverAndNeverDeadLettered() {
        for (Exception cause : List.of(new CannotGetJdbcConnectionException("Postgres is gone"),
                new IllegalStateException("registry unavailable"),
                // On Spring Kafka's default list of failures that skip retries.
                new ClassCastException("a bug"), new MessageConversionException("a bug"))) {
            for (int attempt = 1; attempt <= 50; attempt++) {
                assertThat(fails(cause)).as("%s, attempt %d", cause, attempt).isFalse();
            }
        }
        assertThat(producer.history()).isEmpty();
    }

    /** One failed delivery of the same record, as the container reports it; true if the handler gave it up. */
    private boolean fails(Exception cause) {
        return handler.handleOne(new ListenerExecutionFailedException("listener failed", cause),
                new ConsumerRecord<>(Topics.ALERTS, 0, 42L, "acc-1", new byte[]{1}), consumer, container);
    }
}
