package com.finsentinel.platform.messaging;

import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.contracts.avro.TransactionEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Avro contracts the platform consumes (ADR 0018). The serdes are built at startup, so a platform whose schemas
 * are not registered fails to start instead of failing on the first message.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfig {

    @Bean
    SchemaRegistry schemaRegistry(SchemaRegistryProperties props) {
        return new HttpSchemaRegistry(props.url().toString(), props.timeout());
    }

    @Bean
    AvroSerde<AlertEvent> alertSerde(SchemaRegistry registry) {
        return AvroSerde.forSubject(registry, Contracts.ALERTS_SUBJECT, AlertEvent.class);
    }

    @Bean
    AvroSerde<TransactionEvent> transactionSerde(SchemaRegistry registry) {
        return AvroSerde.forSubject(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
    }
}
