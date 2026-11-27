package dev.honest;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Falling, as a player feels it: the world rushing up, how long I've been dropping, and — if I've seen it — the ground
 * coming. (The world can stand still mid-fall while I think: HP is still full then, and the landing is what kills.
 * That happened: dug out the block under its feet, paused in the air, "HP fine", resumed, dead.)
 *
 * The ground below is what my memory holds (what the eyes saw), never a peek at the world.
 */
public final class Fall {
    /** dropping through the air, not swimming, climbing, gliding or floating down */
    public static boolean falling(LocalPlayer pl) {
        var v = Motion.velocity(pl);   // (seen over the last ticks the world ran: still "falling" while it's frozen mid-air)
        double vy = v != null ? v.y : pl.getDeltaMovement().y;
        return !pl.onGround() && vy < -0.2 && !pl.isInWater() && !pl.isInLava() && !pl.onClimbable()
                && !pl.isFallFlying() && !pl.getAbilities().flying && !pl.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
    }

    /** {metres down to the first thing I've seen under me, that thing}, or null if I haven't seen what's down there */
    static Object[] ground(LocalPlayer pl) {
        BlockPos feet = pl.blockPosition();
        for (int dy = 0; dy < 96; dy++) {
            BlockPos p = feet.below(dy);
            BlockState s = Perception.MAP.get(p.getX(), p.getY(), p.getZ());
            if (s == null) return null;                           // a stretch I never saw: no telling what's below
            if (Vis.isAirish(s) && s.getFluidState().isEmpty()) continue;
            double top = p.getY() + (s.getFluidState().isEmpty() ? Math.max(0, s.getCollisionShape(pl.level(), p).max(net.minecraft.core.Direction.Axis.Y)) : 0.9);
            return new Object[]{Math.max(0, pl.getY() - top), s};
        }
        return null;
    }

    /** about how much the landing will take off, if it's onto that (vanilla: (height - safe distance) * multiplier) */
    static int landingDamage(LocalPlayer pl, double moreMetres, BlockState onto) {
        String n = Perception.itemName(new net.minecraft.world.item.ItemStack(onto.getBlock()));
        if (!onto.getFluidState().isEmpty() || n.equals("slime_block") || n.equals("powder_snow") || n.equals("cobweb") || n.contains("scaffolding")) return 0;
        double h = pl.fallDistance + moreMetres - pl.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        double d = Math.max(0, Math.ceil(h * pl.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)));
        if (n.equals("hay_block") || n.equals("honey_block")) d = Math.ceil(d * 0.2);
        else if (n.endsWith("_bed")) d = Math.ceil(d * 0.5);
        return (int) d;
    }

    /** the "falling" line of the observation (null when on my feet) */
    public static JsonObject describe(LocalPlayer pl) {
        if (!falling(pl)) return null;
        JsonObject f = new JsonObject();
        f.addProperty("dropped_m", Math.round(pl.fallDistance));
        var v = Motion.velocity(pl);
        if (v != null) f.addProperty("speed_mps", Math.round(-v.y * 20 * 10) / 10.0);
        Object[] g = ground(pl);
        float hp = pl.getHealth();
        if (g == null) {
            f.addProperty("ground", "I haven't seen what's below me");
            int sofar = landingDamage(pl, 0, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            f.addProperty("verdict", sofar >= hp ? "LETHAL already, if I land on something hard" : sofar > 0 ? "already enough to hurt (" + sofar + " HP) when I land, and more the further I fall" : "unknown drop");
            return f;
        }
        double down = (double) g[0]; BlockState onto = (BlockState) g[1];
        int dmg = landingDamage(pl, down, onto);
        f.addProperty("ground_m", Math.round(down));
        f.addProperty("landing_on", Perception.itemName(new net.minecraft.world.item.ItemStack(onto.getBlock())));
        f.addProperty("landing_damage_about", dmg);
        f.addProperty("verdict", dmg >= hp ? "LETHAL: the landing will kill me" : dmg >= hp / 2 ? "the landing will hurt badly" : dmg > 0 ? "the landing will hurt" : "harmless landing");
        return f;
    }

    /** worth an alarm: a landing that kills or maims, or a long drop into the unseen */
    static boolean alarming(LocalPlayer pl) {
        if (!falling(pl)) return false;
        Object[] g = ground(pl);
        if (g == null) return pl.fallDistance > 4;
        return landingDamage(pl, (double) g[0], (BlockState) g[1]) >= Math.min(pl.getHealth() / 2, 6);
    }
}
