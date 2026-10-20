package com.finsentinel.contracts;

import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Avro in the Confluent wire format: a zero magic byte, the writer schema's 4-byte registry id, then Avro binary. The
 * format every Kafka schema registry and tool understands, so the registry can change without touching a byte.
 *
 * <p>Writing: the local (generated) schema must already be registered under the subject; the id is looked up once, at
 * construction ({@link #forSubject}), and a producer whose schema the registry does not know fails at startup rather
 * than at the first message. Schemas are registered on purpose ({@link SchemaRegistration}), never by a producer. A
 * consumer's serde ({@link #forReading}) looks the id up on its first write instead, so a consumer starts while the
 * registry is down (drill 9b, ADR 0026).
 *
 * <p>Reading: the writer schema comes from the registry by the id in the message, and Avro resolves it against the
 * local schema, so a consumer reads every compatible version.
 */
public final class AvroSerde<T extends SpecificRecord> {

    private static final byte MAGIC = 0;
    private static final int HEADER = 5;
    private static final Set<String> TRUSTED = new HashSet<>();

    private final SchemaRegistry registry;
    private final String subject;
    private final Schema schema;
    /** The local schema's id under the subject; null until looked up. */
    private volatile Integer writerId;

    private AvroSerde(SchemaRegistry registry, String subject, Schema schema) {
        this.registry = registry;
        this.subject = subject;
        this.schema = schema;
    }

    /**
     * For a producer: fails here, not at the first message, when the registry does not have the local schema.
     *
     * @throws SchemaRegistry.SchemaNotRegisteredException when the local schema is not registered for the subject
     */
    public static <T extends SpecificRecord> AvroSerde<T> forSubject(SchemaRegistry registry, String subject, Class<T> type) {
        AvroSerde<T> serde = forReading(registry, subject, type);
        serde.writerId();
        return serde;
    }

    /**
     * For a consumer: needs no registry to be built. Reading asks the registry only for writer ids it has not seen;
     * the local schema's id is looked up on the first {@link #serialize}, if any.
     */
    public static <T extends SpecificRecord> AvroSerde<T> forReading(SchemaRegistry registry, String subject, Class<T> type) {
        Schema schema = SpecificData.get().getSchema(type);
        trust(schema);
        return new AvroSerde<>(registry, subject, schema);
    }

    private int writerId() {
        Integer id = writerId;
        if (id == null) {
            // Two threads may both ask; the registry answers both with the same id.
            id = registry.idOf(subject, schema);
            writerId = id;
        }
        return id;
    }

    /**
     * Avro 1.12 instantiates only classes on an allow-list when it reads (a defence against gadget classes named in a
     * writer schema). The generated classes of a serde's own schema are exactly the ones it should create.
     */
    private static synchronized void trust(Schema schema) {
        Set<String> names = new HashSet<>();
        collectNamedTypes(schema, names);
        names.removeAll(TRUSTED);
        if (names.isEmpty()) {
            return;
        }
        ClassSecurityValidator.Builder builder = ClassSecurityValidator.builder();
        names.forEach(builder::add);
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
                builder.build()));
        TRUSTED.addAll(names);
    }

    private static void collectNamedTypes(Schema schema, Set<String> names) {
        switch (schema.getType()) {
            case RECORD -> {
                if (names.add(schema.getFullName())) {
                    schema.getFields().forEach(f -> collectNamedTypes(f.schema(), names));
                }
            }
            case ENUM, FIXED -> names.add(schema.getFullName());
            case ARRAY -> collectNamedTypes(schema.getElementType(), names);
            case MAP -> collectNamedTypes(schema.getValueType(), names);
            case UNION -> schema.getTypes().forEach(t -> collectNamedTypes(t, names));
            default -> {
            }
        }
    }

    public byte[] serialize(T record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write(MAGIC);
        out.writeBytes(ByteBuffer.allocate(4).putInt(writerId()).array());
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        try {
            new SpecificDatumWriter<T>(schema).write(record, encoder);
            encoder.flush();
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode " + schema.getFullName(), e);
        }
        return out.toByteArray();
    }

    /**
     * @throws InvalidMessageException for a message that will never be readable: not this format, an id the registry
     *                                 does not have, bytes that do not decode, or a writer schema the local schema
     *                                 cannot resolve. A registry that cannot answer is not the message's fault: that
     *                                 exception propagates, so the caller retries instead of discarding good data.
     */
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER || bytes[0] != MAGIC) {
            throw new InvalidMessageException("not a schema-registry Avro message");
        }
        int id = ByteBuffer.wrap(bytes, 1, 4).getInt();
        Schema writer;
        try {
            writer = registry.schemaById(id);
        } catch (SchemaRegistry.SchemaNotRegisteredException e) {
            throw new InvalidMessageException("unknown schema id " + id);
        }
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, HEADER, bytes.length - HEADER, null);
            return new SpecificDatumReader<T>(writer, schema).read(null, decoder);
        } catch (IOException | RuntimeException e) {
            throw new InvalidMessageException("unreadable message with schema id " + id + ": " + e);
        }
    }

    public static final class InvalidMessageException extends RuntimeException {
        public InvalidMessageException(String message) {
            super(message);
        }
    }
}
