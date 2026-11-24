package com.extantape.bluemapauth.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Two limits on tries: per visitor over 10 minutes, counting every try so a guesser learns nothing from which ones
 * count, and for everyone together over a minute, counting only failed tries. Real sign-ins never use up the
 * site-wide budget, so a few addresses sending good links can't lock everyone else out (K70 review L2, 2026-11-24).
 */
public final class RateLimiter {
    private static final long VISITOR_WINDOW = 10 * 60_000L;
    private static final long SITE_WINDOW = 60_000L;
    private static final int MAX_VISITORS_TRACKED = 50_000;

    private final int perVisitor;
    private final int siteWide;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> visitors = new HashMap<>();
    private final Deque<Long> site = new ArrayDeque<>();

    public RateLimiter(int perVisitorPer10Minutes, int siteWidePerMinute, LongSupplier clockMillis) {
        this.perVisitor = perVisitorPer10Minutes;
        this.siteWide = siteWidePerMinute;
        this.clock = clockMillis;
    }

    /** Zero if this visitor's try may go ahead (and counts it), otherwise how many seconds until it may. */
    public synchronized long tryAcquire(String visitor) {
        long now = clock.getAsLong();
        trim(site, now - SITE_WINDOW);
        Deque<Long> mine = visitors.computeIfAbsent(visitor, k -> new ArrayDeque<>());
        trim(mine, now - VISITOR_WINDOW);
        if (mine.size() >= perVisitor) return seconds(mine.peekFirst() + VISITOR_WINDOW - now);
        mine.addLast(now);
        if (visitors.size() > MAX_VISITORS_TRACKED) forgetIdle(now);
        return 0;
    }

    /**
     * A try that didn't work: counts toward the site-wide limit, and says how many seconds everyone's failed tries
     * must wait, or zero. A link that works is never refused by the site-wide limit, so junk tries from a few
     * addresses can't lock real players out (K70 review L2, 2026-11-24). The links' 256 random bits are what stop
     * guessing; this only slows the feedback to a flood.
     */
    public synchronized long failed() {
        long now = clock.getAsLong();
        trim(site, now - SITE_WINDOW);
        if (site.size() >= siteWide) return seconds(site.peekFirst() + SITE_WINDOW - now);
        site.addLast(now);
        return 0;
    }

    private void forgetIdle(long now) {
        for (Iterator<Deque<Long>> it = visitors.values().iterator(); it.hasNext(); ) {
            Deque<Long> d = it.next();
            trim(d, now - VISITOR_WINDOW);
            if (d.isEmpty()) it.remove();
        }
    }

    private static void trim(Deque<Long> d, long before) {
        while (!d.isEmpty() && d.peekFirst() <= before) d.removeFirst();
    }

    private static long seconds(long millis) { return Math.max(1, (millis + 999) / 1000); }
}
