package com.extantape.bluemapauth.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K70 review L2, 2026-11-24: real sign-ins never use up the site-wide budget; failed tries do. */
class RateLimiterTest {
    final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void goodSignInsFromAFewAddressesDontLockOutEveryoneElse() {
        RateLimiter l = new RateLimiter(20, 5, now::get);
        for (int ip = 0; ip < 4; ip++) for (int i = 0; i < 20; i++) assertEquals(0, l.tryAcquire("10.0.0." + ip));
        assertEquals(0, l.tryAcquire("203.0.113.9"), "a newcomer still gets in");
    }

    @Test
    void failedTriesFillTheSiteWideBudget() {
        RateLimiter l = new RateLimiter(20, 5, now::get);
        for (int i = 0; i < 5; i++) { assertEquals(0, l.tryAcquire("10.0.0." + i)); assertEquals(0, l.failed()); }
        assertEquals(0, l.tryAcquire("203.0.113.9"), "a real player's try still goes ahead");
        assertTrue(l.failed() > 0, "but another failure in that minute is told to wait");
        now.addAndGet(61_000);
        assertEquals(0, l.failed(), "and a minute later it clears");
    }

    @Test
    void everyTryCountsPerVisitor() {
        RateLimiter l = new RateLimiter(3, 100, now::get);
        for (int i = 0; i < 3; i++) assertEquals(0, l.tryAcquire("a"));
        assertTrue(l.tryAcquire("a") > 0);
    }
}
