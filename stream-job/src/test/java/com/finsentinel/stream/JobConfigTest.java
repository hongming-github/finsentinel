package com.finsentinel.stream;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobConfigTest {

    @Test
    void bundledDefaultsLoad() {
        JobConfig config = JobConfig.load(new String[0], Map.of());

        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.stateTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(config.rules().velocityThreshold()).isEqualTo(8);
        assertThat(config.rules().geoJumpWindow()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void argumentsBeatEnvironmentBeatsFile() {
        JobConfig config = JobConfig.load(
                new String[]{"--rules.velocity.threshold=12", "--kafka.bootstrap-servers=args:9092"},
                Map.of("KAFKA_BOOTSTRAP_SERVERS", "env:9092"));

        assertThat(config.rules().velocityThreshold()).isEqualTo(12);
        assertThat(config.bootstrapServers()).isEqualTo("args:9092");
        assertThat(JobConfig.load(new String[0], Map.of("KAFKA_BOOTSTRAP_SERVERS", "env:9092")).bootstrapServers())
                .isEqualTo("env:9092");
    }

    @Test
    void schemaRegistryComesFromTheFileOrTheEnvironment() {
        assertThat(JobConfig.load(new String[0], Map.of()).schemaRegistryUrl())
                .isEqualTo("http://localhost:8095/apis/ccompat/v7");
        assertThat(JobConfig.load(new String[0], Map.of("SCHEMA_REGISTRY_URL", "http://registry:8080/apis/ccompat/v7"))
                .schemaRegistryUrl()).isEqualTo("http://registry:8080/apis/ccompat/v7");
    }

    @Test
    void typoInKeyFailsInsteadOfSilentlyUsingDefault() {
        assertThatThrownBy(() -> JobConfig.load(new String[]{"--rules.velocity.treshold=12"}, Map.of()))
                .hasMessageContaining("Unknown setting: rules.velocity.treshold");
    }

    @Test
    void invalidThresholdIsRejected() {
        assertThatThrownBy(() -> JobConfig.load(new String[]{"--rules.velocity.threshold=1"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationsAcceptShortAndIsoForms() {
        JobConfig config = JobConfig.load(new String[]{"--rules.velocity.window=PT90S", "--checkpoint.interval=500ms"}, Map.of());

        assertThat(config.rules().velocityWindow()).isEqualTo(Duration.ofSeconds(90));
        assertThat(config.checkpointInterval()).isEqualTo(Duration.ofMillis(500));
    }
}
