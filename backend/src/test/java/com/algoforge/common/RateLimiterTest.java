package com.algoforge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void limitsEachWindowAndForgetsIdleKeys() throws Exception {
        RateLimiter limiter = new RateLimiter();
        Duration window = Duration.ofMillis(300);
        limiter.check("login:a", 2, window);
        limiter.check("login:a", 2, window);
        assertThatThrownBy(() -> limiter.check("login:a", 2, window)).isInstanceOf(ApiException.class);
        limiter.check("login:b", 2, window);
        Thread.sleep(400);
        limiter.check("login:a", 2, window);  // the window has moved on
        limiter.forgetIdle();
        assertThat(limiter.hits).containsOnlyKeys("login:a");  // b's last hit is older than every window
    }
}
