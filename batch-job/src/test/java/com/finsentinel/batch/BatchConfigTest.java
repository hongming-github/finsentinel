package com.finsentinel.batch;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchConfigTest {

    // 01:30 in Singapore on the 11th is still the 10th in UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T17:30:00Z"), ZoneOffset.ofHours(8));

    @Test
    void asOfDefaultsToTodayInUtc() {
        assertThat(BatchConfig.load(new String[0], Map.of(), CLOCK).asOf()).isEqualTo(LocalDate.parse("2026-10-10"));
    }

    @Test
    void environmentAndArgumentsOverrideTheFile() {
        BatchConfig config = BatchConfig.load(new String[]{"--baseline.window-days=30"},
                Map.of("FINSENTINEL_LAKE_DIR", "/data/lake", "SCHEMA_REGISTRY_URL", "http://registry/apis/ccompat/v7"),
                CLOCK);

        assertThat(config.windowDays()).isEqualTo(30);
        assertThat(config.lakeDir()).isEqualTo("/data/lake");
        assertThat(config.schemaRegistryUrl()).isEqualTo("http://registry/apis/ccompat/v7");
        assertThat(config.publish()).isTrue();
    }

    @Test
    void theLakeDefaultsToAPersistentDirectoryInTheUsersHome() {
        assertThat(BatchConfig.load(new String[0], Map.of(), CLOCK).lakeDir())
                .isEqualTo(System.getProperty("user.home") + "/.finsentinel/lake");
    }

    @Test
    void typosAndNonsenseAreRejected() {
        assertThatThrownBy(() -> BatchConfig.load(new String[]{"--baseline.windowdays=30"}, Map.of(), CLOCK))
                .hasMessageContaining("Unknown setting");
        assertThatThrownBy(() -> BatchConfig.load(new String[]{"--baseline.window-days=0"}, Map.of(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
