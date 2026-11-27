package dev.honest.pause;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Skins for the agents (they're offline players: Steve and Alex otherwise), for anyone watching and for the videos.
 * config/naked-eye/skins.txt, one line per agent:
 *
 *   Agent: Notch                       wear the skin of that Minecraft account
 *   Bob: <value> <signature>           a skin of your own: upload the PNG at mineskin.org, paste its value + signature
 *
 * Clients show only skins Mojang has signed (and hosted), so a skin is always one Mojang knows: an account's, or one
 * MineSkin uploaded. An account's skin is looked up once (needs the internet) and kept in skins.cache.json, so a copy
 * of the folder without internet still has it. Takes effect when the agent (re)joins.
 */
public final class Skins {
    static final Path FILE = Path.of("config", "naked-eye", "skins.txt"), CACHE = Path.of("config", "naked-eye", "skins.cache.json");
    static final Map<String, String[]> BY_NAME = new HashMap<>();   // player -> {value, signature}
    static long stamp = -2;

    /** at login, as the server makes the offline player's profile: put the skin on it */
    public static GameProfile dress(GameProfile p) {
        load();
        String[] t = BY_NAME.get(p.name());
        if (t == null) return p;
        var m = com.google.common.collect.LinkedHashMultimap.<String, Property>create();
        m.putAll(p.properties());
        m.removeAll("textures");
        m.put("textures", new Property("textures", t[0], t[1]));
        return new GameProfile(p.id(), p.name(), new PropertyMap(m));
    }

    static synchronized void load() {
        try {
            long s = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : -1;
            if (s == stamp) return;
            stamp = s; BY_NAME.clear();
            if (s < 0) return;
            JsonObject cache = Files.exists(CACHE) ? JsonParser.parseString(Files.readString(CACHE)).getAsJsonObject() : new JsonObject();
            boolean cacheChanged = false;
            for (String l : Files.readAllLines(FILE)) {
                l = l.strip(); if (l.isEmpty() || l.startsWith("#") || !l.contains(":")) continue;
                String who = l.substring(0, l.indexOf(':')).strip();
                String[] w = l.substring(l.indexOf(':') + 1).strip().split("\\s+");
                if (w.length >= 2) { BY_NAME.put(who, new String[]{w[0], w[1]}); continue; }   // value + signature
                String account = w[0];
                if (cache.has(account)) {
                    var c = cache.getAsJsonObject(account);
                    BY_NAME.put(who, new String[]{c.get("value").getAsString(), c.get("signature").getAsString()});
                    continue;
                }
                String[] t = fetch(account);
                if (t == null) { log("couldn't get the skin of account " + account + " for " + who + " (no internet? a typo?)"); continue; }
                BY_NAME.put(who, t);
                JsonObject c = new JsonObject(); c.addProperty("value", t[0]); c.addProperty("signature", t[1]);
                cache.add(account, c); cacheChanged = true;
                log(who + " wears the skin of " + account);
            }
            if (cacheChanged) Files.writeString(CACHE, cache.toString());
        } catch (Exception e) { log("couldn't read skins.txt: " + e); }
    }

    /** an account's current skin, signed by Mojang: name -> uuid -> profile with textures */
    static String[] fetch(String account) {
        try {
            HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(8)).build();
            var r1 = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + account)).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
            if (r1.statusCode() != 200) return null;
            String id = JsonParser.parseString(r1.body()).getAsJsonObject().get("id").getAsString();
            var r2 = http.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false")).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
            if (r2.statusCode() != 200) return null;
            for (var e : JsonParser.parseString(r2.body()).getAsJsonObject().getAsJsonArray("properties")) {
                var o = e.getAsJsonObject();
                if (o.get("name").getAsString().equals("textures") && o.has("signature")) return new String[]{o.get("value").getAsString(), o.get("signature").getAsString()};
            }
        } catch (Exception ignored) {}
        return null;
    }

    static void log(String s) { org.slf4j.LoggerFactory.getLogger("naked-eye").info("[skins] {}", s); }
}
