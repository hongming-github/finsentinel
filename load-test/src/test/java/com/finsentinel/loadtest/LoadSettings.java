package com.finsentinel.loadtest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;

/** {@code load-test.properties}, each key overridable by a system property of the same name. */
final class LoadSettings {

    private final Properties defaults = new Properties();

    private LoadSettings() {
        try (InputStream in = LoadSettings.class.getResourceAsStream("/load-test.properties")) {
            if (in == null) {
                throw new IllegalStateException("load-test.properties is missing from the classpath");
            }
            defaults.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static LoadSettings load() {
        return new LoadSettings();
    }

    String string(String key) {
        String value = System.getProperty(key, defaults.getProperty(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("no setting " + key);
        }
        return value.trim();
    }

    int integer(String key) {
        return Integer.parseInt(string(key));
    }

    double decimal(String key) {
        return Double.parseDouble(string(key));
    }

    /** {@code 500ms}, {@code 30s} or {@code 2m}. */
    Duration duration(String key) {
        String value = string(key);
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            default -> throw new IllegalArgumentException(key + ": expected 500ms, 30s or 2m, got " + value);
        };
    }
}
