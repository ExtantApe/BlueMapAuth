package com.extantape.bluemapauth.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Rate-limit keys (K70 review, 2026-11-24): IPv6 by its /64, IPv4 per address, forwarded only from a trusted proxy. */
class ClientAddressTest {
    final ClientAddress clients = new ClientAddress(List.of("127.0.0.1"));

    @Test
    void addressesInOneIpv6SlashSixtyFourShareOneAllowance() throws Exception {
        InetAddress proxy = InetAddress.ofLiteral("127.0.0.1");
        String a = clients.of(proxy, "2001:db8:1:2:aaaa::1"), b = clients.of(proxy, "2001:db8:1:2:ffff:ffff:ffff:ffff");
        assertEquals(a, b);
        assertEquals("2001:db8:1:2:0:0:0:0/64", a);
        assertNotEquals(a, clients.of(proxy, "2001:db8:1:3::1"), "the next /64 is someone else");
    }

    @Test
    void ipv4StaysPerAddress() {
        InetAddress proxy = InetAddress.ofLiteral("127.0.0.1");
        assertEquals("203.0.113.7", clients.of(proxy, "203.0.113.7"));
        assertNotEquals(clients.of(proxy, "203.0.113.7"), clients.of(proxy, "203.0.113.8"));
    }

    @Test
    void aForwardedAddressFromAnUntrustedSenderIsIgnored() {
        assertEquals("198.51.100.9", clients.of(InetAddress.ofLiteral("198.51.100.9"), "203.0.113.7"));
    }
}
