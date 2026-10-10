package com.finsentinel.batch;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Topics;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.InMemorySchemaRegistry;
import com.finsentinel.contracts.avro.AccountBaselineEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The run's baselines reach Kafka as registry Avro, keyed the way the stream job keys accounts. */
@Testcontainers
class BaselinePublisherTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    private static final InMemorySchemaRegistry REGISTRY = InMemorySchemaRegistry.withContracts();

    @TempDir
    Path lake;

    @Test
    void everyAccountsBaselineIsPublishedKeyedByTenantAndAccount() {
        Landing landing = new Landing(lake);
        for (int i = 0; i < 12; i++) {
            landing.write(landing.txn("straits", "acc-1", String.valueOf(10 + i), Instant.parse("2026-10-09T08:00:00Z")
                    .plusSeconds(i)));
            landing.write(landing.txn("harbour", "acc-1", "500", Instant.parse("2026-10-08T08:00:00Z")
                    .plusSeconds(i)));
        }
        BatchConfig config = BatchConfig.load(new String[]{"--lake.dir=" + lake, "--as-of=2026-10-10",
                "--kafka.bootstrap-servers=" + KAFKA.getBootstrapServers()}, Map.of(), Clock.fixed(NOW, ZoneOffset.UTC));

        BaselineJob.run(LocalSpark.SPARK, config, () -> REGISTRY, NOW);

        Map<String, AccountBaseline> published = new java.util.HashMap<>();
        AvroSerde<AccountBaselineEvent> serde =
                AvroSerde.forSubject(REGISTRY, Contracts.ACCOUNT_BASELINES_SUBJECT, AccountBaselineEvent.class);
        for (ConsumerRecord<String, byte[]> r : consume(2)) {
            published.put(r.key(), Contracts.fromAvro(serde.deserialize(r.value())));
        }
        assertThat(published).containsOnlyKeys("straits|acc-1", "harbour|acc-1");
        assertThat(published.get("straits|acc-1")).satisfies(b -> {
            assertThat(b.asOf()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
            assertThat(b.windowDays()).isEqualTo(90);
            assertThat(b.sampleSize()).isEqualTo(12);
            assertThat(b.medianAmount()).isEqualTo(15.5);
            assertThat(b.computedAt()).isEqualTo(NOW);
        });
        assertThat(published.get("harbour|acc-1").robustStdDev()).isZero();
    }

    private static List<ConsumerRecord<String, byte[]>> consume(int expected) {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.ACCOUNT_BASELINES));
            long deadline = System.currentTimeMillis() + 20_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }
}
