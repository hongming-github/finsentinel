package com.finsentinel.platform.cases;

import com.finsentinel.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox messages to {@code case.events.v1}, keyed by case id.
 *
 * <p>At-least-once: a crash between the broker ack and the commit republishes the batch, so every message carries
 * its {@code eventId} for consumer de-duplication. SKIP LOCKED lets several instances relay in parallel; ordering is
 * guaranteed per instance batch only, so consumers should not assume global order across a case's events.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final OutboxProperties props;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, byte[]> kafka, TransactionTemplate transactions,
                JsonMapper json, OutboxProperties props) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.json = json;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${finsentinel.outbox.poll-interval:1s}")
    void poll() {
        if (props.enabled()) {
            try {
                publishOnce();
            } catch (RuntimeException e) {
                log.warn("Outbox publish failed, will retry: {}", e.toString());
            }
        }
    }

    /** Publishes one batch; returns how many messages went out. Public so tests can drive it. */
    public int publishOnce() {
        Integer sent = transactions.execute(status -> {
            List<OutboxRepository.OutboxMessage> batch = outbox.lockUnpublished(props.batchSize());
            for (OutboxRepository.OutboxMessage m : batch) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", m.eventId());
                envelope.put("type", m.type());
                envelope.put("occurredAt", m.createdAt().toString());
                envelope.put("payload", json.readTree(m.payload()));
                try {
                    kafka.send(Topics.CASE_EVENTS, m.aggregateId(), json.writeValueAsBytes(envelope))
                            .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Roll back: nothing in this batch is marked published, so it is retried as a whole.
                    throw new IllegalStateException("Kafka send failed for outbox event " + m.eventId(), e);
                }
            }
            if (!batch.isEmpty()) {
                outbox.markPublished(batch.stream().map(OutboxRepository.OutboxMessage::eventId).toList());
            }
            return batch.size();
        });
        return sent == null ? 0 : sent;
    }
}
