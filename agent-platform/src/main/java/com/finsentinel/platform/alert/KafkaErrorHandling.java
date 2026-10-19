package com.finsentinel.platform.alert;

import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Only a message that cannot be read goes to its topic's dead-letter topic, as the bytes that arrived (ADR 0018);
 * anything else is the platform's or a dependency's fault (Postgres, the registry) and is retried until it succeeds,
 * with a growing pause, so an outage of any length stalls the partition instead of losing what it holds (drill 07b,
 * ADR 0026). During a pause the container is paused, not the consumer thread asleep, so the consumer stays in its
 * group however long the pause; a batch listener's retries pause the consumer and keep polling the same way.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, AlertProperties props,
                                          TaskScheduler scheduler) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(
                        record.topic().equals(Topics.TRANSACTIONS) ? Topics.TRANSACTIONS_DLQ : Topics.ALERTS_DLT, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(props.retryInterval().toMillis(), props.retryMultiplier());
        backOff.setMaxInterval(props.retryMaxInterval().toMillis());
        // No limit on attempts or elapsed time (the defaults): the backoff never says stop.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff,
                new ContainerPausingBackOffHandler(new ListenerContainerPauseService(null, scheduler)));
        // Replaces Spring Kafka's default list of exceptions that skip retries (conversion, ClassCastException ...):
        // a bug fails every attempt, and a stalled partition is visible and fixable, a parked record is neither.
        handler.setClassifications(Map.of(AvroSerde.InvalidMessageException.class, false), true);
        return handler;
    }
}
