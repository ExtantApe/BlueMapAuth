package com.extantape.mapkeeper.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Cuts BlueMap's JSON down to what one viewer may see. Anything that doesn't parse as expected becomes the empty answer,
 * never the original.
 */
final class JsonFilter {
    private JsonFilter() {}

    /** settings.json: only the maps this viewer may see stay in {@code maps}. Null if it isn't BlueMap's settings. */
    static String settings(String json, Predicate<String> mayShow) {
        JsonObject o = object(json);
        if (o == null || !o.has("maps") || !o.get("maps").isJsonArray()) return null;
        JsonArray kept = new JsonArray();
        for (JsonElement e : o.getAsJsonArray("maps")) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && mayShow.test(e.getAsString())) kept.add(e.getAsString());
        }
        o.add("maps", kept);
        return o.toString();
    }

    /** live/players.json: only players the viewer may see. On anything unexpected, no players. */
    static String players(String json, Function<Set<UUID>, Set<UUID>> visible) {
        JsonObject o = object(json);
        if (o == null || !o.has("players") || !o.get("players").isJsonArray()) return EMPTY_PLAYERS;
        Set<UUID> listed = new HashSet<>();
        for (JsonElement e : o.getAsJsonArray("players")) {
            UUID u = uuidOf(e);
            if (u != null) listed.add(u);
        }
        Set<UUID> ok = listed.isEmpty() ? Set.of() : visible.apply(Set.copyOf(listed));
        JsonArray kept = new JsonArray();
        for (JsonElement e : o.getAsJsonArray("players")) {
            UUID u = uuidOf(e);
            if (u != null && ok.contains(u)) kept.add(e);
        }
        o.add("players", kept);
        return o.toString();
    }

    static final String EMPTY_PLAYERS = "{\"players\":[]}";

    private static UUID uuidOf(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonElement u = e.getAsJsonObject().get("uuid");
        if (u == null || !u.isJsonPrimitive() || !u.getAsJsonPrimitive().isString()) return null;
        try {
            return UUID.fromString(u.getAsString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static JsonObject object(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
