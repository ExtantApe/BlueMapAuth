package com.extantape.bluemapauth.web;

import com.extantape.bluemapauth.access.Access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The map's own sessions (D43): one random cookie per browser, kept in memory as its SHA-256 only, tied to the Latchkey
 * session it came from. Each is re-checked with Latchkey at most {@link #RECHECK_MS} apart, so signing out there, a
 * revoke or a whitelist removal reaches the map within that time. A restart signs every browser out of the map.
 */
public final class Sessions {
    public static final long RECHECK_MS = 30_000;
    /** However long the Latchkey session lasts, the map asks for a new hand-off after this. */
    public static final long MAX_AGE_MS = 24 * 60 * 60_000L;
    static final int MAX_SESSIONS = 10_000;
    private static final Pattern COOKIE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Entry(Access.Viewer viewer, long since, long checked) {}

    public record Created(Access.Viewer viewer, String cookie) {}

    private final Map<String, Entry> byHash = new LinkedHashMap<>();
    private final Access access;
    private final LongSupplier clock;

    public Sessions(Access access, LongSupplier clockMillis) {
        this.access = access;
        this.clock = clockMillis;
    }

    public synchronized Created create(Access.Viewer v) {
        if (byHash.size() >= MAX_SESSIONS) {
            Iterator<String> it = byHash.keySet().iterator();
            it.next();
            it.remove();
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = clock.getAsLong();
        byHash.put(hash(cookie), new Entry(v, now, now));
        return new Created(v, cookie);
    }

    /** The viewer for a cookie, re-checked with Latchkey when due. Anything doubtful ends the session. */
    public Optional<Access.Viewer> lookup(String cookie) {
        if (cookie == null || !COOKIE.matcher(cookie).matches()) return Optional.empty();
        String h = hash(cookie);
        Entry e;
        synchronized (this) { e = byHash.get(h); }
        if (e == null) return Optional.empty();
        long now = clock.getAsLong();
        if (now - e.since() > MAX_AGE_MS || now < e.since()) { end(h); return Optional.empty(); }
        if (now - e.checked() >= RECHECK_MS || now < e.checked()) {
            // Asked outside the lock: Latchkey reads a file, and one slow answer shouldn't hold up every browser.
            if (!access.live(e.viewer().session())) { end(h); return Optional.empty(); }
            synchronized (this) { if (byHash.containsKey(h)) byHash.put(h, new Entry(e.viewer(), e.since(), now)); }
        }
        return Optional.of(e.viewer());
    }

    public synchronized boolean end(String cookieOrHash) {
        String h = COOKIE.matcher(cookieOrHash).matches() ? hash(cookieOrHash) : cookieOrHash;
        return byHash.remove(h) != null;
    }

    synchronized int size() { return byHash.size(); }

    private static String hash(String cookie) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cookie.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
