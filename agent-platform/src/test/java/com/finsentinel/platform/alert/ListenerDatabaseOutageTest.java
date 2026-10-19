package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.contracts.avro.TransactionEvent;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.transaction.TransactionRepository;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drill 07b (ADR 0026) in a test: the database goes away for longer than the listeners used to retry, and nothing that
 * arrived meanwhile may end up on a dead-letter topic. Only an unreadable message may be dead-lettered (ADR 0018).
 * The platform's pool is shut out by revoking its role's login and ending its sessions, so the test's own owner
 * connection and the container stay up; the pool's connection wait is shortened for the outage so the old handling
 * (3 attempts, 1 s apart) gives up within seconds rather than after 90.
 */
@PlatformTest
class ListenerDatabaseOutageTest {

    /** Longer than the old handling took to give up: 3 attempts of a 0.5 s connection wait, 1 s apart. */
    private static final Duration OUTAGE = Duration.ofSeconds(10);
    private static final long CONNECTION_WAIT_MS = 500;

    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    ConsumerFactory<String, byte[]> consumers;
    @Autowired
    AvroSerde<AlertEvent> alertSerde;
    @Autowired
    AvroSerde<TransactionEvent> txnSerde;
    @Autowired
    AlertRepository alerts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    DataSource dataSource;
    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void whatArrivesWhileTheDatabaseIsGoneIsStoredOnceItIsBackAndNeverDeadLettered() throws Exception {
        String tenant = uniqueTenant();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Alert alert = new Alert(UUID.randomUUID().toString(), tenant, "acc-outage", "cus-1", AlertRule.LARGE_AMOUNT,
                Severity.HIGH, "test", List.of("t1"), now, now);
        Transaction txn = txn(tenant, "acc-outage", "42.00", Channel.CARD_PRESENT, "SG", now);
        byte[] alertBytes = alertSerde.serialize(Contracts.toAvro(alert));
        byte[] txnBytes = txnSerde.serialize(Contracts.toAvro(txn));
        Map<TopicPartition, Long> deadLettersBefore = endOffsets(Topics.ALERTS_DLT, Topics.TRANSACTIONS_DLQ);

        HikariConfigMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariConfigMXBean();
        long connectionWait = pool.getConnectionTimeout();
        pool.setConnectionTimeout(CONNECTION_WAIT_MS);
        try {
            shutOutThePlatform(true);
            kafka.send(Topics.ALERTS, alert.accountId(), alertBytes).join();
            kafka.send(Topics.TRANSACTIONS, txn.accountKey(), txnBytes).join();
            Thread.sleep(OUTAGE);
        } finally {
            shutOutThePlatform(false);
            pool.setConnectionTimeout(connectionWait);
        }

        await().pollInSameThread().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(alerts.findRecent(tenant, 10)).extracting(s -> s.alert().alertId())
                    .containsExactly(alert.alertId());
            assertThat(transactions.findRecent(tenant, txn.accountId(), now.minusSeconds(60), 10))
                    .containsExactly(txn);
        });
        assertThat(recordsSince(deadLettersBefore))
                .noneMatch(bytes -> Arrays.equals(bytes, alertBytes) || Arrays.equals(bytes, txnBytes));
    }

    /** As the database owner: the platform's role may not log in, and its open sessions end. */
    private void shutOutThePlatform(boolean out) throws SQLException {
        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             Statement sql = owner.createStatement()) {
            if (out) {
                sql.execute("alter role finsentinel_app nologin");
                sql.execute("select pg_terminate_backend(pid) from pg_stat_activity where usename = 'finsentinel_app'");
            } else {
                sql.execute("alter role finsentinel_app login");
            }
        }
    }

    private Map<TopicPartition, Long> endOffsets(String... topics) {
        try (Consumer<String, byte[]> consumer = consumers.createConsumer("outage-test-" + UUID.randomUUID(), null)) {
            return consumer.endOffsets(partitions(consumer, topics));
        }
    }

    /** Every record written to the given partitions after the given offsets, up to their current end. */
    private List<byte[]> recordsSince(Map<TopicPartition, Long> from) {
        List<byte[]> found = new ArrayList<>();
        try (Consumer<String, byte[]> consumer = consumers.createConsumer("outage-test-" + UUID.randomUUID(), null)) {
            Map<TopicPartition, Long> end = consumer.endOffsets(from.keySet());
            consumer.assign(from.keySet());
            from.forEach(consumer::seek);
            long deadline = System.currentTimeMillis() + 15_000;
            while (from.keySet().stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))
                    && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    found.add(r.value());
                }
            }
        }
        return found;
    }

    private static List<TopicPartition> partitions(Consumer<?, ?> consumer, String... topics) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            consumer.partitionsFor(topic, Duration.ofSeconds(10))
                    .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        }
        return partitions;
    }
}
