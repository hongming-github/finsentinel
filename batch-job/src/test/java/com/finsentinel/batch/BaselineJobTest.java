package com.finsentinel.batch;

import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.InMemorySchemaRegistry;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BaselineJobTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    private static final InMemorySchemaRegistry REGISTRY = InMemorySchemaRegistry.withContracts();

    @TempDir
    Path lake;

    @Test
    void curationQuarantinesBadLinesDropsDuplicatesAndCanBeRerun() {
        Landing landing = new Landing(lake);
        Transaction t1 = landing.txn("straits", "acc-1", "12.50", Instant.parse("2026-10-08T10:00:00Z"));
        Transaction late = landing.txn("straits", "acc-1", "99.00", Instant.parse("2026-10-09T23:59:59Z"));
        landing.write(t1).write(t1).write(late)
                .line(LocalDate.parse("2026-10-09"), "{\"txnId\": \"broken")
                .line(LocalDate.parse("2026-10-09"), "{\"txnId\":\"no-amount\",\"tenantId\":\"straits\","
                        + "\"accountId\":\"acc-1\",\"currency\":\"SGD\",\"channel\":\"TRANSFER\","
                        + "\"eventTime\":\"2026-10-09T08:00:00Z\"}")
                .line(LocalDate.parse("2026-10-09"), "{\"txnId\":\"cash\",\"tenantId\":\"straits\","
                        + "\"accountId\":\"acc-1\",\"amount\":5,\"currency\":\"SGD\",\"channel\":\"CASH\","
                        + "\"eventTime\":\"2026-10-09T08:00:00Z\"}");

        BaselineJob.Summary first = run(Map.of());
        BaselineJob.Summary second = run(Map.of());

        assertThat(first.curation()).isEqualTo(new Curation.Result(6, 3, 1, 2));
        assertThat(second.curation()).isEqualTo(first.curation());
        List<Row> curated = LocalSpark.SPARK.read().parquet(new Lake(lake.toString()).curatedTransactions())
                .orderBy("eventTime").collectAsList();
        assertThat(curated).extracting(r -> r.<String>getAs("txnId")).containsExactly(t1.txnId(), late.txnId());
        assertThat(curated).extracting(r -> r.getAs("dt").toString()).containsExactly("2026-10-08", "2026-10-09");
        assertThat(LocalSpark.SPARK.read().json(new Lake(lake.toString()).quarantinedTransactions()).count())
                .isEqualTo(3);
    }

    @Test
    void aBaselineIsRobustToOneHugeAmountAndCoversOnlyTheWindow() {
        Landing landing = new Landing(lake);
        Instant day = Instant.parse("2026-10-09T12:00:00Z");
        for (int i = 0; i < 4; i++) {
            for (String amount : List.of("90", "95", "100", "105", "110")) {
                landing.write(landing.txn("harbour", "acc-7", amount, day.minusSeconds(60L * i)));
            }
        }
        landing.write(landing.txn("harbour", "acc-7", "50000", day.minusSeconds(3600)));
        // Outside the window: on the as-of day itself, and before it starts.
        landing.write(landing.txn("harbour", "acc-7", "77777", Instant.parse("2026-10-10T00:30:00Z")));
        landing.write(landing.txn("harbour", "acc-7", "88888", Instant.parse("2026-09-01T12:00:00Z")));

        // Curate everything written, then a 30-day baseline.
        run(Map.of("curate.lookback-days", "60"));
        Row baseline = LocalSpark.SPARK.read()
                .parquet(new Lake(lake.toString()).accountBaselines() + "/as_of=" + AS_OF).first();

        assertThat(baseline.<Long>getAs("sampleSize")).isEqualTo(21);
        assertThat(baseline.<Double>getAs("medianAmount")).isEqualTo(100.0);
        // Deviations: 0 x4, 5 x8, 10 x8, 49900: median 5, scaled by 1.4826.
        assertThat(baseline.<Double>getAs("robustStdDev")).isCloseTo(5 * 1.4826, within(1e-9));
        // The mean is what the fraud would have done to a mean-based baseline.
        assertThat(baseline.<Double>getAs("meanAmount")).isCloseTo(52000.0 / 21, within(1e-6));
        assertThat(baseline.<Double>getAs("maxAmount")).isEqualTo(50000.0);
    }

    @Test
    void aLakeWithNothingInItYieldsNoBaselinesRatherThanAFailedRun() {
        BaselineJob.Summary summary = run(Map.of());

        assertThat(summary.curation()).isEqualTo(new Curation.Result(0, 0, 0, 0));
        assertThat(summary.accounts()).isZero();
    }

    private BaselineJob.Summary run(Map<String, String> overrides) {
        List<String> args = new java.util.ArrayList<>(List.of("--lake.dir=" + lake, "--as-of=" + AS_OF,
                "--baseline.window-days=30", "--publish.enabled=false"));
        overrides.forEach((k, v) -> args.add("--" + k + "=" + v));
        BatchConfig config = BatchConfig.load(args.toArray(String[]::new), Map.of(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return BaselineJob.run(LocalSpark.SPARK, config, () -> REGISTRY, NOW);
    }
}
