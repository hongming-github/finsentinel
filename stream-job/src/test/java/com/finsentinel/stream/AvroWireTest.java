package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.InMemorySchemaRegistry;
import com.finsentinel.contracts.SchemaRegistry;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.contracts.avro.TransactionEvent;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.function.SerializableSupplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The job's edges: Avro transactions in (bad ones to the DLQ side output), Avro alerts out. */
class AvroWireTest {

    private static final Instant WHEN = Instant.parse("2026-10-10T08:00:00Z");
    private static final InMemorySchemaRegistry REGISTRY = InMemorySchemaRegistry.withContracts();
    private static final SerializableSupplier<SchemaRegistry> SUPPLIER = () -> REGISTRY;

    private final List<Transaction> parsed = new ArrayList<>();
    private final List<byte[]> invalid = new ArrayList<>();

    @Test
    void goodTransactionsAreParsedAndEverythingElseGoesToTheDlqAsTheOriginalBytes() {
        Transaction txn = new Transaction("t-1", "straits", "acc-1", "c-1", new BigDecimal("99.90"), "SGD", "m-1",
                "5411", Channel.CARD_PRESENT, "SG", WHEN);
        byte[] good = AvroSerde.forSubject(REGISTRY, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class)
                .serialize(Contracts.toAvro(txn));
        byte[] json = "{\"txnId\": \"broken".getBytes(StandardCharsets.UTF_8);
        byte[] unknownSchema = {0, 0, 0, 1, 0, 2, 4};

        ParseTransactions parse = open(new ParseTransactions(SUPPLIER));
        for (byte[] raw : List.of(good, json, unknownSchema, new byte[0])) {
            parse.processElement(raw, context(parse), collector());
        }

        assertThat(parsed).singleElement().satisfies(t -> {
            assertThat(t.txnId()).isEqualTo("t-1");
            assertThat(t.amount()).isEqualByComparingTo("99.90");
            assertThat(t.eventTime()).isEqualTo(WHEN);
        });
        assertThat(invalid).containsExactly(json, unknownSchema, new byte[0]);
    }

    @Test
    void aRegistryOutageFailsTheTaskInsteadOfDiscardingGoodTransactions() {
        byte[] good = AvroSerde.forSubject(REGISTRY, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class)
                .serialize(Contracts.toAvro(new Transaction("t-1", "straits", "acc-1", null, BigDecimal.ONE, "SGD",
                        null, null, Channel.TRANSFER, null, WHEN)));
        ParseTransactions parse = open(new ParseTransactions(() -> new OutageAfterStartup(REGISTRY)));

        assertThatThrownBy(() -> parse.processElement(good, context(parse), collector()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invalid).isEmpty();
    }

    @Test
    void alertsAreWrittenAsRegistryAvroKeyedByAccount() {
        Alert alert = new Alert("a-1", "straits", "acc-1", "c-1", AlertRule.GEO_JUMP, Severity.MEDIUM, "SG then GB",
                List.of("t-1", "t-2"), WHEN, WHEN.plusSeconds(1));
        AlertRecordSerializer serializer = new AlertRecordSerializer("alerts", SUPPLIER);
        serializer.open(null, null);

        ProducerRecord<byte[], byte[]> record = serializer.serialize(alert, null, 1L);

        assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("straits|acc-1");
        AlertEvent back = AvroSerde.forSubject(REGISTRY, Contracts.ALERTS_SUBJECT, AlertEvent.class)
                .deserialize(record.value());
        assertThat(Contracts.fromAvro(back)).isEqualTo(alert);
    }

    private static ParseTransactions open(ParseTransactions parse) {
        parse.open(null);
        return parse;
    }

    private Collector<Transaction> collector() {
        return new Collector<>() {
            @Override
            public void collect(Transaction record) {
                parsed.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    private ParseTransactions.Context context(ParseTransactions parse) {
        return parse.new Context() {
            @Override
            public Long timestamp() {
                return null;
            }

            @Override
            public TimerService timerService() {
                throw new UnsupportedOperationException();
            }

            @Override
            public <X> void output(OutputTag<X> tag, X value) {
                assertThat(tag).isEqualTo(ParseTransactions.INVALID);
                invalid.add((byte[]) value);
            }
        };
    }

    /** Knows the job's own schema at startup, then cannot answer lookups by id. */
    private record OutageAfterStartup(SchemaRegistry delegate) implements SchemaRegistry {

        @Override
        public int idOf(String subject, org.apache.avro.Schema schema) {
            return delegate.idOf(subject, schema);
        }

        @Override
        public org.apache.avro.Schema schemaById(int id) {
            throw new IllegalStateException("schema registry answered 503");
        }

        @Override
        public int register(String subject, org.apache.avro.Schema schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCompatibility(String subject, String level) {
            throw new UnsupportedOperationException();
        }
    }
}
