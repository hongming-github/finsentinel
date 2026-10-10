package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
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
 * transactions topic (Avro) -> parse -> keyBy(tenant|account), connected with the account baselines from the batch
 * job -> rules -> alerts topic (Avro). Readable transactions also go to the landing zone the batch job reads.
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

        SingleOutputStreamOperator<Transaction> transactions = env
                .fromSource(bytesSource(config, Topics.TRANSACTIONS), WatermarkStrategy.noWatermarks(), "transactions")
                .uid("transactions-source")
                .process(new ParseTransactions(registry))
                .uid("parse-transactions")
                .name("parse");

        DataStream<AccountBaseline> baselines = env
                .fromSource(bytesSource(config, Topics.ACCOUNT_BASELINES), WatermarkStrategy.noWatermarks(),
                        "account-baselines")
                .uid("baselines-source")
                .process(new ParseBaselines(registry))
                .uid("parse-baselines")
                .name("parse-baselines");

        // Stable uids: the rule state survives topology changes when the job is restored from a savepoint.
        DataStream<Alert> alerts = transactions
                .keyBy(Transaction::accountKey)
                .connect(baselines.keyBy(AccountBaseline::accountKey))
                .process(new RiskRuleFunction(config.rules(), config.stateTtl()))
                .uid("risk-rules")
                .name("risk-rules");

        alerts.sinkTo(alertSink(config.bootstrapServers(), registry)).uid("alerts-sink").name("alerts");
        transactions.getSideOutput(ParseTransactions.INVALID)
                .sinkTo(dlqSink(config.bootstrapServers())).uid("dlq-sink").name("transactions-dlq");
        transactions.sinkTo(LandingZone.transactions(config.lakeDir())).uid("landing-sink").name("landing-zone");

        env.execute("finsentinel-risk-detection");
    }

    // flink-connector-kafka 5.0.0-2.2 (the latest) takes only Kafka's deprecated OffsetResetStrategy here; drop the
    // suppression when it accepts AutoOffsetResetStrategy.
    @SuppressWarnings("deprecation")
    private static KafkaSource<byte[]> bytesSource(JobConfig config, String topic) {
        return KafkaSource.<byte[]>builder()
                .setBootstrapServers(config.bootstrapServers())
                .setTopics(topic)
                .setGroupId(config.groupId())
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setDeserializer(new RawValue())
                .build();
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
