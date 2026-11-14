package com.extantape.bluemapauth;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class SetupTest {
    static final String GOOD = "enabled: true\npublic-url: https://map.example.org\nsign-in-url: https://example.org\n";

    Setup.Result read(String yaml) {
        return Setup.read(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    @Test
    void theShippedConfigIsOffAndOnlyNeedsItsAddress() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            var r = Setup.read(YamlConfiguration.loadConfiguration(new InputStreamReader(in)));
            assertFalse(r.enabled());
            assertFalse(r.ok());
            assertEquals(1, r.problems().size(), r.problems().toString());
            assertTrue(r.problems().getFirst().startsWith("public-url"));
        }
    }

    @Test
    void aGoodConfigStarts() {
        var r = read(GOOD);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("https://map.example.org", r.settings().publicUrl().toString());
        assertEquals("http://127.0.0.1:8100", r.settings().blueMap().toString());
        assertEquals("multiverse.access.{world}", r.worldPermission());
    }

    @Test
    void neverListensBeyondThisMachine() {
        for (String bind : new String[]{"0.0.0.0", "::", "192.168.1.5", "example.org"})
            assertFalse(read(GOOD + "bind: '" + bind + "'\n").ok(), bind);
    }

    @Test
    void blueMapMustBeOnThisMachine() {
        for (String url : new String[]{"http://192.168.1.5:8100", "https://127.0.0.1:8100", "http://example.org:8100", "http://127.0.0.1:8100/maps", "http://127.0.0.1"})
            assertFalse(read(GOOD + "bluemap-url: '" + url + "'\n").ok(), url);
    }

    @Test
    void addressesMustBePlainHttpsHosts() {
        for (String url : new String[]{"http://map.example.org", "https://map.example.org/x", "https://map.example.org?x=1", "javascript:alert(1)", ""})
            assertFalse(read("enabled: true\npublic-url: '" + url + "'\n").ok(), url);
        var r = read(GOOD.replace("sign-in-url: https://example.org", "sign-in-url: 'javascript:alert(1)'"));
        assertFalse(r.ok());
        assertEquals("", r.settings().signInUrl(), "a bad sign-in link is never put in a page");
    }

    @Test
    void trustedProxiesAreExactAddressesOnly() {
        assertFalse(read(GOOD + "trusted-proxies: ['*']\n").ok());
        assertFalse(read(GOOD + "trusted-proxies: ['example.org']\n").ok());
        assertTrue(read(GOOD + "trusted-proxies: ['127.0.0.1', '::1']\n").ok());
    }

    @Test
    void theWorldPermissionNeedsTheWorldInIt() {
        assertFalse(read(GOOD + "world-permission: 'everyone'\n").ok());
        assertFalse(read(GOOD + "world-permission: 'a {world} b'\n").ok());
        assertTrue(read(GOOD + "world-permission: 'myworlds.{world}.enter'\n").ok());
    }

    @Test
    void rateLimitsStayInRange() {
        assertFalse(read(GOOD + "rate-limits: {per-ip-per-10-minutes: 0}\n").ok());
        assertFalse(read(GOOD + "rate-limits: {site-wide-per-minute: 1000}\n").ok());
    }
}
