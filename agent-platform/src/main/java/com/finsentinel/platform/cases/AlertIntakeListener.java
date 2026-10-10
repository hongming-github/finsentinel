package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes alerts (Avro, ADR 0018) from the stream job and files them under cases. Idempotent, because the stream job
 * delivers at-least-once (ADR 0003). An unreadable message goes straight to the dead-letter topic
 * ({@link com.finsentinel.platform.alert.KafkaErrorHandling}); a registry outage is retried like any other failure.
 */
@Component
class AlertIntakeListener {

    private static final Logger log = LoggerFactory.getLogger(AlertIntakeListener.class);

    private final AvroSerde<AlertEvent> serde;
    private final CaseIntake intake;

    AlertIntakeListener(AvroSerde<AlertEvent> serde, CaseIntake intake) {
        this.serde = serde;
        this.intake = intake;
    }

    @KafkaListener(topics = Topics.ALERTS, groupId = "agent-platform-alert-intake")
    void onAlert(byte[] payload) {
        Alert alert = Contracts.fromAvro(serde.deserialize(payload));
        intake.accept(alert).ifPresentOrElse(
                caseId -> log.info("Filed {} {} alert {} for {}/{} under case {}", alert.severity(), alert.rule(),
                        alert.alertId(), alert.tenantId(), alert.accountId(), caseId),
                () -> log.debug("Ignored redelivered alert {}", alert.alertId()));
    }
}
