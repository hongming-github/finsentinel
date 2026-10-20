package com.finsentinel.platform.budget;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Fixed-window rate limit counted in Postgres, so it is exact across any number of platform instances. The upsert
 * takes the window row's lock: concurrent callers are serialised on it and each sees the count including its own call.
 * A fixed window allows up to twice the limit across a window boundary; that is acceptable for a cost guard.
 */
@Component
public class RateLimiter {

    private final JdbcClient jdbc;

    public RateLimiter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts one call against {@code key}; false once the window's limit is used up. */
    @Transactional
    public boolean tryAcquire(String key, int limit, Duration window) {
        String interval = window.toSeconds() + " seconds";
        int used = jdbc.sql("""
                        insert into rate_limit_window (key, window_start, used)
                        values (:key, date_bin(cast(:window as interval), now(), timestamptz '2000-01-01'), 1)
                        on conflict (key, window_start) do update set used = rate_limit_window.used + 1
                        returning used
                        """)
                .param("key", key)
                .param("window", interval)
                .query(Integer.class)
                .single();
        // Old windows are never read again.
        jdbc.sql("delete from rate_limit_window where key = :key and window_start < now() - cast(:keep as interval)")
                .param("key", key)
                .param("keep", window.multipliedBy(2).toSeconds() + " seconds")
                .update();
        return used <= limit;
    }
}
