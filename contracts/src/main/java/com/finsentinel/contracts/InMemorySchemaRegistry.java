package com.finsentinel.contracts;

import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A registry for tests in any module: ids by exact schema, no compatibility checks (those are tested against
 * Apicurio in {@code ApicurioCompatibilityTest}). Thread-safe; serializable so Flink operators can carry it.
 */
public final class InMemorySchemaRegistry implements SchemaRegistry, java.io.Serializable {

    private final List<String> schemas = new ArrayList<>();
    private final Map<String, List<Integer>> subjects = new HashMap<>();

    /** A registry holding the contracts' schemas, as after {@link SchemaRegistration}. */
    public static InMemorySchemaRegistry withContracts() {
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry();
        SchemaRegistration.registerAll(registry);
        return registry;
    }

    @Override
    public synchronized int idOf(String subject, Schema schema) {
        return subjects.getOrDefault(subject, List.of()).stream()
                .filter(id -> schemas.get(id - 1).equals(schema.toString())).findFirst()
                .orElseThrow(() -> new SchemaNotRegisteredException(subject));
    }

    @Override
    public synchronized Schema schemaById(int id) {
        if (id < 1 || id > schemas.size()) {
            throw new SchemaNotRegisteredException("no schema with id " + id);
        }
        return new Schema.Parser().parse(schemas.get(id - 1));
    }

    @Override
    public synchronized int register(String subject, Schema schema) {
        int existing = schemas.indexOf(schema.toString());
        int id = existing >= 0 ? existing + 1 : add(schema);
        subjects.computeIfAbsent(subject, s -> new ArrayList<>()).add(id);
        return id;
    }

    private int add(Schema schema) {
        schemas.add(schema.toString());
        return schemas.size();
    }

    @Override
    public void setCompatibility(String subject, String level) {
    }
}
