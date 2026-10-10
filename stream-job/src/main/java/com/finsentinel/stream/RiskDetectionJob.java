package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.SchemaRegistry;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.function.SerializableSupplier;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.time.Duration;

/**
 * transactions topic (Avro) -> parse -> keyBy(tenant|account) -> rules -> alerts topic (Avro).
 *
 * <p>Delivery is at-least-once: on recovery some alerts are re-sent, and the platform dedupes them by
 * the deterministic {@link Alert#alertId()}. See docs/adr/0003-at-least-once-alerts.md.
 */
public class RiskDetectionJob {

    public static void main(String[] args) throws Exception {
        JobConfig config = JobConfig.load(args, System.getenv());

        Configuration flinkConf = new Configuration();
        flinkConf.set(RestOptions.PORT, config.webUiPort());
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConf);
        env.enableCheckpointing(config.checkpointInterval().toMillis());
        // Each operator instance builds its own client from the URL; the lambda is what gets serialized.
        String registryUrl = config.schemaRegistryUrl();
        Duration registryTimeout = config.schemaRegistryTimeout();
        SerializableSupplier<SchemaRegistry> registry = () -> new HttpSchemaRegistry(registryUrl, registryTimeout);

        // flink-connector-kafka 5.0.0-2.2 (the latest) takes only Kafka's deprecated OffsetResetStrategy here; drop the
        // suppression when it accepts AutoOffsetResetStrategy.
        @SuppressWarnings("deprecation")
        KafkaSource<byte[]> source = KafkaSource.<byte[]>builder()
                .setBootstrapServers(config.bootstrapServers())
                .setTopics(Topics.TRANSACTIONS)
                .setGroupId(config.groupId())
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setDeserializer(new RawValue())
                .build();

        SingleOutputStreamOperator<Transaction> transactions = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "transactions")
                .process(new ParseTransactions(registry))
                .name("parse");

        DataStream<Alert> alerts = transactions
                .keyBy(Transaction::accountKey)
                .process(new RiskRuleFunction(config.rules(), config.stateTtl()))
                .name("risk-rules");

        alerts.sinkTo(alertSink(config.bootstrapServers(), registry)).name("alerts");
        transactions.getSideOutput(ParseTransactions.INVALID)
                .sinkTo(dlqSink(config.bootstrapServers())).name("transactions-dlq");

        env.execute("finsentinel-risk-detection");
    }

    private static KafkaSink<Alert> alertSink(String bootstrapServers, SerializableSupplier<SchemaRegistry> registry) {
        return KafkaSink.<Alert>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(new AlertRecordSerializer(Topics.ALERTS, registry))
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }

    /** The unreadable messages as they arrived, byte for byte, for inspection and replay. */
    private static KafkaSink<byte[]> dlqSink(String bootstrapServers) {
        return KafkaSink.<byte[]>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.<byte[]>builder()
                        .setTopic(Topics.TRANSACTIONS_DLQ)
                        .setKafkaValueSerializer(ByteArraySerializer.class)
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}
