package com.finsentinel.batch;

import org.apache.spark.sql.SparkSession;

/** One local SparkSession for all tests in the module (starting one takes seconds). */
final class LocalSpark {

    static final SparkSession SPARK = SparkSession.builder()
            .master("local[2]")
            .appName("batch-job-test")
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.shuffle.partitions", "2")
            .getOrCreate();

    private LocalSpark() {
    }
}
