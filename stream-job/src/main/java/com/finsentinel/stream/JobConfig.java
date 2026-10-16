package com.finsentinel.stream;

import com.finsentinel.stream.rules.RuleConfig;
import com.finsentinel.stream.rules.Settings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Job settings. Precedence, lowest to highest: bundled stream-job.properties, environment
 * ({@code KAFKA_BOOTSTRAP_SERVERS}, {@code SCHEMA_REGISTRY_URL}, {@code FINSENTINEL_LAKE_DIR}, {@code OTEL_EXPORTER_OTLP_ENDPOINT}), {@code --key=value}
 * arguments. Unknown keys are rejected so a typo cannot silently fall back to a default threshold.
 */
record JobConfig(String bootstrapServers, String schemaRegistryUrl, Duration schemaRegistryTimeout, String groupId,
                 Duration checkpointInterval, String checkpointDir, int webUiPort, Duration stateTtl, String lakeDir, String otlpEndpoint,
                 RuleConfig rules) {

    static JobConfig load(String[] args, Map<String, String> env) {
        Properties p = Settings.defaults();
        String envBootstrap = env.get("KAFKA_BOOTSTRAP_SERVERS");
        if (envBootstrap != null && !envBootstrap.isBlank()) {
            p.setProperty("kafka.bootstrap-servers", envBootstrap);
        }
        String envLake = env.get("FINSENTINEL_LAKE_DIR");
        if (envLake != null && !envLake.isBlank()) {
            p.setProperty("lake.dir", envLake);
        }
        String envOtlp = env.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (envOtlp != null && !envOtlp.isBlank()) {
            p.setProperty("tracing.otlp-endpoint", envOtlp);
        }
        String envRegistry = env.get("SCHEMA_REGISTRY_URL");
        if (envRegistry != null && !envRegistry.isBlank()) {
            p.setProperty("schema-registry.url", envRegistry);
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("Expected --key=value, got: " + arg);
            }
            String key = arg.substring(2, eq);
            if (!p.containsKey(key)) {
                throw new IllegalArgumentException("Unknown setting: " + key);
            }
            p.setProperty(key, arg.substring(eq + 1));
        }
        return new JobConfig(
                Settings.string(p, "kafka.bootstrap-servers"),
                Settings.string(p, "schema-registry.url"),
                Settings.duration(p, "schema-registry.timeout"),
                Settings.string(p, "kafka.group-id"),
                Settings.duration(p, "checkpoint.interval"),
                fileSystemUri(Settings.path(p, "checkpoint.dir")),
                Settings.integer(p, "local.web-ui-port"),
                Settings.duration(p, "state.ttl"),
                Settings.path(p, "lake.dir"),
                p.getProperty("tracing.otlp-endpoint", "").trim(),
                RuleConfig.from(p));
    }

    /** Flink needs a scheme for checkpoint storage; a plain path is a local directory. */
    private static String fileSystemUri(String dir) {
        return dir.contains("://") ? dir : "file://" + Path.of(dir).toAbsolutePath().normalize();
    }
}
