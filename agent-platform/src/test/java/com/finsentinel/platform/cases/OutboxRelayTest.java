package com.finsentinel.platform.cases;

import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.platform.PlatformTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class OutboxRelayTest {

    @Autowired
    OutboxRelay relay;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseWorker worker;
    @Autowired
    ConsumerFactory<String, String> consumers;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;

    @Test
    void readyForDecisionEventIsPublishedOnceWithItsEventId() {
        CaseTestSupport.parkOtherCases(jdbc);
        String tenant = uniqueTenant();
        String caseId = intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), Severity.HIGH)).orElseThrow();
        worker.runOnce();

        assertThat(relay.publishOnce()).isPositive();
        assertThat(relay.publishOnce()).as("nothing left to publish").isZero();

        long eventId = jdbc.sql("select event_id from outbox where aggregate_id = cast(:id as uuid) and published_at is not null")
                .param("id", caseId).query(Long.class).single();
        List<JsonNode> published = readCaseEvents(caseId);
        assertThat(published).singleElement().satisfies(m -> {
            assertThat(m.get("eventId").asLong()).isEqualTo(eventId);
            assertThat(m.get("type").asString()).isEqualTo("case.ready_for_decision");
            assertThat(m.get("payload").get("tenantId").asString()).isEqualTo(tenant);
        });
    }

    private List<JsonNode> readCaseEvents(String caseId) {
        List<JsonNode> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("outbox-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(Topics.CASE_EVENTS));
            long deadline = System.currentTimeMillis() + 15_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (caseId.equals(r.key())) {
                        found.add(json.readTree(r.value()));
                    }
                }
            }
            // Drain a little longer to catch an accidental duplicate.
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofSeconds(1))) {
                if (caseId.equals(r.key())) {
                    found.add(json.readTree(r.value()));
                }
            }
        }
        return found;
    }
}
