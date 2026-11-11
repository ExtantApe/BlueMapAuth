package com.extantape.mapkeeper.access;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything Mapkeeper asks the rest of the server, in one place, so the web side can be tested with made-up answers.
 * Every method must be safe from any thread, and must answer "no" (empty, false, nothing) when it can't be sure.
 */
public interface Access {
    /** Who a browser was handed over for. {@code session} is Latchkey's opaque reference. */
    record Viewer(UUID uuid, String name, String session) {}

    /** Redeems a one-time hand-off token from Latchkey. Empty for anything not fresh, unused and live. */
    Optional<Viewer> redeem(String token);

    /** Whether the Latchkey session behind a viewer is still live. */
    boolean live(String session);

    /** BlueMap map ids to the world each one shows. Maps whose world isn't known are left out. */
    Map<String, String> mapWorlds();

    /** Whether the viewer may enter a world, so may see its maps. */
    boolean mayEnter(UUID viewer, String world);

    /** Of these online players, the ones the viewer may see on the map (D41, D54). */
    Set<UUID> visible(UUID viewer, Set<UUID> players);
}
