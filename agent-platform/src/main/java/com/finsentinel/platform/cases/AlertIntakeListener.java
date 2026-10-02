package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes alerts from the stream job and files them under cases. Idempotent, because the stream job delivers
 * at-least-once (ADR 0003).
 */
@Component
class AlertIntakeListener {

    private static final Logger log = LoggerFactory.getLogger(AlertIntakeListener.class);

    private final JsonMapper json;
    private final CaseIntake intake;

    AlertIntakeListener(JsonMapper json, CaseIntake intake) {
        this.json = json;
        this.intake = intake;
    }

    @KafkaListener(topics = Topics.ALERTS, groupId = "agent-platform-alert-intake")
    void onAlert(String payload) {
        Alert alert = json.readValue(payload, Alert.class);
        intake.accept(alert).ifPresentOrElse(
                caseId -> log.info("Filed {} {} alert {} for {}/{} under case {}", alert.severity(), alert.rule(),
                        alert.alertId(), alert.tenantId(), alert.accountId(), caseId),
                () -> log.debug("Ignored redelivered alert {}", alert.alertId()));
    }
}
