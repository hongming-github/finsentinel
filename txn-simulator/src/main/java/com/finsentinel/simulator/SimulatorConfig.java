package com.finsentinel.simulator;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings from {@code --key=value} arguments.
 *
 * @param schemaRegistryUrl Confluent-compatible schema registry API; the transaction schema must be registered there
 * @param otlpEndpoint      OTLP/HTTP receiver for traces (ADR 0020); empty = no tracing
 * @param fraudEverySeconds how often one fraud scenario is injected; 0 disables injection
 * @param burst             fraud scenarios injected at once on as many distinct accounts after the backfill, for queue
 *                          drain tests (ADR 0025); 0 = none
 * @param durationSeconds   stop after this long; 0 runs until interrupted
 */
record SimulatorConfig(
        String bootstrapServers,
        String schemaRegistryUrl,
        String otlpEndpoint,
        int tps,
        int accountsPerTenant,
        int backfillPerAccount,
        int fraudEverySeconds,
        int burst,
        int durationSeconds,
        long seed) {

    static SimulatorConfig parse(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got: " + arg);
            }
            int eq = arg.indexOf('=');
            kv.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return new SimulatorConfig(
                kv.getOrDefault("bootstrap", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")),
                kv.getOrDefault("registry", System.getenv().getOrDefault("SCHEMA_REGISTRY_URL",
                        "http://localhost:8095/apis/ccompat/v7")),
                kv.getOrDefault("otlp", System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "")),
                Integer.parseInt(kv.getOrDefault("tps", "10")),
                Integer.parseInt(kv.getOrDefault("accounts", "500")),
                Integer.parseInt(kv.getOrDefault("backfill", "20")),
                Integer.parseInt(kv.getOrDefault("fraud-every", "15")),
                Integer.parseInt(kv.getOrDefault("burst", "0")),
                Integer.parseInt(kv.getOrDefault("duration", "0")),
                // Random by default so repeated runs produce new transaction ids; pass --seed to reproduce a run.
                kv.containsKey("seed") ? Long.parseLong(kv.get("seed")) : System.nanoTime());
    }
}
