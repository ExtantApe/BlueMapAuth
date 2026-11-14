package com.extantape.bluemapauth;

import com.extantape.bluemapauth.web.MapServer;
import org.bukkit.configuration.ConfigurationSection;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads config.yml and refuses anything unsafe. The map server starts only with no problems at all: a wrong setting
 * here could put the map in front of strangers, so there's no "mostly right".
 */
public final class Setup {
    public record Result(boolean enabled, MapServer.Settings settings, String worldPermission, List<String> problems) {
        public boolean ok() { return enabled && problems.isEmpty(); }
    }

    private static final Pattern HTTPS_HOST = Pattern.compile("https://[a-z0-9.-]{1,253}(:[0-9]{1,5})?/?");
    private static final Pattern LOCAL_HTTP = Pattern.compile("http://(127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/?");

    private Setup() {}

    public static Result read(ConfigurationSection c) {
        List<String> problems = new ArrayList<>();
        boolean enabled = c.getBoolean("enabled", false);

        String bind = c.getString("bind", "127.0.0.1").strip();
        if (!bind.equals("127.0.0.1") && !bind.equals("::1")) problems.add("bind is \"" + bind + "\". BlueMapAuth listens on this machine only: use 127.0.0.1 and put your HTTPS proxy in front.");

        int port = c.getInt("port", 8180);
        if (port < 1 || port > 65535) problems.add("port " + port + " isn't a port number (1 to 65535).");

        String pub = c.getString("public-url", "").strip();
        if (!HTTPS_HOST.matcher(pub).matches()) problems.add("public-url must be the map's https:// address with only a host name, such as https://map.example.org.");

        String blue = c.getString("bluemap-url", "http://127.0.0.1:8100").strip();
        if (!LOCAL_HTTP.matcher(blue).matches()) problems.add("bluemap-url must be BlueMap's web server on this machine, such as http://127.0.0.1:8100.");

        String signIn = c.getString("sign-in-url", "").strip();
        if (!signIn.isEmpty() && !HTTPS_HOST.matcher(signIn).matches()) {
            problems.add("sign-in-url must be an https:// address with only a host name, or empty. It's left out.");
            signIn = "";
        }

        List<String> proxies = new ArrayList<>();
        for (String p : c.getStringList("trusted-proxies")) {
            String s = p.strip();
            if (!(s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.contains(":"))) { problems.add("trusted-proxies: \"" + s + "\" isn't an exact IP address."); continue; }
            try {
                InetAddress.ofLiteral(s);
                proxies.add(s);
            } catch (IllegalArgumentException e) {
                problems.add("trusted-proxies: \"" + s + "\" isn't an exact IP address.");
            }
        }

        String worldPermission = c.getString("world-permission", "multiverse.access.{world}").strip();
        if (!worldPermission.matches("[a-z0-9_.-]*\\{world}[a-z0-9_.-]*")) problems.add("world-permission must be a permission with {world} in it, such as multiverse.access.{world}.");

        int perIp = c.getInt("rate-limits.per-ip-per-10-minutes", 10), site = c.getInt("rate-limits.site-wide-per-minute", 30);
        if (perIp < 1 || perIp > 20) problems.add("rate-limits.per-ip-per-10-minutes must be 1 to 20.");
        if (site < 1 || site > 60) problems.add("rate-limits.site-wide-per-minute must be 1 to 60.");

        URI pubUri = HTTPS_HOST.matcher(pub).matches() ? URI.create(strip(pub)) : URI.create("https://invalid.example");
        URI blueUri = LOCAL_HTTP.matcher(blue).matches() ? URI.create(strip(blue)) : URI.create("http://127.0.0.1:1");
        return new Result(enabled, new MapServer.Settings(bind, port, pubUri, blueUri, strip(signIn), List.copyOf(proxies), perIp, site),
                worldPermission, List.copyOf(problems));
    }

    private static String strip(String url) { return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }
}
