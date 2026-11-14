package com.extantape.bluemapauth;

import com.extantape.bluemapauth.access.LiveAccess;
import com.extantape.bluemapauth.web.MapServer;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.List;

/**
 * BlueMapAuth: BlueMap's web map behind a sign-in (docs/design.md). Off until {@code enabled: true} and every setting is
 * safe. {@code /bluemapauth status} says what's on and why not; {@code /bluemapauth reload} re-reads the config.
 */
public final class BlueMapAuthPlugin extends JavaPlugin {
    public static final String ADMIN = "bluemapauth.admin";

    private MapServer server;
    private LiveAccess access;
    private BukkitTask refresh;
    private volatile List<String> problems = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> e.registrar().register("bluemapauth",
                "BlueMapAuth's status, and reloading its settings.", List.of(), new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack source, String[] args) {
                        command(source.getSender(), args);
                    }

                    @Override
                    public String permission() { return ADMIN; }
                }));
    }

    @Override
    public void onDisable() { stop(); }

    private void start() {
        reloadConfig();
        Setup.Result r = Setup.read(getConfig());
        problems = r.problems();
        if (!r.enabled()) { getLogger().info("BlueMapAuth is off (enabled: false), so the map isn't served."); return; }
        if (!r.ok()) {
            for (String p : r.problems()) getLogger().severe(p);
            getLogger().severe("BlueMapAuth didn't start, so the map isn't served. Fix the lines above, then /bluemapauth reload.");
            return;
        }
        access = new LiveAccess(r.worldPermission(), getLogger(), System::currentTimeMillis);
        access.refreshMaps();
        refresh = getServer().getScheduler().runTaskTimer(this, access::refreshMaps, 20L * 30, 20L * 30);
        server = new MapServer(r.settings(), access, getLogger(), System::currentTimeMillis);
        try {
            server.start();
            getLogger().info("Serving the map on " + r.settings().bind() + ":" + server.port() + " for " + r.settings().publicUrl()
                    + ", " + access.mapWorlds().size() + " map(s) known.");
        } catch (IOException e) {
            getLogger().severe("Couldn't listen on " + r.settings().bind() + ":" + r.settings().port() + " (" + e.getMessage() + "). Pick a free port.");
            stop();
        }
    }

    private void stop() {
        if (refresh != null) refresh.cancel();
        if (server != null) server.stop();
        refresh = null;
        server = null;
        access = null;
    }

    private void command(CommandSender to, String[] args) {
        String sub = args.length == 0 ? "status" : args[0];
        switch (sub) {
            case "reload" -> {
                stop();
                start();
                to.sendMessage(Component.text(server != null ? "BlueMapAuth reloaded and serving the map." : "BlueMapAuth reloaded. The map isn't served: see /bluemapauth status.",
                        server != null ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "status" -> {
                to.sendMessage(Component.text(server != null ? "Serving the map on port " + server.port() + ", " + access.mapWorlds().size() + " map(s) known."
                        : "Not serving the map.", server != null ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                for (String p : problems) to.sendMessage(Component.text("- " + p, NamedTextColor.RED));
                for (String name : List.of("Latchkey", "BlueMap", "LuckPerms", "HouseRules")) {
                    boolean on = getServer().getPluginManager().isPluginEnabled(name);
                    to.sendMessage(Component.text(name + ": " + (on ? "found" : "missing, so " + missing(name)), on ? NamedTextColor.WHITE : NamedTextColor.RED));
                }
            }
            default -> to.sendMessage(Component.text("Use /bluemapauth status or /bluemapauth reload.", NamedTextColor.RED));
        }
    }

    private static String missing(String plugin) {
        return switch (plugin) {
            case "Latchkey" -> "nobody can sign in to the map";
            case "BlueMap" -> "there are no maps to show";
            case "LuckPerms" -> "someone who isn't in game sees no maps and no other players";
            default -> "the map shows nobody but the viewer themselves";
        };
    }
}
