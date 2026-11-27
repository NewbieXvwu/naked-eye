package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * What hurt me, from the damage event the server sends with every hit (vanilla uses it for the screen tilt).
 * The kind of damage is something a player always knows — you feel the landing, see the flames, the death screen
 * names it — so it's told straight. Who did it only if they're on my screen right now (the same test as `obs`), and
 * the direction it came from as roughly as the screen tilt shows it: one of eight sides.
 */
public final class Hurt {
    static DamageSource last; static long lastTick = -1000;

    public static void record(DamageSource s) { last = s; lastTick = HonestClient.ticks; }

    static final Map<String, String> SAY = Map.ofEntries(
        Map.entry("fall", "I fell"), Map.entry("lava", "I'm IN LAVA"), Map.entry("in_fire", "standing in fire"),
        Map.entry("on_fire", "I'm on fire"), Map.entry("campfire", "standing on a campfire"), Map.entry("hot_floor", "standing on a magma block"),
        Map.entry("drown", "drowning"), Map.entry("starve", "starving"), Map.entry("in_wall", "suffocating: my head is inside a block"),
        Map.entry("cramming", "squashed in a crowd"), Map.entry("cactus", "touching a cactus"), Map.entry("sweet_berry_bush", "berry-bush thorns"),
        Map.entry("freeze", "freezing in powder snow"), Map.entry("magic", "magic (poison or a potion)"), Map.entry("indirect_magic", "a potion thrown at me"),
        Map.entry("wither", "withering"), Map.entry("lightning_bolt", "struck by lightning"), Map.entry("fly_into_wall", "flew into a wall"),
        Map.entry("falling_block", "something fell on my head"), Map.entry("falling_anvil", "an anvil fell on me"), Map.entry("falling_stalactite", "a dripstone fell on me"),
        Map.entry("stalagmite", "landed on a dripstone spike"), Map.entry("explosion", "an explosion"), Map.entry("player_explosion", "an explosion"),
        Map.entry("bad_respawn_point", "an exploding bed / respawn anchor"), Map.entry("fireworks", "a firework"), Map.entry("thorns", "thorns armor"),
        Map.entry("sting", "a bee stung me"), Map.entry("sonic_boom", "a WARDEN's sonic boom"), Map.entry("dragon_breath", "dragon's breath"),
        Map.entry("out_of_world", "fell out of the world"), Map.entry("outside_border", "outside the world border"));

    /** what did it and from where, for a hurt that just happened (null if no damage event came with it) */
    static String cause(net.minecraft.client.player.LocalPlayer p) {
        if (last == null || HonestClient.ticks - lastTick > 10) return null;
        var mc = Minecraft.getInstance();
        java.util.Set<Integer> onScreen = new java.util.HashSet<>();
        for (var el : Perception.entities(mc, p, mc.level, true)) onScreen.add(el.getAsJsonObject().get("id").getAsInt());
        String type = last.typeHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse(last.getMsgId());
        Entity by = last.getEntity(), direct = last.getDirectEntity();
        Vec3 at = last.getSourcePosition();     // (none for falls, fire, hunger…: those come from nowhere in particular)
        // not on screen: say which kind of unseen, as far as the side the hit came from tells it — off the edge of the
        // screen (turn to look), or from the part I'm looking at but I can't make it out (dark? hidden? invisible?).
        // A shooter is only "not in view": where the arrow came from isn't where the archer stands.
        String who = by == null ? null : onScreen.contains(by.getId()) ? name(by) + " #" + by.getId()
                   : direct != null && direct != by ? "something not in view"
                   : at != null && inView(p, by.getBoundingBox()) ? "something right in front of me that I can't make out (too dark? behind a block? invisible?)"
                   : "something off-screen";
        String what = SAY.get(type);
        if (what == null) {
            if (direct != null && direct != by)                         // arrow, trident, fireball, snowball…
                what = "hit by " + a(type.replace('_', ' '))
                     + (who != null ? " from " + who : "");
            else if (who != null) what = "hit by " + who;                // mob_attack, player_attack, …
            else what = type.replace('_', ' ');
        } else if (who != null && (type.contains("explosion") || type.equals("sting") || type.equals("thorns"))) what += " (" + who + ")";
        if (at != null) {
            Vec3 d = at.subtract(p.getEyePosition());
            double rel = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-d.x, d.z)) - p.getYRot());
            String[] side = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};
            what += ", from the " + Places.compass(d.x, d.z) + " (" + side[Math.floorMod((int) Math.round(rel / 45.0), 8)] + ")";
        }
        return what;
    }

    /** is any of it (feet, middle, head) inside the frame of my screen? */
    static boolean inView(net.minecraft.client.player.LocalPlayer p, net.minecraft.world.phys.AABB bb) {
        Vec3 c = bb.getCenter();
        for (double y : new double[]{bb.minY, c.y, bb.maxY}) if (inView(p, new Vec3(c.x, y, c.z))) return true;
        return false;
    }
    static boolean inView(net.minecraft.client.player.LocalPlayer p, Vec3 at) {
        var mc = Minecraft.getInstance();
        Vec3 d = at.subtract(p.getEyePosition());
        double halfV = mc.options.fov().get() / 2.0;
        double halfH = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(halfV)) * mc.getWindow().getWidth() / Math.max(1.0, mc.getWindow().getHeight())));
        double b = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-d.x, d.z)) - p.getYRot());
        double el = Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))) + p.getXRot();
        return Math.abs(b) <= halfH && Math.abs(el) <= halfV;
    }
    static String name(Entity e) { return a(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ')); }
    static String a(String noun) { return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun; }
}
