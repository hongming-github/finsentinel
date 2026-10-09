package com.finsentinel.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Same images as deploy/docker-compose.yml, so tests run against what we deploy. The identity provider is
 * {@link TestIssuer}, in process.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg17")
                .asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));
    }

    /** Off when a test brings its own identity provider ({@code finsentinel.test.external-issuer=true}). */
    @Bean
    DynamicPropertyRegistrar testIssuer(Environment environment) {
        if (environment.getProperty("finsentinel.test.external-issuer", Boolean.class, false)) {
            return registry -> {
            };
        }
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIssuer::uri);
            registry.add("spring.security.oauth2.client.provider.finsentinel.token-uri",
                    () -> TestIssuer.uri() + "/protocol/openid-connect/token");
        };
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);
    }
}
