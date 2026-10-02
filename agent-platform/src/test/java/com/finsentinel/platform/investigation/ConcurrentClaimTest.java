package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.alert.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

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
                                queue.claimWithinBudget(3, Duration.ofMinutes(5), 3, maxPerHour);
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
