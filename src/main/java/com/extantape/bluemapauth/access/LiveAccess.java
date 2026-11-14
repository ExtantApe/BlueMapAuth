package com.extantape.bluemapauth.access;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The real answers, from the plugins on the server. WelcomeWagon and Mayor are called by method name with plain Java
 * and Bukkit types, so BlueMapAuth builds without them (D40). BlueMap and LuckPerms are used only when installed. Each
 * missing piece answers "no" (docs/design.md):
 * <ul>
 *   <li>no WelcomeWagon: nobody can sign in, and every map session ends at its next check;</li>
 *   <li>no BlueMap: no maps;</li>
 *   <li>a viewer who isn't in game, and no LuckPerms: no maps, and only themselves;</li>
 *   <li>no Mayor: nobody but the viewer themselves on the map.</li>
 * </ul>
 */
public final class LiveAccess implements Access {
    static final long PERMISSIONS_FOR_MS = 30_000;

    private final String worldPermission;
    private final Logger log;
    private final LongSupplier clock;
    private volatile Map<String, String> mapWorlds = Map.of();
    private record Cached(Predicate<String> has, long at) {}
    private final Map<UUID, Cached> offline = new ConcurrentHashMap<>();

    public LiveAccess(String worldPermission, Logger log, LongSupplier clockMillis) {
        this.worldPermission = worldPermission;
        this.log = log;
        this.clock = clockMillis;
    }

    /** Re-reads which world each map shows. Call on the server thread, now and then. */
    public void refreshMaps() {
        if (Bukkit.getPluginManager().getPlugin("BlueMap") == null) { mapWorlds = Map.of(); return; }
        try {
            mapWorlds = Map.copyOf(BlueMapWorlds.read());
        } catch (Exception | LinkageError e) {
            log.warning("Couldn't read BlueMap's maps (" + e.getClass().getSimpleName() + "), so the map shows none until it can.");
            mapWorlds = Map.of();
        }
    }

    @Override
    public Optional<Viewer> redeem(String token) {
        Object r = call("WelcomeWagon", "redeemMapHandoff", new Class<?>[]{String.class}, token);
        if (!(r instanceof Map<?, ?> m)) return Optional.empty();
        Object u = m.get("uuid"), n = m.get("name"), s = m.get("session");
        if (!(u instanceof String us) || !(n instanceof String ns) || !(s instanceof String ss)) return Optional.empty();
        try {
            return Optional.of(new Viewer(UUID.fromString(us), ns, ss));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean live(String session) {
        return Boolean.TRUE.equals(call("WelcomeWagon", "handoffSessionLive", new Class<?>[]{String.class}, session));
    }

    @Override
    public Map<String, String> mapWorlds() { return mapWorlds; }

    @Override
    public boolean mayEnter(UUID viewer, String world) {
        String node = worldPermission.replace("{world}", world);
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) return p.hasPermission(node);
        Predicate<String> has = offlinePermissions(viewer);
        return has != null && has.test(node);
    }

    @Override
    public Set<UUID> visible(UUID viewer, Set<UUID> players) {
        Set<UUID> out = new HashSet<>();
        if (players.contains(viewer)) out.add(viewer);
        if (Bukkit.getPluginManager().getPlugin("Mayor") == null) return out;
        Player me = Bukkit.getPlayer(viewer);
        Predicate<String> mine = me == null ? offlinePermissions(viewer) : null;
        if (me == null && mine == null) return out;
        for (UUID id : players) {
            if (id.equals(viewer)) continue;
            Player them = Bukkit.getPlayer(id);
            if (them == null) continue;
            Object ok = me != null
                    ? call("Mayor", "mayReach", new Class<?>[]{Player.class, Player.class}, me, them)
                    : call("Mayor", "sharesCircle", new Class<?>[]{Predicate.class, Player.class}, mine, them);
            if (Boolean.TRUE.equals(ok)) out.add(id);
        }
        return out;
    }

    private Predicate<String> offlinePermissions(UUID viewer) {
        long now = clock.getAsLong();
        Cached c = offline.get(viewer);
        if (c != null && now - c.at() < PERMISSIONS_FOR_MS && now >= c.at()) return c.has();
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        Predicate<String> has = OfflinePermissions.of(viewer);
        if (has == null) { offline.remove(viewer); return null; }
        if (offline.size() > 5_000) offline.clear();
        offline.put(viewer, new Cached(has, now));
        return has;
    }

    /** A public method on another plugin by name, or null if the plugin, the method or the call fails. */
    private Object call(String plugin, String method, Class<?>[] types, Object... args) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return null;
        try {
            Method m = p.getClass().getMethod(method, types);
            return m.invoke(p, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
