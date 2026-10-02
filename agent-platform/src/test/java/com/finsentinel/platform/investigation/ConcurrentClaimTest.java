package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.alert.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stands in for several platform instances polling at once: many threads, each with its own connection and
 * transaction, claiming from the same table at the same moment.
 */
@PlatformTest
class ConcurrentClaimTest {

    private static final int ALERTS = 60;
    private static final int WORKERS = 8;

    @Autowired
    InvestigationRepository queue;
    @Autowired
    AlertRepository alerts;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    StubCaseInvestigator investigator;

    @BeforeEach
    void freshQueue() {
        jdbc.sql("update alert set status = 'FAILED' where status in ('NEW', 'INVESTIGATING')").update();
        String tenant = uniqueTenant();
        for (int i = 0; i < ALERTS; i++) {
            alerts.insertIfAbsent(alert(tenant, "acc-" + UUID.randomUUID(), AlertRule.VELOCITY, Severity.MEDIUM,
                    List.of("t"), Instant.now()));
        }
    }

    @Test
    void concurrentWorkersNeverClaimTheSameAlert() throws Exception {
        List<String> claimed = claimConcurrently(0);

        assertThat(claimed).hasSize(ALERTS).doesNotHaveDuplicates();
    }

    @Test
    void hourlyBudgetHoldsExactlyUnderConcurrency() throws Exception {
        int budget = queue.startedInLastHour() + 10;

        List<String> claimed = claimConcurrently(budget);

        assertThat(claimed).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void workerSkipsThePollWhenAnotherInstanceHoldsTheLockTooLong() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> transactions.executeWithoutResult(status -> {
            // Simulates an instance that took the claim lock and then stalled (GC pause, network hiccup).
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", InvestigationRepository.CLAIM_LOCK_KEY)
                    .query(rs -> {
                    });
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        locked.await();
        InvestigationWorker worker = new InvestigationWorker(queue, investigator, new InvestigationProperties(true, 3,
                Duration.ofMinutes(5), 3, 1_000, Duration.ofMillis(300), new InvestigationProperties.Tools(90, 100),
                new InvestigationProperties.Report(60, 40, 1, true)));

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
        assertThat(worker.runOnce()).as("next poll works once the lock is free").isEqualTo(3);
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
                        InvestigationRepository.ClaimBatch batch =
                                queue.claimWithinBudget(3, Duration.ofMinutes(5), 3, maxPerHour, Duration.ofSeconds(5));
                        if (batch.claims().isEmpty()) {
                            return null;
                        }
                        batch.claims().forEach(c -> claimed.add(c.alert().alert().alertId()));
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
