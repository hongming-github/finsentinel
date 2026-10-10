package com.finsentinel.stream;

import com.finsentinel.common.Transaction;
import org.apache.flink.api.common.serialization.Encoder;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.OutputFileConfig;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.SimpleVersionedStringSerializer;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The raw landing zone the batch job reads (ADR 0019): every readable transaction as one JSON line, in a directory per
 * UTC event date ({@code dt=2026-10-10}), so a late event lands in its own day and the batch job can re-curate a few
 * recent days. Files are committed on checkpoints: a file the batch job can see is complete and, after a restart
 * from a checkpoint, not written twice. In-progress files start with a dot, which Spark ignores.
 */
final class LandingZone {

    static final String TRANSACTIONS = "landing/transactions";

    private LandingZone() {
    }

    static FileSink<Transaction> transactions(String lakeDir) {
        return FileSink.forRowFormat(new Path(lakeDir, TRANSACTIONS), new JsonLines())
                .withBucketAssigner(new EventDate())
                .withRollingPolicy(OnCheckpointRollingPolicy.build())
                .withOutputFileConfig(OutputFileConfig.builder().withPartPrefix("txn").withPartSuffix(".jsonl")
                        .build())
                .build();
    }

    static final class JsonLines implements Encoder<Transaction> {

        /** Jackson 3 mappers are immutable and thread-safe; Instants are written as ISO-8601 text. */
        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Override
        public void encode(Transaction txn, OutputStream out) throws IOException {
            out.write(JSON.writeValueAsBytes(txn));
            out.write('\n');
        }
    }

    static final class EventDate implements BucketAssigner<Transaction, String> {

        @Override
        public String getBucketId(Transaction txn, Context context) {
            return "dt=" + LocalDate.ofInstant(txn.eventTime(), ZoneOffset.UTC);
        }

        @Override
        public SimpleVersionedSerializer<String> getSerializer() {
            return SimpleVersionedStringSerializer.INSTANCE;
        }
    }
}
