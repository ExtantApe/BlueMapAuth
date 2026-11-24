package com.extantape.bluemapauth.web;

import java.net.InetAddress;
import java.util.List;

/**
 * Who a visitor is, for rate limits only. A forwarded address is believed only when the request came straight from a
 * listed proxy; otherwise anyone could claim to be anyone and get a fresh allowance.
 */
public final class ClientAddress {
    private final List<String> trusted;

    public ClientAddress(List<String> trustedProxies) { this.trusted = List.copyOf(trustedProxies); }

    /**
     * The rate-limit key for a visitor. An IPv6 visitor usually holds a whole /64, so the key is the /64; otherwise a
     * few addresses could use up the site-wide sign-in limit (K70 review, 2026-11-24). IPv4 stays per address.
     */
    public String of(InetAddress remote, String forwardedFor) {
        return key(visitor(remote, forwardedFor));
    }

    static String key(String address) {
        InetAddress a = literal(address);
        if (a == null || a.getAddress().length != 16) return address;
        byte[] b = a.getAddress();
        for (int i = 8; i < 16; i++) b[i] = 0;
        try { return InetAddress.getByAddress(b).getHostAddress() + "/64"; } catch (java.net.UnknownHostException e) { return address; }
    }

    private String visitor(InetAddress remote, String forwardedFor) {
        if (!trusted(remote) || forwardedFor == null || forwardedFor.isBlank()) return remote.getHostAddress();
        // Walk from the right: the last address a trusted proxy didn't add is the visitor.
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            InetAddress a = literal(hop);
            if (a == null) return remote.getHostAddress();
            if (!trusted(a)) return a.getHostAddress();
        }
        return remote.getHostAddress();
    }

    boolean trusted(InetAddress a) {
        for (String t : trusted) if (matches(t, a)) return true;
        return false;
    }

    static boolean matches(String rule, InetAddress a) {
        int slash = rule.indexOf('/');
        InetAddress base = literal(slash < 0 ? rule : rule.substring(0, slash));
        if (base == null || base.getAddress().length != a.getAddress().length) return false;
        int bits = slash < 0 ? base.getAddress().length * 8 : Integer.parseInt(rule.substring(slash + 1));
        byte[] x = base.getAddress(), y = a.getAddress();
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >>> (i % 8);
            if ((x[i / 8] & mask) != (y[i / 8] & mask)) return false;
        }
        return true;
    }

    static InetAddress literal(String s) {
        if (s == null || s.isEmpty() || !(s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.contains(":"))) return null;
        try { return InetAddress.ofLiteral(s); } catch (IllegalArgumentException e) { return null; }
    }
}
