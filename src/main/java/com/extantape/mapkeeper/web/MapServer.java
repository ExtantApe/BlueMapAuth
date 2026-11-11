package com.extantape.mapkeeper.web;

import com.extantape.mapkeeper.access.Access;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The only way to BlueMap's web map (docs/design.md). A browser without the map's own cookie gets the sign-in page and
 * nothing of the map. A signed-in browser gets BlueMap's web app, the map list cut to the worlds the viewer may enter,
 * those maps' files, and live player positions cut to the players the viewer may see. A map id it may not see is 404,
 * the same as one that doesn't exist.
 */
public final class MapServer {
    public static final String COOKIE = "mapkeeper";
    static final Duration MIN_ANSWER = Duration.ofMillis(400);
    static final int MAX_JSON = 4 * 1024 * 1024;
    private static final int MAX_BODY = 256;
    private static final Pattern MAP_PATH = Pattern.compile("/maps/([A-Za-z0-9_-]{1,64})(/.*)?");
    private static final Pattern BAD_PATH = Pattern.compile("(?i).*(\\.\\.|%2e|%2f|%5c|%00|\\\\|//).*");
    private static final Set<String> PASS_BACK = Set.of("content-type", "content-encoding", "etag", "last-modified");

    public record Settings(String bind, int port, URI publicUrl, URI blueMap, String signInUrl, List<String> trustedProxies,
                           int perIpPer10Minutes, int siteWidePerMinute) {}

    private final Settings settings;
    private final Access access;
    private final Sessions sessions;
    private final RateLimiter limiter;
    private final ClientAddress clients;
    private final Logger log;
    private final String origin;
    private HttpServer server;
    private ExecutorService pool;
    private HttpClient upstream;

    public MapServer(Settings settings, Access access, Logger log, LongSupplier clockMillis) {
        this.settings = settings;
        this.access = access;
        this.log = log;
        this.sessions = new Sessions(access, clockMillis);
        this.limiter = new RateLimiter(settings.perIpPer10Minutes(), settings.siteWidePerMinute(), clockMillis);
        this.clients = new ClientAddress(settings.trustedProxies());
        URI u = settings.publicUrl();
        this.origin = u.getScheme() + "://" + u.getRawAuthority();
    }

    public void start() throws IOException {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        upstream = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral(settings.bind()), settings.port()), 128);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        if (upstream != null) upstream.close();
    }

    Sessions sessions() { return sessions; }

    private void handle(HttpExchange x) {
        // Closed in finally, not by try-with-resources: that would close it before the catch could still answer.
        try {
            String path = x.getRequestURI().getRawPath();
            String method = x.getRequestMethod();
            if (path == null || !path.startsWith("/") || BAD_PATH.matcher(path).matches()) { plain(x, 400, "Bad request."); return; }
            if (path.equals("/handoff")) {
                if (method.equals("GET")) page(x, 200, Pages.handoff(settings.signInUrl()));
                else if (method.equals("POST")) handoff(x);
                else notAllowed(x, "GET, POST");
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) { notAllowed(x, "GET, HEAD"); return; }
            Optional<Access.Viewer> v = sessions.lookup(cookie(x, COOKIE));
            if (v.isEmpty()) {
                if (path.equals("/") || path.equals("/index.html")) page(x, 401, Pages.signIn(settings.signInUrl()));
                else plain(x, 401, "Sign in first.");
                return;
            }
            serve(x, v.get(), path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | IOException e) {
            // Never a stack trace or an upstream detail to the visitor. The log gets the kind of failure, not the request.
            log.log(Level.WARNING, "Map request failed: " + e.getClass().getSimpleName());
            try { plain(x, 502, ""); } catch (IOException | RuntimeException ignored) {}
        } finally {
            x.close();
        }
    }

    // --- signed in ---

    private void serve(HttpExchange x, Access.Viewer v, String path) throws IOException, InterruptedException {
        if (path.equals("/settings.json")) {
            Set<String> allowed = allowedMaps(v);
            String body = fetchJson(path);
            String out = body == null ? null : JsonFilter.settings(body, allowed::contains);
            if (out == null) { plain(x, 502, ""); return; }
            json(x, out);
            return;
        }
        var m = MAP_PATH.matcher(path);
        if (path.startsWith("/maps/")) {
            if (!m.matches() || !allowedMaps(v).contains(m.group(1))) { plain(x, 404, "Not found."); return; }
            if ("/live/players.json".equals(m.group(2))) {
                String body = fetchJson(path);
                json(x, body == null ? JsonFilter.EMPTY_PLAYERS : JsonFilter.players(body, ps -> access.visible(v.uuid(), ps)));
                return;
            }
            pass(x, path, m.group(2) != null && m.group(2).startsWith("/live/") ? "private, no-store" : "private, max-age=300");
            return;
        }
        pass(x, path, "private, no-cache");
    }

    private Set<String> allowedMaps(Access.Viewer v) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : access.mapWorlds().entrySet()) {
            if (access.mayEnter(v.uuid(), e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    // --- hand-off ---

    private void handoff(HttpExchange x) throws IOException {
        long started = System.nanoTime();
        Headers h = x.getRequestHeaders();
        String from = h.getFirst("Origin");
        if (from == null || !from.equals(origin)) { plain(x, 403, "That came from another site."); return; }
        String visitor = clients.of(x.getRemoteAddress().getAddress(), h.getFirst("X-Forwarded-For"));
        long wait = limiter.tryAcquire(visitor);
        if (wait > 0) {
            x.getResponseHeaders().set("Retry-After", Long.toString(wait));
            plain(x, 429, "Too many tries.");
            return;
        }
        String type = h.getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) { plain(x, 415, "Unsupported."); return; }
        byte[] body = x.getRequestBody().readNBytes(MAX_BODY + 1);
        String token = body.length > MAX_BODY ? null : tokenOf(new String(body, StandardCharsets.US_ASCII));
        Optional<Access.Viewer> v = token == null ? Optional.empty() : access.redeem(token);
        if (v.isEmpty()) {
            waitOut(started);
            plain(x, 403, "That link didn't work.");
            return;
        }
        Sessions.Created c = sessions.create(v.get());
        // Host-only (no Domain), so the cookie never goes to any other name on the domain (D43).
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + c.cookie() + "; Path=/; HttpOnly; Secure; SameSite=Strict");
        securityHeaders(x.getResponseHeaders(), Pages.STRICT_CSP);
        x.sendResponseHeaders(204, -1);
    }

    static String tokenOf(String form) {
        if (!form.startsWith("token=") || form.indexOf('&') >= 0) return null;
        String t = URLDecoder.decode(form.substring(6), StandardCharsets.US_ASCII);
        return t.matches("[A-Za-z0-9_-]{43}") ? t : null;
    }

    // --- BlueMap ---

    private HttpRequest.Builder toBlueMap(String path) {
        return HttpRequest.newBuilder(settings.blueMap().resolve(path)).timeout(Duration.ofSeconds(10));
    }

    /** BlueMap's JSON at this path, or null if it's missing, too big or not readable. */
    private String fetchJson(String path) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = upstream.send(toBlueMap(path).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream raw = r.body()) {
            if (r.statusCode() != 200) return null;
            boolean gz = r.headers().firstValue("Content-Encoding").map(s -> s.equalsIgnoreCase("gzip")).orElse(false);
            try (InputStream in = gz ? new GZIPInputStream(raw) : raw) {
                byte[] b = in.readNBytes(MAX_JSON + 1);
                return b.length > MAX_JSON ? null : new String(b, StandardCharsets.UTF_8);
            }
        }
    }

    private void pass(HttpExchange x, String path, String cache) throws IOException, InterruptedException {
        String q = x.getRequestURI().getRawQuery();
        HttpRequest.Builder b = toBlueMap(q == null ? path : path + "?" + q);
        Headers in = x.getRequestHeaders();
        for (String name : List.of("Accept", "Accept-Encoding", "If-None-Match", "If-Modified-Since")) {
            String val = in.getFirst(name);
            if (val != null) b.header(name, val);
        }
        boolean head = x.getRequestMethod().equals("HEAD");
        b.method(head ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody());
        HttpResponse<InputStream> r = upstream.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = r.body()) {
            int status = r.statusCode();
            if (status != 200 && status != 304 && status != 404) { plain(x, 502, ""); return; }
            Headers out = x.getResponseHeaders();
            r.headers().map().forEach((k, vs) -> { if (PASS_BACK.contains(k.toLowerCase(Locale.ROOT)) && !vs.isEmpty()) out.set(k, vs.getFirst()); });
            securityHeaders(out, APP_CSP);
            out.set("Cache-Control", cache);
            if (head || status == 304) { x.sendResponseHeaders(status, -1); return; }
            long len = r.headers().firstValueAsLong("Content-Length").orElse(0);
            x.sendResponseHeaders(status, len > 0 ? len : 0);
            try (OutputStream o = x.getResponseBody()) { body.transferTo(o); }
        }
    }

    // --- answers ---

    /** BlueMap's web app needs scripts, workers and WebGL, so its pages get a looser policy than Mapkeeper's own. */
    static final String APP_CSP = "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'";

    static void securityHeaders(Headers h, String csp) {
        h.set("Content-Security-Policy", csp);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Strict-Transport-Security", "max-age=31536000");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()");
    }

    private static void json(HttpExchange x, String body) throws IOException {
        Headers h = x.getResponseHeaders();
        securityHeaders(h, APP_CSP);
        h.set("Cache-Control", "private, no-store");
        h.set("Content-Type", "application/json; charset=utf-8");
        write(x, 200, body);
    }

    private static void page(HttpExchange x, int status, String html) throws IOException {
        Headers h = x.getResponseHeaders();
        securityHeaders(h, Pages.STRICT_CSP);
        h.set("Content-Type", "text/html; charset=utf-8");
        write(x, status, html);
    }

    private static void plain(HttpExchange x, int status, String text) throws IOException {
        Headers h = x.getResponseHeaders();
        h.clear();
        securityHeaders(h, Pages.STRICT_CSP);
        h.set("Content-Type", "text/plain; charset=utf-8");
        write(x, status, text);
    }

    private static void notAllowed(HttpExchange x, String allow) throws IOException {
        x.getResponseHeaders().set("Allow", allow);
        plain(x, 405, "Not allowed.");
    }

    private static void write(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        boolean head = x.getRequestMethod().equals("HEAD");
        x.sendResponseHeaders(status, head || b.length == 0 ? -1 : b.length);
        if (!head && b.length > 0) try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    private static void waitOut(long startedNanos) {
        long left = MIN_ANSWER.toNanos() - (System.nanoTime() - startedNanos);
        if (left > 0) {
            try { Thread.sleep(Duration.ofNanos(left)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** One cookie by name. Two of the same name is treated as none, since either could be planted. */
    static String cookie(HttpExchange x, String name) {
        String found = null;
        List<String> lines = x.getRequestHeaders().get("Cookie");
        if (lines == null) return null;
        for (String line : lines) {
            for (String part : line.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0 || !part.substring(0, eq).trim().equals(name)) continue;
                if (found != null) return null;
                found = part.substring(eq + 1).trim();
            }
        }
        return found;
    }
}
