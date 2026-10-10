package com.finsentinel.batch;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.not;
import static org.apache.spark.sql.functions.to_date;

/**
 * Landing (raw JSON Lines) to curated (typed Parquet). Reads the last few landing days with an explicit schema, sends
 * lines that do not parse or lack a key field to quarantine, drops duplicates by (tenant, transaction id), and
 * overwrites only the days it writes: running it twice gives the same result, and a late event is picked up while its
 * day is inside the lookback.
 */
final class Curation {

    static final String CORRUPT = "_corrupt_record";
    private static final List<String> CHANNELS = List.of("CARD_PRESENT", "CARD_NOT_PRESENT", "TRANSFER");

    /** The landing schema: {@code common.Transaction} as the stream job writes it. */
    static final StructType LANDING_SCHEMA = new StructType()
            .add("txnId", DataTypes.StringType)
            .add("tenantId", DataTypes.StringType)
            .add("accountId", DataTypes.StringType)
            .add("customerId", DataTypes.StringType)
            .add("amount", DataTypes.createDecimalType(18, 2))
            .add("currency", DataTypes.StringType)
            .add("merchantId", DataTypes.StringType)
            .add("merchantCategory", DataTypes.StringType)
            .add("channel", DataTypes.StringType)
            .add("country", DataTypes.StringType)
            .add("eventTime", DataTypes.TimestampType)
            .add(CORRUPT, DataTypes.StringType);

    record Result(long read, long quarantined, long duplicates, long curated) {
    }

    private Curation() {
    }

    /** Curates landing days in [from, toExclusive). */
    static Result run(SparkSession spark, Lake lake, LocalDate from, LocalDate toExclusive, LocalDate runDate) {
        List<String> days = existingDays(spark, lake.landingTransactions(), from, toExclusive);
        if (days.isEmpty()) {
            return new Result(0, 0, 0, 0);
        }
        // Cached: Spark refuses queries on the corrupt-record column alone straight from JSON files.
        Dataset<Row> raw = spark.read().schema(LANDING_SCHEMA).option("mode", "PERMISSIVE")
                .option("columnNameOfCorruptRecord", CORRUPT).json(days.toArray(String[]::new)).cache();
        try {
            Column valid = col(CORRUPT).isNull()
                    .and(col("txnId").isNotNull()).and(col("tenantId").isNotNull()).and(col("accountId").isNotNull())
                    .and(col("amount").isNotNull()).and(col("currency").isNotNull()).and(col("eventTime").isNotNull())
                    .and(col("channel").isin(CHANNELS.toArray()));
            Dataset<Row> good = raw.where(valid).drop(CORRUPT);
            // A null anywhere in the test (a missing channel, say) counts as invalid too.
            Dataset<Row> bad = raw.where(not(coalesce(valid, lit(false))));

            long read = raw.count();
            long quarantined = bad.count();
            if (quarantined > 0) {
                bad.withColumn("run_date", lit(runDate.toString())).write().mode(SaveMode.Overwrite)
                        .option("partitionOverwriteMode", "dynamic").partitionBy("run_date")
                        .json(lake.quarantinedTransactions());
            }
            Dataset<Row> unique = good.dropDuplicates("tenantId", "txnId")
                    .withColumn("dt", to_date(col("eventTime")));
            long curated = unique.count();
            unique.write().mode(SaveMode.Overwrite).option("partitionOverwriteMode", "dynamic").partitionBy("dt")
                    .parquet(lake.curatedTransactions());
            return new Result(read, quarantined, read - quarantined - curated, curated);
        } finally {
            raw.unpersist();
        }
    }

    private static List<String> existingDays(SparkSession spark, String root, LocalDate from, LocalDate toExclusive) {
        List<String> days = new ArrayList<>();
        try {
            for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
                Path day = new Path(root + "/dt=" + d);
                FileSystem fs = day.getFileSystem(spark.sparkContext().hadoopConfiguration());
                if (fs.exists(day)) {
                    days.add(day.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return days;
    }
}
