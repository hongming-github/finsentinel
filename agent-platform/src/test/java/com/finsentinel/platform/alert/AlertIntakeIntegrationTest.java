package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

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
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;
    @Autowired
    AlertRepository repository;
    @Autowired
    CaseIntake intake;

    @Test
    void redeliveredAlertIsStoredOnce() {
        Alert alert = alert("harbour", "acc-dup");
        String payload = json.writeValueAsString(alert);

        kafka.send(Topics.ALERTS, alert.accountId(), payload);
        kafka.send(Topics.ALERTS, alert.accountId(), payload);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findRecent("harbour", 50))
                        .extracting(s -> s.alert().alertId())
                        .containsOnlyOnce(alert.alertId()));
        assertThat(repository.findRecent("harbour", 50))
                .filteredOn(s -> s.alert().alertId().equals(alert.alertId()))
                .singleElement()
                .satisfies(s -> assertThat(s.alert()).isEqualTo(alert));
    }

    @Test
    void poisonMessageDoesNotBlockLaterAlerts() {
        Alert good = alert("straits", "acc-after-poison");

        kafka.send(Topics.ALERTS, good.accountId(), "{not json");
        kafka.send(Topics.ALERTS, good.accountId(), json.writeValueAsString(good));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findRecent("straits", 50))
                        .extracting(s -> s.alert().alertId())
                        .contains(good.alertId()));
    }

    @Test
    void readsAreScopedToTenant() {
        Alert other = alert("straits", "acc-other-tenant");
        intake.accept(other);

        assertThat(repository.findRecent("harbour", 200))
                .noneMatch(s -> s.alert().alertId().equals(other.alertId()));
    }

    private static Alert alert(String tenant, String account) {
        // Postgres keeps microseconds; truncate so the round-tripped record compares equal.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Alert(UUID.randomUUID().toString(), tenant, account, "cus-1",
                AlertRule.LARGE_AMOUNT, Severity.HIGH, "test", List.of("t1", "t2"), now, now);
    }
}
