package com.finsentinel.stream;

import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drill 11 (ADR 0026): the embedded job killed and started again began from nothing ({@code Restored: 0}), so the
 * rules lost every account's history and large amounts went undetected for minutes. Started the way
 * {@code scripts/stack.sh flink} starts it, a second run now continues from the first run's last checkpoint. The
 * pipeline is a stand-in with the same kind of state: a source's position is restored like the rules' keyed state.
 */
class EmbeddedRestartTest {

    /** The first number each run's operator saw, by run; the source counts from 1. */
    private static final Map<Long, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    private static final AtomicLong RUNS = new AtomicLong();

    @TempDir
    Path checkpoints;

    @Test
    void aRestartedEmbeddedJobContinuesFromTheLastCheckpointOfThePreviousRun() throws Exception {
        JobConfig config = JobConfig.load(new String[]{"--checkpoint.dir=" + checkpoints,
                "--checkpoint.interval=200ms", "--local.web-ui-port=0"}, Map.of());

        assertThat(run(config)).as("first run, nothing to restore").isEqualTo(1);
        // Stopped as a stopped process cancels it: the checkpoint must outlive the job.
        assertThat(LatestCheckpoint.in(config.checkpointDir())).isPresent();

        assertThat(run(config)).as("second run starts after the first run's checkpointed position").isGreaterThan(1);
    }

    @Test
    void aColdStartIsStillPossible() throws Exception {
        JobConfig config = JobConfig.load(new String[]{"--checkpoint.dir=" + checkpoints,
                "--checkpoint.interval=200ms", "--local.web-ui-port=0"}, Map.of());
        run(config);

        JobConfig cold = JobConfig.load(new String[]{"--checkpoint.dir=" + checkpoints,
                "--checkpoint.interval=200ms", "--local.web-ui-port=0", "--checkpoint.restore=none"}, Map.of());
        assertThat(run(cold)).isEqualTo(1);
    }

    /**
     * Drill 12: on the compose cluster {@code flink run} failed unless the cluster's REST port was passed, because the
     * job set its embedded web UI port in every mode, and its checkpoint directory overrode the cluster's.
     */
    @Test
    void onAClusterTheJobLeavesRestAndCheckpointSettingsToTheCluster() {
        JobConfig config = JobConfig.load(new String[0], Map.of());

        StreamExecutionEnvironment env = RiskDetectionJob.environment(config, false);

        assertThat(env.getConfiguration().getOptional(RestOptions.PORT)).isEmpty();
        assertThat(env.getConfiguration().getOptional(CheckpointingOptions.CHECKPOINTS_DIRECTORY)).isEmpty();
        assertThat(RiskDetectionJob.embeddedConfiguration(config).get(RestOptions.PORT)).isEqualTo(8082);
    }

    /** Runs until the first run's operator has seen a number and a checkpoint is complete, then cancels the job. */
    private long run(JobConfig config) throws Exception {
        // Per run: a cancelled job's task may still see a number or two after the next run has begun.
        long runId = RUNS.incrementAndGet();
        long checkpointsBefore = completeCheckpoints();
        StreamExecutionEnvironment env = RiskDetectionJob.environment(config, true);
        env.setParallelism(1);
        env.fromSequence(1, Long.MAX_VALUE).uid("numbers")
                .map(n -> {
                    FIRST_SEEN.putIfAbsent(runId, n);
                    return n;
                }).uid("first-seen")
                .sinkTo(new DiscardingSink<>()).uid("discard");
        JobClient job = env.executeAsync("embedded-restart-test");
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!FIRST_SEEN.containsKey(runId) || completeCheckpoints() <= checkpointsBefore) {
                assertThat(System.nanoTime()).as("a checkpoint within 60 s").isLessThan(deadline);
                Thread.sleep(50);
            }
        } finally {
            job.cancel().get(30, TimeUnit.SECONDS);
        }
        return FIRST_SEEN.get(runId);
    }

    private long completeCheckpoints() throws Exception {
        try (var files = java.nio.file.Files.find(checkpoints, 3,
                (path, attrs) -> path.getFileName().toString().equals("_metadata"))) {
            return files.count();
        }
    }
}
