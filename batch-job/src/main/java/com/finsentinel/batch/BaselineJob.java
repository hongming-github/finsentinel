package com.finsentinel.batch;

import com.finsentinel.contracts.HttpSchemaRegistry;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The daily batch (ADR 0019): curate the last few landing days, compute every account's baseline over the window
 * before {@code as-of}, keep the snapshot as Parquet, and publish it for the stream job. Run once a day after midnight
 * UTC by any scheduler; re-running a day is safe.
 */
public final class BaselineJob {

    private static final Logger log = LoggerFactory.getLogger(BaselineJob.class);

    private BaselineJob() {
    }

    public static void main(String[] args) throws IOException {
        BatchConfig config = BatchConfig.load(args, System.getenv(), Clock.systemUTC());
        SparkSession.Builder builder = SparkSession.builder().appName("finsentinel-baselines")
                // Days are UTC everywhere: landing directories, curated partitions, the baseline window.
                .config("spark.sql.session.timeZone", "UTC")
                // The baselines are cached (counted, written, published); without this AQE may not coalesce a cached
                // plan's shuffle partitions, and 100 accounts became 82 Parquet files and 82 Kafka producers.
                .config("spark.sql.optimizer.canChangeCachedPlanOutputPartitioning", "true")
                .config("spark.ui.enabled", "false");
        if (!config.sparkMaster().isEmpty()) {
            builder.master(config.sparkMaster());
        }
        try (SparkSession spark = builder.getOrCreate()) {
            String url = config.schemaRegistryUrl();
            Duration timeout = config.schemaRegistryTimeout();
            run(spark, config, () -> new HttpSchemaRegistry(url, timeout), Instant.now());
        }
    }

    static Summary run(SparkSession spark, BatchConfig config, BaselinePublisher.RegistryFactory registry,
                       Instant now) {
        Lake lake = new Lake(config.lakeDir());
        LocalDate asOf = config.asOf();
        Curation.Result curation = Curation.run(spark, lake, asOf.minusDays(config.curateLookbackDays()), asOf, asOf);
        log.info("Curated landing {} to {}: {} lines read, {} quarantined, {} duplicates dropped, {} curated",
                asOf.minusDays(config.curateLookbackDays()), asOf.minusDays(1), curation.read(),
                curation.quarantined(), curation.duplicates(), curation.curated());

        if (!lake.exists(spark, lake.curatedTransactions())) {
            // A first run, or nothing has landed yet: no history means no baselines, not a failed run.
            log.info("No curated transactions yet in {}: no baselines as of {}", lake.root(), asOf);
            return new Summary(curation, 0);
        }
        Dataset<Row> baselines = Baselines.compute(spark, lake, asOf, config.windowDays(), now).cache();
        try {
            long accounts = baselines.count();
            Baselines.write(baselines, lake, asOf);
            if (config.publish()) {
                baselines.foreachPartition(new BaselinePublisher(config.bootstrapServers(), registry));
            }
            log.info("Baselines as of {} over {} days: {} accounts, {}", asOf, config.windowDays(), accounts,
                    config.publish() ? "published" : "not published (publish.enabled=false)");
            return new Summary(curation, accounts);
        } finally {
            baselines.unpersist();
        }
    }

    record Summary(Curation.Result curation, long accounts) {
    }
}
