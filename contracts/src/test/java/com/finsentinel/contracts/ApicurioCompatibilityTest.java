package com.finsentinel.contracts;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.avro.TransactionEvent;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract rules against the real registry (Apicurio, Confluent-compatible API): registration is idempotent, a
 * breaking change is refused, a compatible one is accepted, and a consumer reads a newer producer's messages.
 */
@Testcontainers
class ApicurioCompatibilityTest {

    @Container
    static final GenericContainer<?> APICURIO = new GenericContainer<>(ApicurioImage.NAME)
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/ccompat/v7/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    private static HttpSchemaRegistry registry;

    @BeforeAll
    static void registerContracts() {
        registry = new HttpSchemaRegistry(
                "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/ccompat/v7",
                Duration.ofSeconds(10));
        SchemaRegistration.registerAll(registry);
    }

    @Test
    void registrationIsIdempotent() {
        assertThat(SchemaRegistration.registerAll(registry)).isEqualTo(SchemaRegistration.registerAll(registry));
    }

    @Test
    void aBreakingChangeIsRefused() {
        // A new required field: the new schema cannot read the messages already on the topic.
        Schema breaking = withField(TransactionEvent.getClassSchema(), "deviceId", Schema.create(Schema.Type.STRING),
                null);

        assertThatThrownBy(() -> registry.register(Contracts.TRANSACTIONS_SUBJECT, breaking))
                .isInstanceOf(SchemaRegistry.IncompatibleSchemaException.class);
        assertThatThrownBy(() -> registry.idOf(Contracts.TRANSACTIONS_SUBJECT, breaking))
                .isInstanceOf(SchemaRegistry.SchemaNotRegisteredException.class);
    }

    @Test
    void anOptionalFieldIsAcceptedAndOlderConsumersReadTheNewMessages() throws IOException {
        Schema evolved = withField(TransactionEvent.getClassSchema(), "deviceId",
                SchemaBuilder.unionOf().nullType().and().stringType().endUnion(), Schema.Field.NULL_DEFAULT_VALUE);
        int evolvedId = registry.register(Contracts.TRANSACTIONS_SUBJECT, evolved);
        Instant when = Instant.parse("2026-10-10T08:00:00Z");
        TransactionEvent current = Contracts.toAvro(new Transaction("t-1", "straits", "acc-1", null,
                new BigDecimal("42.00"), "SGD", null, null, Channel.TRANSFER, "SG", when));

        // A producer already on the new version writes a message with the new field set...
        GenericRecord record = new GenericData.Record(evolved);
        evolved.getFields().forEach(f -> record.put(f.name(),
                f.name().equals("deviceId") ? "device-7" : current.get(f.name())));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(ByteBuffer.allocate(4).putInt(evolvedId).array());
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        GenericData model = new GenericData();
        model.addLogicalTypeConversion(new org.apache.avro.Conversions.DecimalConversion());
        model.addLogicalTypeConversion(new org.apache.avro.data.TimeConversions.TimestampMicrosConversion());
        new GenericDatumWriter<GenericRecord>(evolved, model).write(record, encoder);
        encoder.flush();

        // ...and a consumer on the current generated class still reads it.
        AvroSerde<TransactionEvent> consumer =
                AvroSerde.forSubject(registry, Contracts.TRANSACTIONS_SUBJECT, TransactionEvent.class);
        assertThat(consumer.deserialize(out.toByteArray())).isEqualTo(current);
    }

    private static Schema withField(Schema base, String name, Schema type, Object defaultValue) {
        java.util.List<Schema.Field> fields = new java.util.ArrayList<>();
        base.getFields().forEach(f -> fields.add(new Schema.Field(f, f.schema())));
        fields.add(new Schema.Field(name, type, null, defaultValue));
        return Schema.createRecord(base.getName(), base.getDoc(), base.getNamespace(), false, fields);
    }
}
