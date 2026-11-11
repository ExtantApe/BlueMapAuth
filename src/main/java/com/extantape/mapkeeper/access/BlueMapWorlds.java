package com.extantape.mapkeeper.access;

import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/** Which world each BlueMap map shows. Only loaded when BlueMap is installed. Call on the server thread. */
final class BlueMapWorlds {
    private BlueMapWorlds() {}

    static Map<String, String> read() {
        Map<String, String> out = new HashMap<>();
        var api = BlueMapAPI.getInstance();
        if (api.isEmpty()) return out;
        for (World w : Bukkit.getWorlds()) {
            api.get().getWorld(w).ifPresent(bw -> bw.getMaps().forEach(m -> out.put(m.getId(), w.getName())));
        }
        return out;
    }
}
