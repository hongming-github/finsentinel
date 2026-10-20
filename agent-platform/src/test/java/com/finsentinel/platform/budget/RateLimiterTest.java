package com.finsentinel.platform.budget;

import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class RateLimiterTest {

    @Autowired
    RateLimiter limiter;

    /** Fails with a read-then-write counter: callers released together read the same count. */
    @Test
    void limitIsExactUnderConcurrency() throws Exception {
        for (int round = 0; round < 5; round++) {
            String key = "test:" + UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Boolean>> calls = IntStream.range(0, 40)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        start.await();
                        return limiter.tryAcquire(key, 5, Duration.ofHours(1));
                    })
                    .toList();

            long granted;
            try (ExecutorService pool = Executors.newFixedThreadPool(40)) {
                List<Future<Boolean>> results = calls.stream().map(pool::submit).toList();
                start.countDown();
                granted = results.stream().filter(RateLimiterTest::get).count();
            }

            assertThat(granted).as("round " + round).isEqualTo(5);
        }
    }

    @Test
    void keysAreCountedSeparately() {
        String a = "test:" + UUID.randomUUID();
        String b = "test:" + UUID.randomUUID();

        assertThat(limiter.tryAcquire(a, 1, Duration.ofHours(1))).isTrue();
        assertThat(limiter.tryAcquire(a, 1, Duration.ofHours(1))).isFalse();
        assertThat(limiter.tryAcquire(b, 1, Duration.ofHours(1))).isTrue();
    }

    private static boolean get(Future<Boolean> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
