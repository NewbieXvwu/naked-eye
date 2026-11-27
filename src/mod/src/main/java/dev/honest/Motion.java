package dev.honest;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * How fast things move, as the eyes judge it: from where each thing was over the last few ticks of WORLD time.
 *
 * Only ticks in which the world ran count, so while it stands still (me or another agent thinking) a falling body
 * still reads as falling at the speed it had — not as 0, which "position now minus position a moment ago" would
 * give, and not the client's velocity field either (for other entities that's only whatever the server last sent).
 * Speeds are in metres per second of game time (as the world sees it at any fast-forward).
 */
public final class Motion {
    static final int KEEP = 6;
    static final Map<Integer, ArrayDeque<Vec3>> TRACK = new HashMap<>();

    /** every client tick in which the world ran */
    static void tick(Minecraft mc) {
        var pl = mc.player; if (pl == null || mc.level == null) return;
        java.util.Set<Integer> live = new java.util.HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(pl) > 96 * 96) continue;
            live.add(e.getId());
            var q = TRACK.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
            Vec3 p = e.position();
            if (!q.isEmpty() && q.peekLast().distanceTo(p) > 12) q.clear();   // (a teleport, a respawn: not motion)
            q.addLast(p);
            while (q.size() > KEEP) q.removeFirst();
        }
        TRACK.keySet().retainAll(live);
    }

    /** blocks per tick, averaged over the ticks I have (null if I haven't watched it move yet) */
    static Vec3 velocity(Entity e) {
        var q = TRACK.get(e.getId());
        if (q == null || q.size() < 2) return null;
        return q.peekLast().subtract(q.peekFirst()).scale(1.0 / (q.size() - 1));
    }

    /** my own motion, for the observation: only when I'm moving */
    static JsonObject self(net.minecraft.client.player.LocalPlayer pl) {
        Vec3 v = velocity(pl);
        if (v == null) return null;
        double h = Math.hypot(v.x, v.z) * 20, vy = v.y * 20;
        if (h < 0.3 && Math.abs(vy) < 0.5) return null;
        JsonObject j = new JsonObject();
        if (h >= 0.3) { j.addProperty("speed_mps", r1(h)); j.addProperty("heading", Places.compass(v.x, v.z)); }
        if (Math.abs(vy) >= 0.5) j.addProperty("vertical_mps", Math.round(vy * 10) / 10.0);   // − = going down
        return j;
    }

    /** another thing's motion as I see it: speed, where it's heading, and whether it's coming at me */
    static JsonObject seen(Entity e, Vec3 eye, double dist) {
        Vec3 v = velocity(e);
        if (v == null) return null;
        double h = Math.hypot(v.x, v.z) * 20, vy = v.y * 20;
        if (h < 0.3 && Math.abs(vy) < 0.5) return null;
        // judged by eye: finer up close, rougher far off
        double step = dist < 16 ? 0.1 : dist < 48 ? 0.5 : 1.0;
        JsonObject j = new JsonObject();
        if (h >= 0.3) {
            j.addProperty("speed_mps", r1(Math.max(step, Math.round(h / step) * step)));
            j.addProperty("heading", Places.compass(v.x, v.z));
            Vec3 to = eye.subtract(e.position()); to = new Vec3(to.x, 0, to.z);
            if (to.lengthSqr() > 1e-6) {
                double closing = new Vec3(v.x, 0, v.z).dot(to.normalize()) * 20;   // m/s toward me
                if (closing > 0.6 * h) j.addProperty("relative", "coming at me");
                else if (closing < -0.6 * h) j.addProperty("relative", "going away from me");
                else j.addProperty("relative", "moving across my view");
            }
        }
        if (Math.abs(vy) >= 0.5) j.addProperty("vertical_mps", r1(Math.round(vy / step) * step));
        return j;
    }
    static double r1(double x) { return Math.round(x * 10) / 10.0; }
}
