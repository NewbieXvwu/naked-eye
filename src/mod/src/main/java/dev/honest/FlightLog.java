package dev.honest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The flight recorder: what the replay file can't hold, because it never left this client.
 *
 * The server records every packet it sends us (ServerReplay), so the world can be rebuilt later and both views —
 * what I could perceive and what was really there — recomputed at any moment. What only exists in here goes to
 * flightlog/SESSION.jsonl, one JSON object per line, stamped with the level's game time "g" (the key that lines
 * it up with the replay) and wall time "w":
 *   start   session header; SESSION.memory.bin is a copy of my memory as it was then (replays must start from it)
 *   pose    my exact eye position and view angles, on every tick they change (the replay only has 1.4° angles)
 *   observe exactly what the brain was shown when it asked
 *   act     every request the brain sent and what it got back
 *   event   everything heard, read, paused, reflexes, deaths — and the brain's own commentary ("note")
 */
public final class FlightLog {
    private FlightLog() {}
    static final Path DIR = Path.of("flightlog");
    static final Gson GSON = new Gson();
    static Writer out;
    static Object connection;
    static double[] lastPose;
    static int unflushed;

    /** called every client tick */
    static synchronized void tick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (pl == null || mc.getConnection() == null) { if (connection != null) close(); return; }
        if (connection != mc.getConnection()) start(mc);
        double[] p = {r(pl.getX(), 1000), r(pl.getEyeY(), 1000), r(pl.getZ(), 1000), r(pl.getYRot(), 100), r(pl.getXRot(), 100)};
        if (lastPose == null || !java.util.Arrays.equals(p, lastPose)) {
            lastPose = p;
            JsonObject o = new JsonObject();
            com.google.gson.JsonArray a = new com.google.gson.JsonArray(); for (double v : p) a.add(v);
            o.add("p", a);
            write("pose", o);
        }
    }

    static void start(Minecraft mc) {
        close();
        connection = mc.getConnection();
        try {
            Files.createDirectories(DIR);
            String session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss"));
            // my memory as it is right now: the replay of this session has to start from the same knowledge
            Path mem = Path.of("honest_memory.bin");
            Perception.MAP.save(mem);
            if (Files.exists(mem)) Files.copy(mem, DIR.resolve(session + ".memory.bin"), StandardCopyOption.REPLACE_EXISTING);
            out = Files.newBufferedWriter(DIR.resolve(session + ".jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            JsonObject o = new JsonObject();
            o.addProperty("session", session);
            o.addProperty("player", mc.player.getGameProfile().name());
            o.addProperty("format", 1);
            write("start", o);
        } catch (Exception e) { HonestClient.LOG.warn("[honest] flight log off: " + e); out = null; }
    }

    static synchronized void close() {
        try { if (out != null) { out.flush(); out.close(); } } catch (Exception ignored) {}
        out = null; connection = null; lastPose = null;
    }

    public static synchronized void write(String type, JsonObject body) {
        if (out == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("t", type);
            var lvl = Minecraft.getInstance().level;
            o.addProperty("g", lvl == null ? -1 : lvl.getGameTime());
            o.addProperty("w", System.currentTimeMillis());
            for (var e : body.entrySet()) if (!e.getKey().equals("t")) o.add(e.getKey(), e.getValue());
            out.write(GSON.toJson(o)); out.write('\n');
            // poses come every tick: batch those; anything the brain did or saw is flushed at once
            if (!type.equals("pose") || ++unflushed >= 100) { out.flush(); unflushed = 0; }
        } catch (Exception ignored) {}
    }

    static double r(double v, double k) { return Math.round(v * k) / k; }
}
