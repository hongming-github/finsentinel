package com.finsentinel.platform.observability;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestTracing;
import com.finsentinel.platform.cases.CaseWorker;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One trace from the stream job's detection span through the queue to the analysis and its graph nodes (ADR 0020).
 */
@PlatformTest
class TracingIntegrationTest {

    private static final String TRACE_1 = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String DETECTION_1 = "00f067aa0ba902b7";
    private static final String TRACE_2 = "5cf92f3577b34da6a3ce929d0e0e4737";
    private static final String DETECTION_2 = "11f067aa0ba902b8";

    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    AvroSerde<AlertEvent> serde;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CaseWorker worker;
    @Autowired
    InMemorySpanExporter spans;
    @Autowired
    SdkTracerProvider tracerProvider;
    @Autowired
    Environment environment;

    @Test
    void anAnalysisContinuesTheTraceOfTheAlertThatStartedItsRunAndLinksTheCasesOtherAlerts() {
        String tenant = uniqueTenant();
        String account = "acc-" + UUID.randomUUID();
        Alert first = alert(tenant, account, AlertRule.VELOCITY, Severity.MEDIUM, List.of("t-1"), Instant.now());

        String caseId = deliver(first, TRACE_1, DETECTION_1);
        SpanData intake1 = consumerSpan(TRACE_1, DETECTION_1);
        assertThat(storedTrace("alert", "alert_id", first.alertId())).contains(TRACE_1).contains(intake1.getSpanId());
        analyse(caseId);
        SpanData analysis1 = analysisSpan(caseId, 1);

        assertThat(analysis1.getTraceId()).isEqualTo(TRACE_1);
        assertThat(analysis1.getParentSpanId()).as("child of the alert intake, across the queue")
                .isEqualTo(intake1.getSpanId());
        assertThat(nodes(analysis1)).extracting(SpanData::getName)
                .contains("graph node investigate", "graph node review");

        // A second alert reopens the case: the new run continues the new alert's trace and links the first.
        Alert second = alert(tenant, account, AlertRule.LARGE_AMOUNT, Severity.HIGH, List.of("t-2"), Instant.now());
        deliver(second, TRACE_2, DETECTION_2);
        SpanData intake2 = consumerSpan(TRACE_2, DETECTION_2);
        analyse(caseId);
        SpanData analysis2 = analysisSpan(caseId, 2);

        assertThat(analysis2.getTraceId()).isEqualTo(TRACE_2);
        assertThat(analysis2.getParentSpanId()).isEqualTo(intake2.getSpanId());
        assertThat(analysis2.getLinks()).extracting(LinkData::getSpanContext)
                .anySatisfy(link -> {
                    assertThat(link.getTraceId()).isEqualTo(TRACE_1);
                    assertThat(link.getSpanId()).isEqualTo(intake1.getSpanId());
                });
    }

    @Test
    void promptsAnswersAndToolArgumentsStayOutOfSpans() {
        for (String flag : List.of("spring.ai.chat.observations.log-prompt",
                "spring.ai.chat.observations.log-completion",
                "spring.ai.chat.client.observations.log-prompt",
                "spring.ai.chat.client.observations.log-completion",
                "spring.ai.tools.observations.include-content")) {
            assertThat(environment.getProperty(flag, Boolean.class)).as(flag).isFalse();
        }
    }

    private String deliver(Alert alert, String traceId, String spanId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(Topics.ALERTS, alert.accountId(),
                serde.serialize(Contracts.toAvro(alert)));
        record.headers().add("traceparent",
                ("00-" + traceId + "-" + spanId + "-01").getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
        return await().pollInSameThread().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                        "select case_id::text from alert where alert_id = :id")
                .param("id", UUID.fromString(alert.alertId())).query(String.class).optional(), o -> o.isPresent())
                .orElseThrow();
    }

    /** Runs the worker with only this case in the queue, so the poll claims exactly it. */
    private void analyse(String caseId) {
        jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage = 'ANALYSIS' and case_id <> :id")
                .param("id", UUID.fromString(caseId)).update();
        assertThat(worker.runOnce()).isEqualTo(1);
    }

    private SpanData consumerSpan(String traceId, String parentSpanId) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> TestTracing.finished(tracerProvider, spans).stream()
                        .filter(s -> s.getKind() == SpanKind.CONSUMER && s.getTraceId().equals(traceId)
                                && s.getParentSpanId().equals(parentSpanId))
                        .findFirst(), o -> o.isPresent())
                .orElseThrow();
    }

    private SpanData analysisSpan(String caseId, long run) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> TestTracing.finished(tracerProvider, spans).stream()
                        .filter(s -> s.getName().equals("analyse case")
                                && caseId.equals(s.getAttributes().get(AttributeKey.stringKey("finsentinel.case_id")))
                                && Long.valueOf(run).equals(s.getAttributes().get(AttributeKey.longKey("finsentinel.run"))))
                        .findFirst(), o -> o.isPresent())
                .orElseThrow();
    }

    private List<SpanData> nodes(SpanData analysis) {
        return TestTracing.finished(tracerProvider, spans).stream()
                .filter(s -> s.getParentSpanId().equals(analysis.getSpanId()))
                .toList();
    }

    private String storedTrace(String table, String idColumn, String id) {
        return jdbc.sql("select trace_parent from " + table + " where " + idColumn + " = :id")
                .param("id", UUID.fromString(id)).query(String.class).single();
    }
}
