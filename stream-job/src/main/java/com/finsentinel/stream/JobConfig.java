package com.finsentinel.stream;

import java.util.Map;

/** Job settings from environment variables, with defaults that match deploy/docker-compose.yml. */
record JobConfig(String bootstrapServers, String groupId, long checkpointIntervalMs, int webUiPort) {

    static JobConfig fromEnv(Map<String, String> env) {
        return new JobConfig(
                env.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                env.getOrDefault("STREAM_GROUP_ID", "finsentinel-stream"),
                Long.parseLong(env.getOrDefault("CHECKPOINT_INTERVAL_MS", "10000")),
                Integer.parseInt(env.getOrDefault("FLINK_LOCAL_UI_PORT", "8082")));
    }
}
