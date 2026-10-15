package com.finsentinel.batch;

import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Layout of the data lake shared with the stream job (ADR 0019). Landing is raw and append-only (written by Flink);
 * curated and gold are rebuilt by this job and can be deleted and recomputed from landing.
 */
record Lake(String root) {

    boolean exists(SparkSession spark, String path) {
        try {
            Path p = new Path(path);
            return p.getFileSystem(spark.sparkContext().hadoopConfiguration()).exists(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JSON Lines from the stream job, {@code dt=YYYY-MM-DD} by UTC event date. */
    String landingTransactions() {
        return root + "/landing/transactions";
    }

    /** Valid, de-duplicated transactions as Parquet, {@code dt=} by UTC event date. */
    String curatedTransactions() {
        return root + "/curated/transactions";
    }

    /** Lines that could not be read, kept with the run that found them, for inspection. */
    String quarantinedTransactions() {
        return root + "/quarantine/transactions";
    }

    /** One snapshot of baselines per run, {@code as_of=YYYY-MM-DD}. */
    String accountBaselines() {
        return root + "/gold/account_baselines";
    }
}
