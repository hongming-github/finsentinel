package com.finsentinel.stream.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads typed values from the job's properties. Plain Java so it works the same in tests, IDE and cluster. */
public final class Settings {

    public static final String DEFAULTS_RESOURCE = "/stream-job.properties";
    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private Settings() {
    }

    /** The defaults file bundled with the job. */
    public static Properties defaults() {
        Properties properties = new Properties();
        try (InputStream in = Settings.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " not found on classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    public static String string(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing setting: " + key);
        }
        return value.trim();
    }

    /**
     * A file system location; {@code ${user.home}} is replaced with the user's home directory, so a default can be
     * persistent without naming a machine-specific path.
     */
    public static String path(Properties p, String key) {
        return string(p, key).replace("${user.home}", System.getProperty("user.home"));
    }

    public static int integer(Properties p, String key) {
        return Integer.parseInt(string(p, key));
    }

    public static double decimal(Properties p, String key) {
        return Double.parseDouble(string(p, key));
    }

    public static Duration duration(Properties p, String key) {
        return parseDuration(string(p, key));
    }

    static Duration parseDuration(String value) {
        Matcher m = SIMPLE_DURATION.matcher(value);
        if (!m.matches()) {
            return Duration.parse(value);
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }
}
