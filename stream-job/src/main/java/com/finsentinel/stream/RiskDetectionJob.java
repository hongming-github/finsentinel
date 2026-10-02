package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
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
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

/**
 * transactions topic -> parse -> keyBy(tenant|account) -> rules -> alerts topic.
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

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(config.bootstrapServers())
                .setTopics(Topics.TRANSACTIONS)
                .setGroupId(config.groupId())
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        SingleOutputStreamOperator<Transaction> transactions = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "transactions")
                .process(new ParseTransactions())
                .name("parse");

        DataStream<Alert> alerts = transactions
                .keyBy(Transaction::accountKey)
                .process(new RiskRuleFunction(config.rules(), config.stateTtl()))
                .name("risk-rules");

        alerts.sinkTo(alertSink(config.bootstrapServers())).name("alerts");
        transactions.getSideOutput(ParseTransactions.INVALID)
                .sinkTo(dlqSink(config.bootstrapServers())).name("transactions-dlq");

        env.execute("finsentinel-risk-detection");
    }

    private static KafkaSink<Alert> alertSink(String bootstrapServers) {
        return KafkaSink.<Alert>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(new AlertRecordSerializer(Topics.ALERTS))
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }

    private static KafkaSink<String> dlqSink(String bootstrapServers) {
        return KafkaSink.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(Topics.TRANSACTIONS_DLQ)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}
