package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * EXPERIMENTER instrument (debug port only, reads the real world): follow every arrow from release to its end
 * and say what happened to it — hit me, stopped by one of my cover blocks, or landed somewhere else — together
 * with what I was doing at that moment. So we can see WHY arrows still get through, not just the HP left over.
 */
public final class ArrowLog {
    static final class Rec {
        int id, owner = -1, firstTick, lastTick, still = 0; Vec3 first, last, vel = Vec3.ZERO; double closest = 99;
        String outcome; JsonObject ctx;
    }
    static final Map<Integer, Rec> live = new HashMap<>();
    static final List<Rec> done = new ArrayList<>();
    static int lastArrowCount = 0;

    public static void tick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (pl == null || mc.level == null) return;
        int t = HonestClient.ticks;
        Set<Integer> seen = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractArrow a) || e.distanceTo(pl) > 64) continue;
            Rec r = live.get(e.getId());
            if (r == null) {
                if (done.stream().anyMatch(d -> d.id == e.getId())) continue;   // already ended (stuck arrows linger)
                r = new Rec(); r.id = e.getId(); r.firstTick = t; r.first = e.position();
                Entity o = a.getOwner(); r.owner = o == null ? -1 : o.getId();
                live.put(r.id, r);
            }
            seen.add(r.id);
            Vec3 p = e.position();
            if (r.last != null && p.distanceToSqr(r.last) < 1e-4) r.still++; else r.still = 0;
            if (r.last != null) r.vel = p.subtract(r.last);
            r.last = p; r.lastTick = t;
            r.closest = Math.min(r.closest, p.distanceTo(pl.getBoundingBox().getCenter()));
            if (r.still >= 2) finish(mc, r, stuckOutcome(r));
        }
        int arrows = pl.getArrowCount();
        boolean newArrowInMe = arrows > lastArrowCount; lastArrowCount = arrows;
        for (Rec r : new ArrayList<>(live.values())) {
            if (seen.contains(r.id)) continue;
            // vanished mid-flight: if it vanished right next to me it hit me
            boolean nearMe = r.last != null && r.last.add(r.vel).distanceTo(pl.getBoundingBox().getCenter()) < 2.2;
            finish(mc, r, nearMe && (newArrowInMe || pl.hurtTime > 0) ? "hit_me" : nearMe && pl.isBlocking() ? "blocked_by_shield" : nearMe ? "hit_me?" : "vanished_elsewhere");
        }
    }

    static String stuckOutcome(Rec r) {
        if (r.closest < 1.6 && Minecraft.getInstance().player.isBlocking()) return "blocked_by_shield";
        BlockPos hitBlock = BlockPos.containing(r.last.add(r.vel.normalize().scale(0.3)));
        for (BlockPos c : Fight.COVER) if (c.distManhattan(hitBlock) == 0) return "blocked_by_cover";
        return r.closest < 1.5 ? "near_miss_landed" : "landed_elsewhere";
    }

    static void finish(Minecraft mc, Rec r, String outcome) {
        live.remove(r.id);
        r.outcome = outcome;
        LocalPlayer pl = mc.player;
        JsonObject c = new JsonObject();
        c.addProperty("flight_ticks", r.lastTick - r.firstTick);
        c.addProperty("shooter", r.owner);
        c.addProperty("shot_from_m", Math.round(r.first.distanceTo(pl.position()) * 10) / 10.0);
        c.addProperty("me_airborne", !pl.onGround());
        c.addProperty("me_blocking", pl.isBlocking());
        c.addProperty("me_vy", Math.round(pl.getDeltaMovement().y * 100) / 100.0);
        c.addProperty("placing_cover_now", Fight.placingNow);
        c.addProperty("ticks_since_last_cover", Fight.lastCoverTick < 0 ? -1 : HonestClient.ticks - Fight.lastCoverTick);
        // was any cover block on the straight line from where it was shot to where it ended?
        boolean coverOnLine = false; double nearestCover = 99;
        for (BlockPos b : Fight.COVER) {
            nearestCover = Math.min(nearestCover, Vec3.atCenterOf(b).distanceTo(pl.position()));
            if (new net.minecraft.world.phys.AABB(b).clip(r.first, r.last.add(r.vel)).isPresent()) coverOnLine = true;
        }
        c.addProperty("cover_on_line_of_fire", coverOnLine);
        c.addProperty("nearest_cover_m", nearestCover > 98 ? -1 : Math.round(nearestCover * 10) / 10.0);
        // which way did it come from vs. which way I was facing
        double in = Math.toDegrees(Math.atan2(-(r.first.x - pl.getX()), r.first.z - pl.getZ()));
        c.addProperty("came_from_deg_rel_to_facing", Math.round(net.minecraft.util.Mth.wrapDegrees(in - pl.getYRot())));
        c.addProperty("arrived_height_above_feet", Math.round((r.last.y - pl.getY()) * 10) / 10.0);
        r.ctx = c;
        done.add(r);
        if (done.size() > 500) done.remove(0);
    }

    public static JsonObject report(boolean reset) {
        JsonObject o = new JsonObject();
        Map<String, Integer> counts = new TreeMap<>();
        JsonArray hits = new JsonArray();
        for (Rec r : done) {
            counts.merge(r.outcome, 1, Integer::sum);
            if (r.outcome.startsWith("hit_me")) hits.add(r.ctx);
        }
        JsonObject cs = new JsonObject(); counts.forEach(cs::addProperty);
        o.add("outcomes", cs); o.add("hits_context", hits);
        if (reset) { done.clear(); live.clear(); Fight.COVER.clear(); }
        return o;
    }
}
