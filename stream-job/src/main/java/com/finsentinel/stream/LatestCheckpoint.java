package com.finsentinel.stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The newest complete checkpoint under a checkpoint directory, as Flink lays it out: {@code <dir>/<job id>/chk-<n>/},
 * complete once its {@code _metadata} file is written. Each start of the embedded job has a new job id, so the newest
 * is looked for across all of them. Local file systems only: a cluster's storage (s3://...) is the cluster's business.
 */
final class LatestCheckpoint {

    private LatestCheckpoint() {
    }

    /** @return the checkpoint's directory as a {@code file://} URI, or empty if there is none or the storage is remote */
    static Optional<String> in(String checkpointDir) {
        if (!checkpointDir.startsWith("file:")) {
            return Optional.empty();
        }
        Path root = Path.of(URI.create(checkpointDir));
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> metadata = Files.find(root, 3, (path, attrs) -> attrs.isRegularFile()
                && path.getFileName().toString().equals("_metadata")
                && path.getParent().getFileName().toString().startsWith("chk-"))) {
            return metadata.max(Comparator.comparing(LatestCheckpoint::modified))
                    .map(path -> path.getParent().toUri().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list checkpoints in " + root, e);
        }
    }

    private static FileTime modified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
