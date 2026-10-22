package com.algoforge.common;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter keyed by an arbitrary string (e.g. "submit:42").
 * shortcut: in-memory, so limits are per API instance; move to Redis or Postgres when running several API instances.
 */
@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String key, int max, Duration window) {
        long now = System.nanoTime();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > window.toNanos()) q.pollFirst();
            if (q.size() >= max) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, slow down and retry shortly");
            q.addLast(now);
        }
    }
}
