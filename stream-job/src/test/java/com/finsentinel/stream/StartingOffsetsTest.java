package com.finsentinel.stream;

import com.finsentinel.common.Topics;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job started without a savepoint, on a consumer group that had already read everything (a redeployment):
 * transactions continue where the group stopped, baselines are read again from the start (ADR 0019). With the
 * group's committed offsets for both, the new job had no baselines until the next batch run.
 */
@Testcontainers
class StartingOffsetsTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    private static final String GROUP = "redeployed-job";
    private static final int RECORDS = 3;

    private static JobConfig config;

    @BeforeAll
    static void aGroupThatHasReadBothTopics() throws Exception {
        config = JobConfig.load(new String[]{"--kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--kafka.group-id=" + GROUP}, Map.of());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer())) {
            for (String topic : List.of(Topics.TRANSACTIONS, Topics.ACCOUNT_BASELINES)) {
                for (int i = 0; i < RECORDS; i++) {
                    producer.send(new ProducerRecord<>(topic, 0, "k" + i,
                            (topic + "-" + i).getBytes(StandardCharsets.UTF_8))).get();
                }
            }
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.alterConsumerGroupOffsets(GROUP, Map.of(
                    new TopicPartition(Topics.TRANSACTIONS, 0), new OffsetAndMetadata(RECORDS),
                    new TopicPartition(Topics.ACCOUNT_BASELINES, 0), new OffsetAndMetadata(RECORDS))).all().get();
        }
    }

    @Test
    void baselinesAreReadFromTheStartEvenWhenTheGroupHasReadThemAll() throws Exception {
        assertThat(readUpToNow(RiskDetectionJob.baselinesSource(config)))
                .containsExactly(Topics.ACCOUNT_BASELINES + "-0", Topics.ACCOUNT_BASELINES + "-1",
                        Topics.ACCOUNT_BASELINES + "-2");
    }

    @Test
    void transactionsContinueFromTheGroupsCommittedOffsets() throws Exception {
        assertThat(readUpToNow(RiskDetectionJob.transactionsSource(config))).isEmpty();
    }

    /** Runs the source in a local Flink job until the end offsets of now, and returns what it emitted. */
    private static List<String> readUpToNow(KafkaSourceBuilder<RawMessage> source) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        List<String> read = new ArrayList<>();
        try (CloseableIterator<RawMessage> records = env
                .fromSource(source.setBounded(OffsetsInitializer.latest()).build(), WatermarkStrategy.noWatermarks(),
                        "source")
                .executeAndCollect()) {
            records.forEachRemaining(r -> read.add(new String(r.value(), StandardCharsets.UTF_8)));
        }
        return read;
    }
}
