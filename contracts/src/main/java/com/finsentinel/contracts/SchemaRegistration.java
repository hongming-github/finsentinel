package com.finsentinel.contracts;

import java.time.Duration;

/**
 * Registers the contracts' schemas and sets each subject's compatibility rule: the deliberate step that producers
 * rely on (they never register). Idempotent: registering a schema the subject already has returns its id. An
 * incompatible change is refused by the registry and fails this step, which is where a contract break should stop.
 *
 * <p>Run: {@code ./mvnw -pl contracts -am compile exec:java -Dexec.args=http://localhost:8095/apis/ccompat/v7}
 */
public final class SchemaRegistration {

    private SchemaRegistration() {
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8095/apis/ccompat/v7";
        registerAll(new HttpSchemaRegistry(url, Duration.ofSeconds(10)))
                .forEach((subject, id) -> System.out.println("registered " + subject + " -> id " + id));
    }

    /** @return the id of each subject's current schema */
    public static java.util.Map<String, Integer> registerAll(SchemaRegistry registry) {
        java.util.Map<String, Integer> ids = new java.util.LinkedHashMap<>();
        for (Contracts.Subject subject : Contracts.SUBJECTS) {
            ids.put(subject.name(), registry.register(subject.name(), subject.schema()));
            registry.setCompatibility(subject.name(), Contracts.COMPATIBILITY);
        }
        return ids;
    }
}
