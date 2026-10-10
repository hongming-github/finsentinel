package com.finsentinel.platform;

import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.SchemaRegistration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Same images as deploy/docker-compose.yml, so tests run against what we deploy. The identity provider is
 * {@link TestIssuer}, in process.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    /**
     * Not a service connection: that would log the platform in as the container's superuser, which bypasses row-level
     * security. The platform logs in as finsentinel_app (created by V10), Flyway as the owner, as in production.
     */
    @Bean
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
    DynamicPropertyRegistrar database(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "finsentinel_app");
            registry.add("spring.datasource.password", () -> "test-app-password");
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }

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

    /** In-memory storage here (compose uses kafkasql); the API and the compatibility rules are the same. */
    @Bean
    SchemaRegistryContainer schemaRegistryContainer() {
        return new SchemaRegistryContainer();
    }

    /** Registers the contracts as the deployment step would, before the platform's serdes look them up. */
    @Bean
    DynamicPropertyRegistrar schemaRegistryUrl(SchemaRegistryContainer container) {
        SchemaRegistration.registerAll(new HttpSchemaRegistry(container.apiUrl(), Duration.ofSeconds(10)));
        return registry -> registry.add("finsentinel.schema-registry.url", container::apiUrl);
    }

    public static final class SchemaRegistryContainer extends GenericContainer<SchemaRegistryContainer> {

        SchemaRegistryContainer() {
            super(DockerImageName.parse("apicurio/apicurio-registry:3.3.3"));
            withExposedPorts(8080);
            // Apicurio 3 has no /health/ready; the API answering is the readiness that matters.
            waitingFor(Wait.forHttp("/apis/ccompat/v7/subjects").withStartupTimeout(Duration.ofMinutes(2)));
        }

        public String apiUrl() {
            return "http://" + getHost() + ":" + getMappedPort(8080) + "/apis/ccompat/v7";
        }
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);
    }
}
