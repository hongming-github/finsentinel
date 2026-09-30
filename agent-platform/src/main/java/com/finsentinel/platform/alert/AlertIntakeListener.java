package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes alerts from the stream job. Idempotent: the stream job delivers at-least-once, and
 * {@link AlertRepository#insertIfAbsent} turns redeliveries into no-ops.
 */
@Component
class AlertIntakeListener {

    private static final Logger log = LoggerFactory.getLogger(AlertIntakeListener.class);

    private final JsonMapper json;
    private final AlertRepository alerts;

    AlertIntakeListener(JsonMapper json, AlertRepository alerts) {
        this.json = json;
        this.alerts = alerts;
    }

    @KafkaListener(topics = Topics.ALERTS, groupId = "agent-platform-alert-intake")
    void onAlert(String payload) {
        Alert alert = json.readValue(payload, Alert.class);
        if (alerts.insertIfAbsent(alert)) {
            log.info("Stored {} {} alert {} for {}/{}", alert.severity(), alert.rule(), alert.alertId(),
                    alert.tenantId(), alert.accountId());
        } else {
            log.debug("Ignored redelivered alert {}", alert.alertId());
        }
    }
}
