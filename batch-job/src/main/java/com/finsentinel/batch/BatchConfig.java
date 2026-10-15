package com.finsentinel.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;

/**
 * Job settings. Precedence, lowest to highest: bundled batch-job.properties, environment ({@code FINSENTINEL_LAKE_DIR},
 * {@code KAFKA_BOOTSTRAP_SERVERS}, {@code SCHEMA_REGISTRY_URL}), {@code --key=value} arguments. Unknown keys are
 * rejected so a typo cannot silently fall back to a default.
 */
record BatchConfig(String lakeDir, LocalDate asOf, int curateLookbackDays, int windowDays, String bootstrapServers,
                   String schemaRegistryUrl, Duration schemaRegistryTimeout, boolean publish, String sparkMaster) {

    private static final Map<String, String> ENVIRONMENT = Map.of(
            "FINSENTINEL_LAKE_DIR", "lake.dir",
            "KAFKA_BOOTSTRAP_SERVERS", "kafka.bootstrap-servers",
            "SCHEMA_REGISTRY_URL", "schema-registry.url");

    BatchConfig {
        if (curateLookbackDays < 1 || windowDays < 1) {
            throw new IllegalArgumentException("curate.lookback-days and baseline.window-days must be at least 1");
        }
        if (schemaRegistryTimeout.isNegative() || schemaRegistryTimeout.isZero()) {
            throw new IllegalArgumentException("schema-registry.timeout must be positive");
        }
    }

    static BatchConfig load(String[] args, Map<String, String> env, Clock clock) {
        Properties p = defaults();
        ENVIRONMENT.forEach((variable, key) -> {
            String value = env.get(variable);
            if (value != null && !value.isBlank()) {
                p.setProperty(key, value);
            }
        });
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
        String asOf = p.getProperty("as-of").trim();
        return new BatchConfig(
                required(p, "lake.dir").replace("${user.home}", System.getProperty("user.home")),
                asOf.isEmpty() ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : LocalDate.parse(asOf),
                Integer.parseInt(required(p, "curate.lookback-days")),
                Integer.parseInt(required(p, "baseline.window-days")),
                required(p, "kafka.bootstrap-servers"),
                required(p, "schema-registry.url"),
                duration(required(p, "schema-registry.timeout")),
                Boolean.parseBoolean(required(p, "publish.enabled")),
                p.getProperty("spark.master").trim());
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        try (InputStream in = BatchConfig.class.getResourceAsStream("/batch-job.properties")) {
            if (in == null) {
                throw new IllegalStateException("batch-job.properties not found on classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing setting: " + key);
        }
        return value.trim();
    }

    /** {@code 10s}, {@code 500ms}, or ISO-8601. */
    private static Duration duration(String value) {
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s") && !value.startsWith("P")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.parse(value);
    }
}
