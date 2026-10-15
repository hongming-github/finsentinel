package com.finsentinel.mcp.banking;

import io.micrometer.common.KeyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientRequestObservationConvention;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Calls to the bank's systems are recorded by URI template only (ADR 0020): the full URL carries account numbers,
 * customer ids and counterparty names, which can be a person's name, and traces leave the bank's control less
 * carefully than its systems do.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    static final String FULL_URL = "http.url";

    @Bean
    ClientRequestObservationConvention withoutFullUrls() {
        return new DefaultClientRequestObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
                return KeyValues.of(super.getHighCardinalityKeyValues(context).stream()
                        .filter(keyValue -> !keyValue.getKey().equals(FULL_URL))
                        .toList());
            }
        };
    }
}
