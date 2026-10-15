package com.finsentinel.platform.cases;

import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.security.Tenancy;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stands in for several platform instances polling at once: many threads, each with its own connection and
 * transaction, claiming from the same table at the same moment. (Removing the advisory lock makes the budget test
 * claim 12-13 against a budget of 10; see ADR 0004.)
 */
@PlatformTest
class ConcurrentCaseClaimTest {

    private static final int CASES = 60;
    private static final int WORKERS = 8;

    @Autowired
    CaseRepository cases;
    @Autowired
    SystemsProperties systems;
    @Autowired
    CaseIntake intake;
    @Autowired
    AlertRepository alerts;
    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void freshQueue() {
        CaseTestSupport.parkOtherCases(jdbc);
        String tenant = uniqueTenant();
        for (int i = 0; i < CASES; i++) {
            intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), Severity.MEDIUM));
        }
    }

    @Test
    void concurrentWorkersNeverClaimTheSameCase() throws Exception {
        assertThat(claimConcurrently(0)).hasSize(CASES).doesNotHaveDuplicates();
    }

    @Test
    void hourlyBudgetHoldsExactlyUnderConcurrency() throws Exception {
        int budget = cases.startedInLastHour() + 10;

        assertThat(claimConcurrently(budget)).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void workerSkipsThePollWhenAnotherInstanceHoldsTheLockTooLong() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> transactions.executeWithoutResult(status -> {
            // Simulates an instance that took the claim lock and then stalled (GC pause, network hiccup).
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CaseRepository.CLAIM_LOCK_KEY).query(rs -> {
            });
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        locked.await();
        CaseWorker worker = new CaseWorker(cases, alerts, analysis,
                CaseWorkerTest.props(1_000, Duration.ofMillis(300)), systems, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));

        int claimed;
        long waitedMs;
        try {
            long started = System.nanoTime();
            claimed = worker.runOnce();
            waitedMs = (System.nanoTime() - started) / 1_000_000;
        } finally {
            // Always let go: a leaked lock would time out every later test that shares this database.
            release.countDown();
            holder.join();
        }
        assertThat(claimed).isZero();
        assertThat(waitedMs).as("gave up near the timeout instead of waiting for the holder").isBetween(250L, 2_000L);
        assertThat(worker.runOnce()).as("next poll works once the lock is free").isEqualTo(5);
    }

    /** All workers start together and keep claiming batches of 3 until nothing is left for them. */
    private List<String> claimConcurrently(int maxPerHour) throws Exception {
        List<String> claimed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        // Claiming spans every bank: the system scope, as CaseWorker does.
                        CaseRepository.ClaimBatch batch = Tenancy.callAsSystem(() ->
                                cases.claimWithinBudget(3, Duration.ofMinutes(5), 3, maxPerHour, Duration.ofSeconds(5)));
                        if (batch.claims().isEmpty()) {
                            return null;
                        }
                        batch.claims().forEach(c -> claimed.add(c.fraudCase().caseId()));
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return claimed;
    }
}
