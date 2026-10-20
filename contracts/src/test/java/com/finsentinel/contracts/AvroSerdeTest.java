package com.finsentinel.contracts;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.contracts.avro.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvroSerdeTest {

    private static final Instant WHEN = Instant.parse("2026-10-10T08:15:30.123456Z");

    private InMemorySchemaRegistry registry;

    @BeforeEach
    void register() {
        registry = InMemorySchemaRegistry.withContracts();
    }

    @Test
    void aTransactionRoundTripsThroughTheWireFormat() {
        Transaction txn = new Transaction("t-1", "straits", "acc-1", null, new BigDecimal("1250.5"), "SGD", "m-9",
                "5411", Channel.CARD_NOT_PRESENT, "SG", WHEN);
        AvroSerde<TransactionEvent> serde =
                AvroSerde.forSubject(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);

        byte[] bytes = serde.serialize(Contracts.toAvro(txn));

        assertThat(bytes[0]).as("magic byte").isZero();
        assertThat(java.nio.ByteBuffer.wrap(bytes, 1, 4).getInt()).as("schema id")
                .isEqualTo(registry.idOf(Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.getClassSchema()));
        Transaction back = Contracts.fromAvro(serde.deserialize(bytes));
        assertThat(back).usingRecursiveComparison().withEqualsForType(
                (a, b) -> a.compareTo(b) == 0, BigDecimal.class).isEqualTo(txn);
        assertThat(back.amount()).isEqualByComparingTo("1250.50");
    }

    @Test
    void anAlertRoundTrips() {
        Alert alert = new Alert("a-1", "harbour", "acc-2", "c-2", AlertRule.VELOCITY, Severity.HIGH, "5 txns in 60s",
                List.of("t-1", "t-2"), WHEN, WHEN.plusMillis(5));
        AvroSerde<AlertEvent> serde = AvroSerde.forSubject(registry, Contracts.ALERTS_SUBJECT, AlertEvent.class);

        assertThat(Contracts.fromAvro(serde.deserialize(serde.serialize(Contracts.toAvro(alert))))).isEqualTo(alert);
    }

    @Test
    void anAccountBaselineRoundTrips() {
        com.finsentinel.common.AccountBaseline baseline = new com.finsentinel.common.AccountBaseline("straits",
                "acc-1", Instant.parse("2026-10-10T00:00:00Z"), 90, 42, 120.5, 30.25, 131.0, 260.0, 900.0, WHEN);
        AvroSerde<com.finsentinel.contracts.avro.AccountBaselineEvent> serde = AvroSerde.forSubject(registry,
                Contracts.ACCOUNT_BASELINES_SUBJECT, com.finsentinel.contracts.avro.AccountBaselineEvent.class);

        assertThat(Contracts.fromAvro(serde.deserialize(serde.serialize(Contracts.toAvro(baseline)))))
                .isEqualTo(baseline);
    }

    @Test
    void anAmountWithMoreThanTwoDecimalsIsRefusedNotRounded() {
        Transaction txn = new Transaction("t-1", "straits", "acc-1", null, new BigDecimal("10.005"), "SGD", null,
                null, Channel.TRANSFER, null, WHEN);

        assertThatThrownBy(() -> Contracts.toAvro(txn)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void aProducerWhoseSchemaIsNotRegisteredFailsAtConstruction() {
        assertThatThrownBy(() -> AvroSerde.forSubject(new InMemorySchemaRegistry(), Contracts.ALERTS_SUBJECT,
                AlertEvent.class)).isInstanceOf(SchemaRegistry.SchemaNotRegisteredException.class);
    }

    @Test
    void anythingButARegistryAvroMessageIsInvalid() {
        AvroSerde<TransactionEvent> serde =
                AvroSerde.forSubject(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
        byte[] json = "{\"txnId\":\"t-1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] unknownId = {0, 0, 0, 0, 99, 1, 2};
        byte[] truncated = java.util.Arrays.copyOf(serde.serialize(Contracts.toAvro(new Transaction("t-1", "straits",
                "acc-1", null, BigDecimal.TEN, "SGD", null, null, Channel.TRANSFER, null, WHEN))), 12);

        for (byte[] bad : List.of(json, unknownId, truncated, new byte[0])) {
            assertThatThrownBy(() -> serde.deserialize(bad)).isInstanceOf(AvroSerde.InvalidMessageException.class);
        }
        assertThatThrownBy(() -> serde.deserialize(null)).isInstanceOf(AvroSerde.InvalidMessageException.class);
    }

    @Test
    void aRegistryThatCannotAnswerIsNotABadMessage() {
        byte[] good = AvroSerde.forSubject(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class)
                .serialize(Contracts.toAvro(new Transaction("t-1", "straits", "acc-1", null, BigDecimal.TEN, "SGD",
                        null, null, Channel.TRANSFER, null, WHEN)));
        SchemaRegistry down = new SchemaRegistry() {
            @Override
            public int idOf(String subject, org.apache.avro.Schema schema) {
                return registry.idOf(subject, schema);
            }

            @Override
            public org.apache.avro.Schema schemaById(int id) {
                throw new java.io.UncheckedIOException(new java.net.ConnectException("registry down"));
            }

            @Override
            public int register(String subject, org.apache.avro.Schema schema) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setCompatibility(String subject, String level) {
                throw new UnsupportedOperationException();
            }
        };
        AvroSerde<TransactionEvent> serde = AvroSerde.forSubject(down, Contracts.TRANSACTIONS_SUBJECT,
                TransactionEvent.class);

        // Discarding it to a dead-letter topic would lose a good transaction; the caller must retry.
        assertThatThrownBy(() -> serde.deserialize(good)).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void aConsumerSerdeIsBuiltWhileTheRegistryIsDownAndLooksItsIdUpOnFirstWrite() {
        java.util.concurrent.atomic.AtomicBoolean reachable = new java.util.concurrent.atomic.AtomicBoolean();
        SchemaRegistry switched = new SchemaRegistry() {
            private SchemaRegistry current() {
                if (!reachable.get()) {
                    throw new java.io.UncheckedIOException(new java.net.ConnectException("registry down"));
                }
                return registry;
            }

            @Override
            public int idOf(String subject, org.apache.avro.Schema schema) {
                return current().idOf(subject, schema);
            }

            @Override
            public org.apache.avro.Schema schemaById(int id) {
                return current().schemaById(id);
            }

            @Override
            public int register(String subject, org.apache.avro.Schema schema) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setCompatibility(String subject, String level) {
                throw new UnsupportedOperationException();
            }
        };
        AvroSerde<AlertEvent> serde = AvroSerde.forReading(switched, Contracts.ALERTS_SUBJECT, AlertEvent.class);
        Alert alert = new Alert("a-1", "straits", "acc-1", "cus-1", AlertRule.LARGE_AMOUNT, Severity.HIGH, "big",
                List.of("t-1"), WHEN, WHEN);

        assertThatThrownBy(() -> serde.serialize(Contracts.toAvro(alert)))
                .isInstanceOf(java.io.UncheckedIOException.class);
        reachable.set(true);
        assertThat(Contracts.fromAvro(serde.deserialize(serde.serialize(Contracts.toAvro(alert))))).isEqualTo(alert);
    }
}
