package com.finsentinel.simulator;

import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.HttpSchemaRegistry;
import com.finsentinel.contracts.avro.TransactionEvent;
import com.finsentinel.telemetry.Telemetry;
import com.finsentinel.telemetry.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Publishes a week of backfill, then live traffic at a fixed rate with periodic fraud injection. */
public class Simulator {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    private final SimulatorConfig config;
    private final TrafficGenerator generator;
    private final KafkaProducer<String, byte[]> producer;
    private final AvroSerde<TransactionEvent> serde;
    private final Telemetry telemetry;

    Simulator(SimulatorConfig config) {
        this.config = config;
        this.generator = new TrafficGenerator(config.accountsPerTenant(), config.seed());
        // Fails here, before any traffic, when the schema is not registered.
        this.telemetry = Telemetry.start("txn-simulator", config.otlpEndpoint());
        this.serde = AvroSerde.forSubject(new HttpSchemaRegistry(config.schemaRegistryUrl(), Duration.ofSeconds(10)),
                Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.LINGER_MS_CONFIG, 20,
                ProducerConfig.CLIENT_ID_CONFIG, "txn-simulator"),
                new StringSerializer(), new ByteArraySerializer());
    }

    public static void main(String[] args) throws InterruptedException {
        SimulatorConfig config = SimulatorConfig.parse(args);
        Simulator simulator = new Simulator(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            simulator.producer.close();
            simulator.telemetry.close();
        }));
        simulator.run();
    }

    void run() throws InterruptedException {
        log.info("Simulating {} accounts across {} at {} tps -> {} (seed {})",
                generator.accounts().size(), TrafficGenerator.TENANTS, config.tps(), config.bootstrapServers(),
                config.seed());

        if (config.backfillPerAccount() > 0) {
            List<Transaction> history = generator.backfill(config.backfillPerAccount(), Instant.now());
            history.forEach(this::send);
            producer.flush();
            log.info("Backfilled {} historical transactions", history.size());
        }

        long start = System.currentTimeMillis();
        long nextFraudAt = start + config.fraudEverySeconds() * 1000L;
        long sent = 0;
        while (config.durationSeconds() == 0 || System.currentTimeMillis() - start < config.durationSeconds() * 1000L) {
            long tick = System.currentTimeMillis();
            for (int i = 0; i < config.tps(); i++) {
                send(generator.normal(Instant.now()));
            }
            sent += config.tps();

            if (config.fraudEverySeconds() > 0 && tick >= nextFraudAt) {
                inject(generator.randomScenario(), generator.randomAccount());
                nextFraudAt = tick + config.fraudEverySeconds() * 1000L;
            }
            if (sent % (config.tps() * 30L) == 0) {
                log.info("Sent {} live transactions", sent);
            }
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - tick)));
        }
        producer.close();
        telemetry.close();
    }

    private void inject(Scenario scenario, Account account) {
        if (scenario == Scenario.MALFORMED) {
            producer.send(new ProducerRecord<>(Topics.TRANSACTIONS, account.tenantId() + "|" + account.accountId(),
                    "{\"txnId\": \"broken".getBytes(StandardCharsets.UTF_8)));
        } else {
            generator.scenario(scenario, account, Instant.now()).forEach(this::send);
        }
        log.info("Injected {} on {}/{}", scenario, account.tenantId(), account.accountId());
    }

    /**
     * With tracing on, every transaction starts a trace (ADR 0020): the simulator stands in for the bank's core
     * system, where a payment's trace would begin. The producer span's context travels in the traceparent header.
     */
    private void send(Transaction txn) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(Topics.TRANSACTIONS, txn.accountKey(),
                serde.serialize(Contracts.toAvro(txn)));
        if (!telemetry.enabled()) {
            producer.send(record);
            return;
        }
        Span span = telemetry.tracer().spanBuilder(Topics.TRANSACTIONS + " publish").setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", Topics.TRANSACTIONS)
                .setAttribute("finsentinel.tenant", txn.tenantId())
                .setAttribute("finsentinel.txn_id", txn.txnId())
                .startSpan();
        record.headers().add(TraceContext.HEADER,
                TraceContext.traceparent(Context.root().with(span)).getBytes(StandardCharsets.UTF_8));
        producer.send(record, (metadata, error) -> {
            if (error != null) {
                span.recordException(error);
            }
            span.end();
        });
    }
}
