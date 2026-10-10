package com.finsentinel.batch;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.apache.spark.sql.functions.abs;
import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;

/**
 * Per-account spending baselines over the curated history (ADR 0019): the window is the {@code windowDays} complete
 * UTC days before {@code asOf}. Centre and spread are the median and 1.4826 x the median absolute deviation (MAD),
 * which equals the standard deviation for normally distributed amounts but ignores a few extreme ones: the batch job
 * cannot see which past amounts were fraud, and one large fraudulent payment must not make the next look normal.
 * Exact percentiles: an account has at most a few thousand transactions in the window.
 */
final class Baselines {

    /** Scales the MAD to a standard deviation for normally distributed data. */
    static final double MAD_TO_STD_DEV = 1.4826;

    private Baselines() {
    }

    static Dataset<Row> compute(SparkSession spark, Lake lake, LocalDate asOf, int windowDays, Instant computedAt) {
        Dataset<Row> window = spark.read().parquet(lake.curatedTransactions())
                .where(col("dt").geq(lit(asOf.minusDays(windowDays))).and(col("dt").lt(lit(asOf))))
                .select(col("tenantId"), col("accountId"), col("amount").cast("double").as("amount"));

        Dataset<Row> centre = window.groupBy("tenantId", "accountId").agg(
                count(lit(1)).as("sampleSize"),
                expr("percentile(amount, 0.5)").as("medianAmount"),
                avg("amount").as("meanAmount"),
                expr("percentile(amount, 0.95)").as("p95Amount"),
                max("amount").as("maxAmount"));
        Dataset<Row> spread = window.join(centre.select("tenantId", "accountId", "medianAmount"),
                        new String[]{"tenantId", "accountId"})
                .withColumn("deviation", abs(col("amount").minus(col("medianAmount"))))
                .groupBy("tenantId", "accountId")
                .agg(expr("percentile(deviation, 0.5)").multiply(MAD_TO_STD_DEV).as("robustStdDev"));

        return centre.join(spread, new String[]{"tenantId", "accountId"}).select(
                col("tenantId"), col("accountId"),
                lit(asOf).as("asOf"), lit(windowDays).as("windowDays"),
                col("sampleSize"), col("medianAmount"), col("robustStdDev"), col("meanAmount"), col("p95Amount"),
                col("maxAmount"), lit(Timestamp.from(computedAt)).as("computedAt"));
    }

    /** The run's snapshot, replacing an earlier run for the same as-of date. */
    static void write(Dataset<Row> baselines, Lake lake, LocalDate asOf) {
        baselines.drop("asOf").write().mode(SaveMode.Overwrite)
                .parquet(lake.accountBaselines() + "/as_of=" + asOf);
    }
}
