package dev.honest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Review mode (-Dhonest.review=true): this client is fed a recording by tools/replay_server.py instead of playing.
 * The world is rebuilt packet by packet exactly as the agent's client received it, and I stand where the agent
 * stood, looking where it looked (exact poses from its flight log; else following its body in the replay) — so
 * /observe answers "what could the agent perceive at this moment" and /debug/raw "what was really there".
 * Nothing acts: no tasks, no reflexes, no pausing, no flight log.
 */
public final class Review {
    private Review() {}
    public static final boolean ON = Boolean.getBoolean("honest.review");
    static final TreeMap<Long, double[]> POSES = new TreeMap<>();
    static boolean timeoutRemoved;

    static void load() {
        String f = System.getProperty("honest.review.flightlog", "");
        if (!ON || f.isEmpty()) return;
        try (var lines = Files.lines(Path.of(f))) {
            lines.forEach(l -> {
                if (!l.contains("\"pose\"")) return;
                JsonObject o = JsonParser.parseString(l).getAsJsonObject();
                if (!o.get("t").getAsString().equals("pose")) return;
                var a = o.getAsJsonArray("p");
                POSES.put(o.get("g").getAsLong(), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble(), a.get(4).getAsDouble()});
            });
            HonestClient.LOG.info("[honest] review: {} poses from {}", POSES.size(), f);
        } catch (Exception e) { HonestClient.LOG.warn("[honest] review: can't read flight log {}: {}", f, e.toString()); }
    }

    /** every client tick, before the eyes sweep: put me exactly where the agent was */
    static void tick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (!ON || pl == null || mc.level == null) return;
        if (!timeoutRemoved) {   // a paused replay sends nothing for a while: don't time out
            try {
                var f = net.minecraft.network.Connection.class.getDeclaredField("channel"); f.setAccessible(true);
                var ch = (io.netty.channel.Channel) f.get(mc.getConnection().getConnection());
                if (ch.pipeline().get("timeout") != null) ch.pipeline().remove("timeout");
                timeoutRemoved = true;
            } catch (Exception e) { timeoutRemoved = true; HonestClient.LOG.warn("[honest] review: couldn't remove the timeout: " + e); }
        }
        var e = POSES.floorEntry(mc.level.getGameTime());
        double x, eyeY, z; float yaw, pitch;
        if (e != null) { double[] p = e.getValue(); x = p[0]; eyeY = p[1]; z = p[2]; yaw = (float) p[3]; pitch = (float) p[4]; }
        else {
            Player body = body(mc);
            if (body == null) return;
            x = body.getX(); eyeY = body.getEyeY(); z = body.getZ(); yaw = body.getYHeadRot(); pitch = body.getXRot();
        }
        pl.setPos(x, eyeY - pl.getEyeHeight(), z);
        pl.setDeltaMovement(0, 0, 0);
        pl.setYRot(yaw); pl.setXRot(pitch); pl.setYHeadRot(yaw); pl.yRotO = yaw; pl.xRotO = pitch;
    }

    /** the agent's own body, as the replay shows it to viewers: not something it can "see" */
    static Player body(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering())
            if (e instanceof Player p && p != mc.player && isMe(mc, p)) return p;
        return null;
    }

    public static boolean isMe(Minecraft mc, Entity e) {
        return ON && e instanceof Player p && p != mc.player && mc.player != null
                && (p.getUUID().equals(mc.player.getUUID()) || p.getGameProfile().name().equals(mc.player.getGameProfile().name()));
    }
}
