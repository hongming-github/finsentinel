package com.finsentinel.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Bound from {@code finsentinel.schema-registry.*} (ADR 0018).
 *
 * @param url     Confluent-compatible API of the schema registry (Apicurio: {@code .../apis/ccompat/v7})
 * @param timeout connect and request timeout of registry calls
 */
@ConfigurationProperties("finsentinel.schema-registry")
public record SchemaRegistryProperties(URI url, Duration timeout) {

    public SchemaRegistryProperties {
        if (url == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("finsentinel.schema-registry: url and a positive timeout are required");
        }
    }
}
