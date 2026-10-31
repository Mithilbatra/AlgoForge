package com.algoforge.common;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter keyed by an arbitrary string (e.g. "submit:42"). Keys idle for longer than every window are
 * forgotten, so keys from one-off callers (any email typed at login) do not pile up.
 * shortcut: in-memory, so limits are per API instance; move to Redis or Postgres when running several API instances.
 */
@Component
public class RateLimiter {
    final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private volatile long longestWindow;

    public void check(String key, int max, Duration window) {
        long now = System.nanoTime(), w = window.toNanos();
        if (w > longestWindow) longestWindow = w;
        hits.compute(key, (k, q) -> {
            Deque<Long> d = q == null ? new ArrayDeque<>() : q;
            while (!d.isEmpty() && now - d.peekFirst() > w) d.pollFirst();
            if (d.size() >= max) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, slow down and retry shortly");
            d.addLast(now);
            return d;
        });
    }

    @Scheduled(fixedDelay = 600_000)
    public void forgetIdle() {
        long now = System.nanoTime();
        hits.forEach((key, q) -> hits.computeIfPresent(key, (k, d) -> now - d.peekLast() > longestWindow ? null : d));
    }
}
