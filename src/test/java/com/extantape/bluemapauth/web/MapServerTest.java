package com.extantape.bluemapauth.web;

import com.extantape.bluemapauth.access.Access;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map server against a made-up BlueMap and made-up players. Kid is in the "family" circle with Mum and may enter
 * only the family world; Adult may enter both worlds and shares no circle with Kid; Stranger is in the family world but
 * in no circle.
 */
class MapServerTest {
    static final String ORIGIN = "https://map.example.org";
    static final UUID KID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID MUM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID ADULT = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final Map<String, Access.Viewer> tokens = new ConcurrentHashMap<>();
    final Set<String> liveSessions = ConcurrentHashMap.newKeySet();
    final Map<UUID, Set<String>> worldsOf = new HashMap<>();
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final List<Map<String, List<String>>> upstreamSaw = new CopyOnWriteArrayList<>();
    final AtomicBoolean upstreamBroken = new AtomicBoolean();
    volatile String playersJson;
    HttpServer blueMap;
    MapServer server;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** The rules: in game, chat's reach (here: same circle, or same world); away, circle only (D41, D54). */
    final Access access = new Access() {
        public Optional<Viewer> redeem(String token) { return Optional.ofNullable(tokens.remove(token)); }
        public boolean live(String session) { return liveSessions.contains(session); }
        public Map<String, String> mapWorlds() { return Map.of("family_map", "family", "family_cave", "family", "adult_map", "adults"); }
        public boolean mayEnter(UUID viewer, String world) { return worldsOf.getOrDefault(viewer, Set.of()).contains(world); }
        public Set<UUID> visible(UUID viewer, Set<UUID> players) {
            Set<UUID> out = new HashSet<>();
            for (UUID p : players) {
                if (p.equals(viewer)) { out.add(p); continue; }
                boolean family = Set.of(KID, MUM).containsAll(Set.of(viewer, p));
                boolean sameWorld = online.contains(viewer) && !viewer.equals(KID) && !p.equals(KID);
                if (family || sameWorld) out.add(p);
            }
            return out;
        }
    };

    @BeforeEach
    void up() throws Exception {
        worldsOf.put(KID, Set.of("family"));
        worldsOf.put(MUM, Set.of("family"));
        worldsOf.put(ADULT, Set.of("family", "adults"));
        worldsOf.put(STRANGER, Set.of("family"));
        playersJson = "{\"players\":[" + player(KID) + "," + player(MUM) + "," + player(ADULT) + "," + player(STRANGER) + "]}";
        blueMap = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 16);
        blueMap.createContext("/", x -> {
            upstreamSaw.add(Map.copyOf(x.getRequestHeaders()));
            String p = x.getRequestURI().getPath();
            if (upstreamBroken.get()) { x.sendResponseHeaders(500, -1); x.close(); return; }
            String body = switch (p) {
                case "/settings.json" -> "{\"version\":\"5.0\",\"maps\":[\"family_map\",\"family_cave\",\"adult_map\"],\"startLocation\":null}";
                case "/maps/family_map/live/players.json", "/maps/adult_map/live/players.json" -> playersJson;
                case "/maps/family_map/live/markers.json" -> "{\"family-markers\":{}}";
                case "/maps/adult_map/live/markers.json" -> "{\"adult-markers\":{\"label\":\"secret base\"}}";
                case "/maps/family_map/tiles/0/x0/z0.prbm", "/maps/adult_map/tiles/0/x0/z0.prbm" -> "TILE";
                case "/", "/index.html" -> "<!doctype html><title>BlueMap</title>";
                default -> null;
            };
            if (body == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", p.endsWith(".json") ? "application/json" : "text/html");
            x.getResponseHeaders().set("X-Upstream-Secret", "should-not-pass");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        blueMap.start();
        start();
    }

    void start() throws Exception {
        if (server != null) server.stop();
        var s = new MapServer.Settings("127.0.0.1", 0, URI.create(ORIGIN), URI.create("http://127.0.0.1:" + blueMap.getAddress().getPort()),
                "https://example.org", List.of("127.0.0.1"), 20, 60);
        server = new MapServer(s, access, Logger.getLogger("test"), now::get);
        server.start();
    }

    @AfterEach
    void down() {
        server.stop();
        blueMap.stop(0);
    }

    static String player(UUID u) {
        return "{\"uuid\":\"" + u + "\",\"name\":\"P" + u.toString().charAt(35) + "\",\"foreign\":false,\"position\":{\"x\":1,\"y\":64,\"z\":2}}";
    }

    // --- helpers ---

    HttpResponse<String> get(String path, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (cookie != null) b.header("Cookie", MapServer.COOKIE + "=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postHandoff(String body, String origin) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/handoff"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) b.header("Origin", origin);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A token as WelcomeWagon would issue it, for this player and a live session. */
    String token(UUID who) {
        String t = UUID.randomUUID().toString().replace("-", "") + "0123456789a";
        String session = "s-" + who;
        liveSessions.add(session);
        tokens.put(t, new Access.Viewer(who, "P", session));
        return t;
    }

    String signIn(UUID who) throws Exception {
        var r = postHandoff("token=" + token(who), ORIGIN);
        assertEquals(204, r.statusCode(), r.body());
        String set = r.headers().firstValue("set-cookie").orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    // --- nothing for a stranger ---

    @Test
    void aVisitorWhoIsntSignedInGetsNothingOfTheMap() throws Exception {
        var home = get("/", null);
        assertEquals(401, home.statusCode());
        assertTrue(home.body().contains("Sign in to see the map"));
        assertTrue(home.body().contains("href=\"https://example.org\""));
        for (String p : List.of("/settings.json", "/maps/family_map/tiles/0/x0/z0.prbm", "/maps/family_map/live/players.json",
                "/maps/adult_map/live/markers.json", "/index.html", "/assets/app.js")) {
            var r = get(p, null);
            assertEquals(401, r.statusCode(), p);
            assertFalse(r.body().contains("family_map") || r.body().contains("adult") || r.body().contains("uuid"), p);
        }
        assertTrue(upstreamSaw.isEmpty(), "BlueMap is never asked for a visitor who isn't signed in");
    }

    @Test
    void aMadeUpOrForeignCookieIsNoSession() throws Exception {
        signIn(KID);
        for (String c : List.of("x", "A".repeat(43), "../../etc", "s-" + KID))
            assertEquals(401, get("/settings.json", c).statusCode(), c);
    }

    // --- the hand-off (D43) ---

    @Test
    void aHandOffSetsAHostOnlyStrictCookieOnce() throws Exception {
        String t = token(KID);
        var r = postHandoff("token=" + t, ORIGIN);
        assertEquals(204, r.statusCode());
        String set = r.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(set.startsWith(MapServer.COOKIE + "="));
        assertTrue(set.contains("HttpOnly") && set.contains("Secure") && set.contains("SameSite=Strict") && set.contains("Path=/"), set);
        assertFalse(set.toLowerCase().contains("domain="), "host-only: never a cookie for the whole domain");
        assertFalse(set.toLowerCase().contains("max-age") || set.toLowerCase().contains("expires"), "gone when the browser closes");
        assertEquals(403, postHandoff("token=" + t, ORIGIN).statusCode(), "a token works once");
    }

    @Test
    void handOffsFromElsewhereOrMalformedAreRefused() throws Exception {
        String t = token(KID);
        assertEquals(403, postHandoff("token=" + t, null).statusCode(), "no Origin");
        assertEquals(403, postHandoff("token=" + t, "https://evil.example").statusCode());
        assertEquals(403, postHandoff("token=" + t, "https://example.org").statusCode(), "not even the invite page itself");
        assertTrue(tokens.containsKey(t), "a refused post doesn't use the token up");
        assertEquals(403, postHandoff("token=short", ORIGIN).statusCode());
        assertEquals(403, postHandoff("token=" + t + "&x=1", ORIGIN).statusCode());
        assertEquals(403, postHandoff("token=" + "A".repeat(300), ORIGIN).statusCode());
        var json = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/handoff"))
                .header("Origin", ORIGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + t + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, json.statusCode());
    }

    @Test
    void aPlayerInAnotherWorldIsNeverSentEvenIfReachable() throws Exception {
        // K70 review M1: with BlueMap's hide-different-world off, players elsewhere come back marked foreign.
        String kid = signIn(KID);
        playersJson = "{\"players\":[" + player(KID) + "," + player(MUM).replace("\"foreign\":false", "\"foreign\":true") + "]}";
        var r = get("/maps/family_map/live/players.json", kid).body();
        assertTrue(r.contains(KID.toString()), r);
        assertFalse(r.contains(MUM.toString()), "Mum is in the circle but in another world, maybe one the kid can't enter");
        playersJson = "{\"players\":[" + player(MUM).replace("\"foreign\":false", "\"foreign\":\"yes\"") + "]}";
        assertFalse(get("/maps/family_map/live/players.json", kid).body().contains(MUM.toString()), "an odd flag counts as foreign");
    }

    @Test
    void aPlayerEntryWithNoForeignFlagIsLeftOut() throws Exception {
        String kid = signIn(KID);
        playersJson = "{\"players\":[" + player(KID) + "," + player(MUM).replace(",\"foreign\":false", "") + "]}";
        var r = get("/maps/family_map/live/players.json", kid).body();
        assertTrue(r.contains(KID.toString()) && !r.contains(MUM.toString()), r);
    }

    @Test
    void aWorkingLinkStillSignsInWhenJunkTriesHaveFilledTheSiteWideLimit() throws Exception {
        // K70 review L2: 60 junk tries in a minute used to lock real players out too.
        int refused = 0;
        for (int i = 0; i < 70; i++) {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/handoff")).header("Origin", ORIGIN)
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-Forwarded-For", "198.51.100." + (i / 15))
                    .POST(HttpRequest.BodyPublishers.ofString("token=" + "J".repeat(43))).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) refused++;
        }
        assertTrue(refused > 0, "junk is slowed once the site-wide limit is full");
        signIn(MUM); // throws unless the hand-off answers 204
    }

    @Test
    void anyCaseOfLiveIsTreatedAsLive() throws Exception {
        String kid = signIn(KID);
        int before = upstreamSaw.size();
        for (String p : List.of("/maps/family_map/Live/players.json", "/maps/family_map/LIVE/sse", "/maps/family_map/lIvE/markers.json"))
            assertEquals(404, get(p, kid).statusCode(), p);
        assertEquals(before, upstreamSaw.size(), "BlueMap is never asked");
    }

    @Test
    void aPlayerHeadIsOnlyThereForPlayersTheViewerMaySee() throws Exception {
        // K70 review L3: a head picture says the player has joined, and shows their skin.
        String kid = signIn(KID);
        int before = upstreamSaw.size();
        assertEquals(404, get("/maps/family_map/assets/playerheads/" + ADULT + ".png", kid).statusCode());
        assertEquals(404, get("/maps/family_map/assets/playerheads/" + STRANGER + ".png", kid).statusCode());
        assertEquals(404, get("/maps/family_map/assets/playerheads/alex.png", kid).statusCode());
        assertEquals(404, get("/maps/family_map/assets/PlayerHeads/" + ADULT + ".png", kid).statusCode());
        assertEquals(before, upstreamSaw.size(), "BlueMap is never asked about players the kid can't see");
        get("/maps/family_map/assets/playerheads/" + MUM + ".png", kid);
        assertEquals(before + 1, upstreamSaw.size(), "Mum, in the kid's circle, is passed on");
    }

    @Test
    void handOffTriesAreRateLimited() throws Exception {
        int refused = 0;
        for (int i = 0; i < 25; i++) if (postHandoff("token=" + "B".repeat(43), ORIGIN).statusCode() == 429) refused++;
        assertTrue(refused >= 5, "per-visitor limit of 20 in 10 minutes");
    }

    @Test
    void theHandOffPageMovesTheTokenOutOfTheAddressBarUnderAStrictPolicy() throws Exception {
        var r = get("/handoff", null);
        assertEquals(200, r.statusCode());
        String csp = r.headers().firstValue("content-security-policy").orElseThrow();
        assertTrue(csp.startsWith("default-src 'none'"));
        String body = r.body();
        String js = body.substring(body.indexOf("<script>") + 8, body.indexOf("</script>"));
        assertTrue(csp.contains("'" + Pages.sha256(js) + "'"));
        assertTrue(js.contains("history.replaceState"), "the token leaves the address bar before anything else");
        assertTrue(body.contains("role=\"status\""), "screen readers hear the outcome");
    }

    // --- what a kid sees (D41, D54) ---

    @Test
    void anAwayKidSeesOnlyTheirWorldsMapsAndTheirCircle() throws Exception {
        String kid = signIn(KID);
        var settings = get("/settings.json", kid);
        assertEquals(200, settings.statusCode());
        assertTrue(settings.body().contains("family_map") && settings.body().contains("family_cave"));
        assertFalse(settings.body().contains("adult_map"), settings.body());
        assertTrue(settings.body().contains("\"version\":\"5.0\""), "the rest of BlueMap's settings stay");

        var players = get("/maps/family_map/live/players.json", kid);
        assertEquals(200, players.statusCode());
        assertTrue(players.body().contains(KID.toString()) && players.body().contains(MUM.toString()), players.body());
        assertFalse(players.body().contains(ADULT.toString()), "an adult outside the circle never appears");
        assertFalse(players.body().contains(STRANGER.toString()), "nor a stranger in the same world");
        assertEquals("private, no-store", players.headers().firstValue("cache-control").orElseThrow());
    }

    @Test
    void anAdultWorldsMapIsNotThereForAKidAtAll() throws Exception {
        String kid = signIn(KID);
        int before = upstreamSaw.size();
        var forbidden = List.of("/maps/adult_map/tiles/0/x0/z0.prbm", "/maps/adult_map/live/markers.json",
                "/maps/adult_map/live/players.json", "/maps/adult_map/settings.json");
        String notFound = get("/maps/no_such_map/tiles/0/x0/z0.prbm", kid).body();
        for (String p : forbidden) {
            var r = get(p, kid);
            assertEquals(404, r.statusCode(), p);
            assertEquals(notFound, r.body(), "the same answer as a map that doesn't exist");
            assertFalse(r.body().contains("secret base"));
        }
        assertEquals(before, upstreamSaw.size(), "BlueMap isn't even asked");
        assertEquals(200, get("/maps/family_map/tiles/0/x0/z0.prbm", kid).statusCode());
        assertEquals(200, get("/maps/family_map/live/markers.json", kid).statusCode());
    }

    @Test
    void encodedDottedOrRecasedPathsNeverReachAKidsHiddenMap() throws Exception {
        // K70 security review, 2026-11-24: BlueMap decodes and normalises paths, and the Mac's disk ignores case.
        String kid = signIn(KID);
        int before = upstreamSaw.size();
        for (String p : List.of("/%6Daps/adult_map/settings.json", "/maps/adult_map/live/player%73.json", "/maps/family_map/live/player%73.json",
                "/./maps/adult_map/settings.json", "/maps/./adult_map/settings.json", "/maps/family_map/../adult_map/settings.json",
                "/Maps/adult_map/settings.json", "/MAPS/adult_map/live/players.json", "/maps//adult_map/settings.json",
                "/maps/adult_map%2Fsettings.json", "/maps/family_map/", "/.hidden", "/maps/family_map/live/sse",
                "/maps/adult_map/live/sse", "/maps/family_map/live/", "/maps/family_map/live/other.json", "/random.json", "/x/y")) {
            var r = get(p, kid);
            assertTrue(r.statusCode() == 400 || r.statusCode() == 404, p + " -> " + r.statusCode());
            assertFalse(r.body().contains("secret base") || r.body().contains(ADULT.toString()), p);
        }
        assertEquals(before, upstreamSaw.size(), "BlueMap is never asked for any of them");
    }

    @Test
    void liveMarkersAreNeverPassedOn() throws Exception {
        online.add(ADULT);
        String adult = signIn(ADULT);
        var r = get("/maps/adult_map/live/markers.json", adult);
        assertEquals(200, r.statusCode());
        assertEquals("{}", r.body(), "markers can carry positions Mayor hasn't checked");
    }

    @Test
    void routeAllowsOnlyTheExactList() {
        assertEquals(MapServer.Kind.PAGE, MapServer.route("/").kind());
        assertEquals(MapServer.Kind.PAGE, MapServer.route("/index.html").kind());
        assertEquals(MapServer.Kind.SETTINGS, MapServer.route("/settings.json").kind());
        assertEquals(MapServer.Kind.STATIC, MapServer.route("/assets/index-abc.js").kind());
        assertEquals(MapServer.Kind.STATIC, MapServer.route("/lang/en.conf").kind());
        assertEquals(MapServer.Kind.STATIC, MapServer.route("/favicon.png").kind());
        assertEquals(new MapServer.Route(MapServer.Kind.MAP_FILE, "family_map"), MapServer.route("/maps/family_map/tiles/0/x-1/z2.prbm"));
        assertEquals(new MapServer.Route(MapServer.Kind.MAP_PLAYERS, "m"), MapServer.route("/maps/m/live/players.json"));
        assertEquals(new MapServer.Route(MapServer.Kind.MAP_MARKERS, "m"), MapServer.route("/maps/m/live/markers.json"));
        for (String bad : List.of("", "x", "/%2e", "/a\\b", "//", "/./x", "/../x", "/a/.", "/a/..", "/maps/m/", "/.git/config"))
            assertEquals(MapServer.Kind.BAD, MapServer.route(bad).kind(), bad);
        for (String nf : List.of("/Maps/m/settings.json", "/maps/m", "/maps/m.x/settings.json", "/maps/m/live/sse", "/other.json", "/app.js", "/x/y"))
            assertEquals(MapServer.Kind.NOT_FOUND, MapServer.route(nf).kind(), nf);
    }

    @Test
    void aMalformedPercentInAHandOffIsRefusedNot502() throws Exception {
        assertNull(MapServer.tokenOf("token=%zz"));
        assertEquals(403, postHandoff("token=%zz", ORIGIN).statusCode());
    }

    @Test
    void anAdultSeesBothWorldsAndThePlayersTheyMayReach() throws Exception {
        online.add(ADULT);
        String adult = signIn(ADULT);
        var settings = get("/settings.json", adult).body();
        assertTrue(settings.contains("adult_map") && settings.contains("family_map"));
        var players = get("/maps/adult_map/live/players.json", adult).body();
        assertTrue(players.contains(ADULT.toString()) && players.contains(STRANGER.toString()) && players.contains(MUM.toString()));
        assertFalse(players.contains(KID.toString()), "the kid stays hidden from an adult outside their circle");
        assertEquals(200, get("/maps/adult_map/live/markers.json", adult).statusCode());
    }

    @Test
    void oddPlayerListsShowNobody() throws Exception {
        String kid = signIn(KID);
        for (String bad : List.of("not json", "[]", "{\"players\":{}}", "{\"players\":[{\"uuid\":\"nope\"},{\"name\":\"x\"}]}")) {
            playersJson = bad;
            var r = get("/maps/family_map/live/players.json", kid);
            assertEquals(200, r.statusCode());
            assertFalse(r.body().contains("nope") || r.body().contains("\"x\""), bad + " -> " + r.body());
        }
    }

    // --- sessions end with WelcomeWagon's ---

    @Test
    void signingOutOfWelcomeWagonEndsTheMapWithin30Seconds() throws Exception {
        String kid = signIn(KID);
        liveSessions.remove("s-" + KID);
        assertEquals(200, get("/settings.json", kid).statusCode(), "still within the 30 seconds");
        now.addAndGet(Sessions.RECHECK_MS);
        assertEquals(401, get("/settings.json", kid).statusCode());
        liveSessions.add("s-" + KID);
        assertEquals(401, get("/settings.json", kid).statusCode(), "once ended, it stays ended");
    }

    @Test
    void aMapSessionLastsADayAtMost() throws Exception {
        String kid = signIn(KID);
        now.addAndGet(Sessions.MAX_AGE_MS + 1);
        assertEquals(401, get("/settings.json", kid).statusCode());
    }

    @Test
    void twoMapCookiesCountAsNone() throws Exception {
        String kid = signIn(KID);
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/settings.json"))
                .header("Cookie", MapServer.COOKIE + "=" + kid + "; " + MapServer.COOKIE + "=" + kid).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
    }

    // --- the proxy itself ---

    @Test
    void cookiesAndOtherHeadersNeverReachBlueMapAndItsHeadersDontComeBack() throws Exception {
        String kid = signIn(KID);
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/index.html"))
                .header("Cookie", MapServer.COOKIE + "=" + kid).header("Authorization", "Basic x").header("X-Forwarded-For", "1.2.3.4").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        var saw = upstreamSaw.getLast();
        for (String k : saw.keySet()) assertFalse(Set.of("cookie", "authorization", "x-forwarded-for").contains(k.toLowerCase()), k);
        assertTrue(r.headers().firstValue("x-upstream-secret").isEmpty());
        assertEquals("DENY", r.headers().firstValue("x-frame-options").orElseThrow());
        assertTrue(r.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'"));
    }

    @Test
    void oddPathsAndMethodsAreRefused() throws Exception {
        String kid = signIn(KID);
        for (String p : List.of("/maps/family_map/../adult_map/tiles/0/x0/z0.prbm", "/maps/%2e%2e/adult_map/x", "/maps/family_map%2F..%2Fadult_map/x",
                "//settings.json", "/maps/family_map\\..\\adult_map"))
            assertTrue(Set.of(400, 404).contains(get(p.replace("\\", "%5C"), kid).statusCode()), p);
        var put = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/settings.json"))
                .header("Cookie", MapServer.COOKIE + "=" + kid).PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, put.statusCode());
    }

    @Test
    void aBrokenBlueMapGivesAnEmptyErrorWithNoDetails() throws Exception {
        String kid = signIn(KID);
        upstreamBroken.set(true);
        for (String p : List.of("/settings.json", "/index.html", "/maps/family_map/tiles/0/x0/z0.prbm")) {
            var r = get(p, kid);
            assertEquals(502, r.statusCode(), p);
            assertEquals("", r.body());
        }
        var players = get("/maps/family_map/live/players.json", kid);
        assertEquals(JsonFilter.EMPTY_PLAYERS, players.body(), "no players rather than an error");
        blueMap.stop(0);
        upstreamBroken.set(false);
        var down = get("/settings.json", kid);
        assertEquals(502, down.statusCode());
        assertEquals("", down.body());
    }
}
