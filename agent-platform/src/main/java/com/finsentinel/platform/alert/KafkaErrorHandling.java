package com.finsentinel.platform.alert;

import com.finsentinel.common.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/** Retry transient failures briefly, then park the record on the DLT so one poison message cannot block a partition. */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.ALERTS_DLT, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000, 2));
        // A payload that cannot be parsed will not parse on retry either.
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
