package com.finsentinel.platform.alert;

import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry transient failures briefly, then park the record on its topic's dead-letter topic, as the bytes that arrived,
 * so one poison message cannot block a partition and anything parked can be replayed.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, AlertProperties props) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(
                        record.topic().equals(Topics.TRANSACTIONS) ? Topics.TRANSACTIONS_DLQ : Topics.ALERTS_DLT, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(props.retryInterval().toMillis(), props.retryAttempts()));
        // A message that cannot be read will not become readable on retry either.
        handler.addNotRetryableExceptions(AvroSerde.InvalidMessageException.class);
        return handler;
    }
}
