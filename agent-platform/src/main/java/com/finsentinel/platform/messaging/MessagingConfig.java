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
 * The Avro contracts the platform consumes (ADR 0018). The serdes are built without asking the registry, so the
 * platform starts and serves its API while the registry is down; the listeners fail on a message whose writer schema
 * they have not seen and retry until the registry answers (drill 9b, ADR 0026).
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfig {

    @Bean
    SchemaRegistry schemaRegistry(SchemaRegistryProperties props) {
        return new HttpSchemaRegistry(props.url().toString(), props.timeout());
    }

    @Bean
    AvroSerde<AlertEvent> alertSerde(SchemaRegistry registry) {
        return AvroSerde.forReading(registry, Contracts.ALERTS_SUBJECT, AlertEvent.class);
    }

    @Bean
    AvroSerde<TransactionEvent> transactionSerde(SchemaRegistry registry) {
        return AvroSerde.forReading(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
    }
}
