package com.finsentinel.platform.messaging;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestcontainersConfig.SchemaRegistryContainer;
import com.finsentinel.platform.alert.AlertRepository;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drill 9b (ADR 0026) in a test: the platform starts while the schema registry cannot be reached, serves its API, and
 * files an alert that arrived meanwhile once the registry answers again. Before the fix the serdes looked their schema
 * ids up when the beans were created, so the context failed and the process took the analyst API and the MCP server
 * down with the intake. A context of its own (and so containers of its own): the registry the platform sees is
 * unreachable from startup until the test switches it on; the registry container itself runs and holds the schemas.
 */
@PlatformTest
class StartWithoutRegistryTest {

    @Autowired
    SwitchedRegistry registry;
    @Autowired
    SchemaRegistryContainer registryContainer;
    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    AlertRepository alerts;
    @Autowired
    MockMvc mvc;

    @Test
    void startsWithoutTheRegistryAndFilesWhatArrivedOnceItAnswers() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        String tenant = uniqueTenant();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Alert alert = new Alert(UUID.randomUUID().toString(), tenant, "acc-registry", "cus-1", AlertRule.LARGE_AMOUNT,
                Severity.HIGH, "test", List.of("t1"), now, now);
        // Written as the stream job would, with the registry it can reach.
        AvroSerde<AlertEvent> producer = AvroSerde.forSubject(
                new HttpSchemaRegistry(registryContainer.apiUrl(), Duration.ofSeconds(10)), Contracts.ALERTS_SUBJECT,
                AlertEvent.class);
        kafka.send(Topics.ALERTS, alert.accountId(), producer.serialize(Contracts.toAvro(alert))).join();

        await().pollInSameThread().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(alerts.findRecent(tenant, 10)).isEmpty());
        registry.reachable = true;
        await().pollInSameThread().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(alerts.findRecent(tenant, 10)).extracting(s -> s.alert().alertId())
                        .containsExactly(alert.alertId()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Registry {

        @Bean
        @Primary
        SwitchedRegistry switchedRegistry(SchemaRegistryContainer container) {
            return new SwitchedRegistry(new HttpSchemaRegistry(container.apiUrl(), Duration.ofSeconds(10)));
        }
    }

    /** The registry over real HTTP: a closed port until {@link #reachable} is set, the running container after. */
    static final class SwitchedRegistry implements SchemaRegistry {

        private final SchemaRegistry up;
        private final SchemaRegistry down = new HttpSchemaRegistry("http://localhost:1", Duration.ofSeconds(1));
        volatile boolean reachable;

        SwitchedRegistry(SchemaRegistry up) {
            this.up = up;
        }

        private SchemaRegistry current() {
            return reachable ? up : down;
        }

        @Override
        public int idOf(String subject, Schema schema) {
            return current().idOf(subject, schema);
        }

        @Override
        public Schema schemaById(int id) {
            return current().schemaById(id);
        }

        @Override
        public int register(String subject, Schema schema) {
            return current().register(subject, schema);
        }

        @Override
        public void setCompatibility(String subject, String level) {
            current().setCompatibility(subject, level);
        }
    }
}
