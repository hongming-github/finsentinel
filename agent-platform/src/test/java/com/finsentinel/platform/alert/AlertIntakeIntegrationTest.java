package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@PlatformTest
class AlertIntakeIntegrationTest {

    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    ConsumerFactory<String, byte[]> consumers;
    @Autowired
    AvroSerde<AlertEvent> serde;
    @Autowired
    AlertRepository repository;
    @Autowired
    CaseIntake intake;

    @Test
    void redeliveredAlertIsStoredOnce() {
        Alert alert = alert("harbour", "acc-dup");
        byte[] payload = serde.serialize(Contracts.toAvro(alert));

        kafka.send(Topics.ALERTS, alert.accountId(), payload);
        kafka.send(Topics.ALERTS, alert.accountId(), payload);

        await().pollInSameThread().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findRecent("harbour", 50))
                        .extracting(s -> s.alert().alertId())
                        .containsOnlyOnce(alert.alertId()));
        assertThat(repository.findRecent("harbour", 50))
                .filteredOn(s -> s.alert().alertId().equals(alert.alertId()))
                .singleElement()
                .satisfies(s -> assertThat(s.alert()).isEqualTo(alert));
    }

    @Test
    void poisonMessageGoesToTheDeadLetterTopicAsSentAndDoesNotBlockLaterAlerts() {
        Alert good = alert("straits", "acc-after-poison");
        // A v1-era JSON alert: not the registry's wire format.
        byte[] poison = ("{\"alertId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);

        kafka.send(Topics.ALERTS, good.accountId(), poison);
        kafka.send(Topics.ALERTS, good.accountId(), serde.serialize(Contracts.toAvro(good)));

        await().pollInSameThread().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findRecent("straits", 50))
                        .extracting(s -> s.alert().alertId())
                        .contains(good.alertId()));
        assertThat(deadLetters()).anySatisfy(bytes -> assertThat(bytes).isEqualTo(poison));
    }

    @Test
    void readsAreScopedToTenant() {
        Alert other = alert("straits", "acc-other-tenant");
        intake.accept(other);

        assertThat(repository.findRecent("harbour", 200))
                .noneMatch(s -> s.alert().alertId().equals(other.alertId()));
    }

    private List<byte[]> deadLetters() {
        List<byte[]> found = new java.util.ArrayList<>();
        try (Consumer<String, byte[]> consumer = consumers.createConsumer("dlt-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(Topics.ALERTS_DLT));
            long deadline = System.currentTimeMillis() + 15_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    found.add(r.value());
                }
            }
        }
        return found;
    }

    private static Alert alert(String tenant, String account) {
        // Postgres keeps microseconds; truncate so the round-tripped record compares equal.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Alert(UUID.randomUUID().toString(), tenant, account, "cus-1",
                AlertRule.LARGE_AMOUNT, Severity.HIGH, "test", List.of("t1", "t2"), now, now);
    }
}
