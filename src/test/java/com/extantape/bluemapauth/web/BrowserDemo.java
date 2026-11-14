package com.extantape.bluemapauth.web;

import com.extantape.bluemapauth.access.Access;
import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * For checking the hand-off page in a real browser: a made-up BlueMap and a map server on localhost, with one token
 * printed for a made-up player. Not a test; run by hand. Local only, made-up data, stops when the process does.
 */
public final class BrowserDemo {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        UUID kid = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        HttpServer blue = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 8);
        blue.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            String body = switch (p) {
                case "/", "/index.html" -> "<!doctype html><html lang=\"en\"><title>Made-up map</title><h1>Made-up map</h1><p id=\"s\">The map app would load here.</p></html>";
                case "/settings.json" -> "{\"maps\":[\"family_map\",\"adult_map\"]}";
                default -> null;
            };
            if (body == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", p.endsWith(".json") ? "application/json" : "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        blue.start();
        String token = "T".repeat(43);
        Access access = new Access() {
            boolean used;
            public synchronized Optional<Viewer> redeem(String t) {
                if (used || !t.equals(token)) return Optional.empty();
                used = true;
                return Optional.of(new Viewer(kid, "Kid", "s"));
            }
            public boolean live(String s) { return true; }
            public Map<String, String> mapWorlds() { return Map.of("family_map", "family", "adult_map", "adults"); }
            public boolean mayEnter(UUID v, String w) { return w.equals("family"); }
            public Set<UUID> visible(UUID v, Set<UUID> ps) { return Set.of(); }
        };
        var s = new MapServer.Settings("127.0.0.1", port, URI.create("http://localhost:" + port),
                URI.create("http://127.0.0.1:" + blue.getAddress().getPort()), "https://example.org", List.of("127.0.0.1"), 20, 60);
        new MapServer(s, access, Logger.getLogger("demo"), System::currentTimeMillis).start();
        System.out.println("READY http://localhost:" + port + "/handoff#" + token);
    }
}
