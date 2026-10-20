package com.finsentinel.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LatestCheckpointTest {

    @TempDir
    Path dir;

    @Test
    void theNewestCompleteCheckpointAcrossEarlierRuns() throws Exception {
        complete("job-a/chk-40", "2026-10-19T10:00:00Z");
        complete("job-b/chk-3", "2026-10-19T11:00:00Z");
        complete("job-c/chk-7", "2026-10-19T09:00:00Z");
        // In progress when the process died: no _metadata, not restorable.
        Files.createDirectories(dir.resolve("job-b/chk-4"));

        assertThat(LatestCheckpoint.in(dir.toUri().toString()))
                .contains(dir.resolve("job-b/chk-3").toUri().toString());
    }

    @Test
    void noneWithoutACompleteOneOrOnRemoteStorage() throws Exception {
        assertThat(LatestCheckpoint.in(dir.resolve("missing").toUri().toString())).isEmpty();
        Files.createDirectories(dir.resolve("job-a/chk-1"));
        assertThat(LatestCheckpoint.in(dir.toUri().toString())).isEmpty();
        assertThat(LatestCheckpoint.in("s3://bucket/checkpoints")).isEmpty();
    }

    private void complete(String checkpoint, String at) throws Exception {
        Path metadata = Files.createDirectories(dir.resolve(checkpoint)).resolve("_metadata");
        Files.writeString(metadata, "x");
        Files.setLastModifiedTime(metadata, FileTime.from(Instant.parse(at)));
    }
}
