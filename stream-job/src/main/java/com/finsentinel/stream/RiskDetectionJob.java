package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.SchemaRegistry;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExternalizedCheckpointRetention;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.StateRecoveryOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
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

        StreamExecutionEnvironment env = environment(config,
                StreamExecutionEnvironment.getExecutionEnvironment() instanceof LocalStreamEnvironment);
        // Each operator instance builds its own client from the URL; the lambda is what gets serialized.
        String registryUrl = config.schemaRegistryUrl();
        Duration registryTimeout = config.schemaRegistryTimeout();
        SerializableSupplier<SchemaRegistry> registry = () -> new HttpSchemaRegistry(registryUrl, registryTimeout);

        SingleOutputStreamOperator<TracedTransaction> transactions = env
                .fromSource(transactionsSource(config).build(), WatermarkStrategy.noWatermarks(), "transactions")
                .uid("transactions-source")
                .process(new ParseTransactions(registry))
                .uid("parse-transactions")
                .name("parse");

        DataStream<AccountBaseline> baselines = env
                .fromSource(baselinesSource(config).build(), WatermarkStrategy.noWatermarks(), "account-baselines")
                .uid("baselines-source")
                .process(new ParseBaselines(registry))
                .uid("parse-baselines")
                .name("parse-baselines");

        // Stable uids: the rule state survives topology changes when the job is restored from a savepoint.
        DataStream<TracedAlert> alerts = transactions
                .keyBy(traced -> traced.txn().accountKey())
                .connect(baselines.keyBy(AccountBaseline::accountKey))
                .process(new RiskRuleFunction(config.rules(), config.stateTtl(), config.otlpEndpoint()))
                .uid("risk-rules")
                .name("risk-rules");

        alerts.sinkTo(alertSink(config.bootstrapServers(), registry)).uid("alerts-sink").name("alerts");
        transactions.getSideOutput(ParseTransactions.INVALID)
                .sinkTo(dlqSink(config.bootstrapServers())).uid("dlq-sink").name("transactions-dlq");
        transactions.map(TracedTransaction::txn).returns(Transaction.class).name("untrace")
                .sinkTo(LandingZone.transactions(config.lakeDir())).uid("landing-sink").name("landing-zone");

        env.execute("finsentinel-risk-detection");
    }

    /**
     * Embedded (this process is the whole cluster, as {@code scripts/stack.sh flink} runs it): the job sets the web UI
     * port and the checkpoint directory, keeps its last checkpoint when it stops, and starts from the newest complete
     * checkpoint there ({@code checkpoint.restore=latest}). A restart then keeps the rules' state, as a cluster's
     * JobManager does after a failure (drills 11 and 12, ADR 0026). On a cluster ({@code flink run}) the cluster's
     * configuration decides all of that: the client keeps the cluster's REST port, checkpoints go where the cluster
     * stores them, the JobManager restores after failures, and a redeployment passes a savepoint.
     */
    static StreamExecutionEnvironment environment(JobConfig config, boolean embedded) {
        StreamExecutionEnvironment env = embedded
                ? StreamExecutionEnvironment.getExecutionEnvironment(embeddedConfiguration(config))
                : StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(config.checkpointInterval().toMillis());
        return env;
    }

    static Configuration embeddedConfiguration(JobConfig config) {
        Configuration flinkConf = new Configuration();
        flinkConf.set(RestOptions.PORT, config.webUiPort());
        // On a file system, not in the JobManager's memory (5 MB of state per subtask at most).
        flinkConf.set(CheckpointingOptions.CHECKPOINT_STORAGE, "filesystem");
        flinkConf.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, config.checkpointDir());
        // Stopping the process cancels the job; without this its last checkpoint would be deleted with it.
        flinkConf.set(CheckpointingOptions.EXTERNALIZED_CHECKPOINT_RETENTION,
                ExternalizedCheckpointRetention.RETAIN_ON_CANCELLATION);
        if (config.restoreLatestCheckpoint()) {
            LatestCheckpoint.in(config.checkpointDir())
                    .ifPresent(checkpoint -> flinkConf.set(StateRecoveryOptions.SAVEPOINT_PATH, checkpoint));
        }
        return flinkConf;
    }

    /**
     * Transactions continue from the group's committed offsets: a job started without a savepoint (a redeployment)
     * picks up where the previous one stopped, and a new group starts at the beginning.
     */
    // flink-connector-kafka 5.0.0-2.2 (the latest) takes only Kafka's deprecated OffsetResetStrategy here; drop the
    // suppression when it accepts AutoOffsetResetStrategy.
    @SuppressWarnings("deprecation")
    static KafkaSourceBuilder<RawMessage> transactionsSource(JobConfig config) {
        return bytesSource(config, Topics.TRANSACTIONS)
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST));
    }

    /**
     * Baselines are read from the beginning on every start without a savepoint: the compacted topic is the table of
     * every account's latest baseline, and a job without state needs all of it (ADR 0019). The group's committed
     * offsets would skip everything the previous job had read, so the new job would have no baselines until the next
     * batch run. Restoring from a checkpoint or savepoint still resumes from the offsets in the state.
     */
    static KafkaSourceBuilder<RawMessage> baselinesSource(JobConfig config) {
        return bytesSource(config, Topics.ACCOUNT_BASELINES).setStartingOffsets(OffsetsInitializer.earliest());
    }

    private static KafkaSourceBuilder<RawMessage> bytesSource(JobConfig config, String topic) {
        return KafkaSource.<RawMessage>builder()
                .setBootstrapServers(config.bootstrapServers())
                .setTopics(topic)
                .setGroupId(config.groupId())
                .setDeserializer(new RawValue());
    }

    private static KafkaSink<TracedAlert> alertSink(String bootstrapServers,
                                                    SerializableSupplier<SchemaRegistry> registry) {
        return KafkaSink.<TracedAlert>builder()
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
