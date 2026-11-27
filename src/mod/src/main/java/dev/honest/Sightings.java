package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Object permanence. When something you were watching vanishes, you still know roughly what happened:
 * it slid off the LEFT edge of the screen (or you turned away), or it ducked behind something mid-screen,
 * which way it was moving, and so roughly where it probably is now. Built only from earlier sightings.
 */
public final class Sightings {
    static final class Seen { String type; int tick; double bearing, elev, dist; Vec3 pos, vel = Vec3.ZERO; boolean lost; String how; int lostTick; }
    static final Map<Integer, Seen> SEEN = new HashMap<>();

    static void update(Minecraft mc, LocalPlayer pl, JsonArray visible) {
        int now = (int) mc.level.getGameTime();   // game time: stands still while paused
        Set<Integer> ids = new HashSet<>();
        double halfH = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(mc.options.fov().get()) / 2)
                * mc.getWindow().getWidth() / Math.max(1.0, mc.getWindow().getHeight())));
        double halfV = mc.options.fov().get() / 2.0;
        for (JsonElement el : visible) {
            JsonObject j = el.getAsJsonObject();
            int id = j.get("id").getAsInt();
            String type = j.get("type").getAsString();
            if (type.equals("item") || type.equals("experience_orb") || type.endsWith("arrow")) continue;
            ids.add(id);
            JsonArray ap = j.getAsJsonArray("approx_pos");
            Vec3 pos = new Vec3(ap.get(0).getAsDouble(), ap.get(1).getAsDouble(), ap.get(2).getAsDouble());
            Seen s = SEEN.computeIfAbsent(id, k -> new Seen());
            if (s.pos != null && !s.lost && now > s.tick) {
                Vec3 v = pos.subtract(s.pos).scale(1.0 / (now - s.tick));
                // a knockback jolt isn't where it's heading; mobs walk at most ~0.3 m/tick — cap the estimate there
                if (v.horizontalDistance() > 0.3) v = v.scale(0.3 / v.horizontalDistance());
                s.vel = s.vel.scale(0.5).add(v.multiply(0.5, 0, 0.5));
            }
            s.type = type; s.tick = now; s.pos = pos; s.lost = false;
            s.bearing = j.get("bearing").getAsDouble(); s.elev = j.get("elevation").getAsDouble(); s.dist = j.get("dist").getAsDouble();
        }
        for (var en : SEEN.entrySet()) {
            Seen s = en.getValue();
            if (ids.contains(en.getKey()) || s.lost) continue;
            s.lost = true; s.lostTick = now;
            // where is its last known spot on my CURRENT screen? (I may have turned since — the screen swept past it)
            Vec3 p = s.pos.add(s.vel.scale(now - s.tick)).add(0, 0.9, 0).subtract(pl.getEyePosition());
            double b = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-p.x, p.z)) - pl.getYRot());
            double el = Math.toDegrees(Math.atan2(p.y, Math.hypot(p.x, p.z))) + pl.getXRot();
            if (b < -halfH) s.how = "went off the LEFT side of my view";
            else if (b > halfH) s.how = "went off the RIGHT side of my view";
            else if (el > halfV) s.how = "went off the TOP of my view";
            else if (el < -halfV) s.how = "went off the BOTTOM of my view";
            else if (mc.level.getEntity(en.getKey()) == null) s.how = "gone while in view (died / despawned)";
            else s.how = "vanished while in view (went behind something, or it got too dark)";
        }
        SEEN.values().removeIf(s -> s.lost && now - s.lostTick > 200);   // ~10 s of memory
    }

    static JsonArray lost(Minecraft mc, LocalPlayer pl) {
        JsonArray arr = new JsonArray();
        int now = (int) mc.level.getGameTime();   // game time: stands still while paused
        for (var en : SEEN.entrySet()) {
            Seen s = en.getValue();
            if (!s.lost) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", en.getKey()); j.addProperty("type", s.type);
            j.addProperty("lost_s_ago", Math.round((now - s.lostTick) / 2.0) / 10.0);
            j.addProperty("how", s.how);
            j.addProperty("last_dist", s.dist);
            double sp = Math.hypot(s.vel.x, s.vel.z);
            if (sp > 0.02) {
                // its motion as I saw it, in my body frame at the time it vanished
                double rel = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-s.vel.x, s.vel.z)) - pl.getYRot());
                j.addProperty("was_moving", Places.compass(s.vel.x, s.vel.z) + String.format(" at ~%.1f m/s", sp * 20));
            }
            // a guess, clearly labelled: where it would be now if it kept going (capped at 1.5 s of travel)
            double dt = Math.min(30, now - s.tick);
            Vec3 guess = s.pos.add(s.vel.scale(dt));
            j.addProperty("probably_now", "~" + Places.rel(pl, BlockPos.containing(guess)) + " (guess)");
            arr.add(j);
        }
        return arr;
    }
}
