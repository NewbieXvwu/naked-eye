package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * "What's around me", in body-relative words: for each of 8 directions, how far I can walk before a wall,
 * a drop, liquid, or the unknown. Plus what's above my head. Read from memory (KnownMap) only.
 */
public final class Sonar {
    static final String[] BODY = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};

    public static String describe(Minecraft mc) {
        LocalPlayer pl = mc.player;
        StringBuilder sb = new StringBuilder("AROUND ME (facing " + Places.compass(-Mth.sin(pl.getYRot() * Mth.DEG_TO_RAD), Mth.cos(pl.getYRot() * Mth.DEG_TO_RAD)) + "):\n");
        for (int b = 0; b < 8; b++) {
            float yaw = pl.getYRot() + b * 45f; // + = clockwise = to the right
            double ux = -Mth.sin(yaw * Mth.DEG_TO_RAD), uz = Mth.cos(yaw * Mth.DEG_TO_RAD);
            sb.append(String.format("  %-12s %s%n", BODY[b] + " (" + Places.compass(ux, uz) + ")", ray(pl, ux, uz)));
        }
        int x = pl.getBlockX(), y = pl.getBlockY() + 2, z = pl.getBlockZ(), up = 0;
        String top = "unknown";
        for (; up < 24; up++) {
            byte c = Places.classify(Perception.MAP.get(x, y + up, z));
            if (c == Places.OPEN) continue;
            top = c == Places.SOLID ? "ceiling" : c == Places.WATER ? "water" : c == Places.HAZARD ? "⚠ LAVA/fire" : "unseen";
            break;
        }
        sb.append("  above        ").append(up == 0 && top.equals("ceiling") ? "ceiling right over my head"
                : up >= 24 ? "open for 24+ m (a shaft or the sky)" : top + " " + (up + 1) + " m up").append('\n');
        return sb.toString();
    }

    /** walk along a direction on memory alone, following 1-block steps; describe the first thing that stops you */
    static String ray(LocalPlayer pl, double ux, double uz) {
        double px = pl.getX(), pz = pl.getZ();
        int level = pl.getBlockY(), lastX = pl.getBlockX(), lastZ = pl.getBlockZ(), rise = 0;
        for (double d = 0.5; d <= 16; d += 0.5) {
            int x = Mth.floor(px + ux * d), z = Mth.floor(pz + uz * d);
            if (x == lastX && z == lastZ) continue;
            lastX = x; lastZ = z;
            String at = fmt(d);
            byte feet = Places.classify(Perception.MAP.get(x, level, z)), head = Places.classify(Perception.MAP.get(x, level + 1, z));
            if (feet == Places.HAZARD || head == Places.HAZARD) return walked(d, rise) + "⚠ LAVA/fire at " + at;
            if (feet == Places.WATER) return walked(d, rise) + "water at " + at;
            if (feet == Places.UNKNOWN || head == Places.UNKNOWN) return walked(d, rise) + "never seen beyond " + at;
            if (feet == Places.SOLID) {
                if (Places.classify(Perception.MAP.get(x, level + 1, z)) == Places.OPEN && Places.classify(Perception.MAP.get(x, level + 2, z)) == Places.OPEN) { level++; rise++; continue; }
                return walked(d, rise) + "wall at " + at;
            }
            if (head == Places.SOLID) return walked(d, rise) + "low ceiling at " + at + " (crawl/dig)";
            // open: is there floor?
            int y = level - 1, drop = 0;
            while (drop < 30 && Places.classify(Perception.MAP.get(x, y, z)) == Places.OPEN) { y--; drop++; }
            byte under = Places.classify(Perception.MAP.get(x, y, z));
            if (drop == 0 && under == Places.SOLID) continue;
            if (drop == 1 && under == Places.SOLID) { level--; rise--; continue; }
            String into = under == Places.SOLID ? "" : under == Places.WATER ? " into water" : under == Places.HAZARD ? " into ⚠LAVA" : " (bottom never seen)";
            if (drop == 0) return walked(d, rise) + (under == Places.WATER ? "water at " + at : under == Places.HAZARD ? "⚠ LAVA at " + at : "floor never seen at " + at);
            return walked(d, rise) + "DROP " + (drop >= 30 ? "30+" : drop + 1) + " m at " + at + into;
        }
        return walked(16, rise) + "(still going)";
    }

    static String fmt(double d) { return Math.round(d) + " m"; }
    static String walked(double d, int rise) {
        if (d < 1.01) return "";
        return "walkable " + Math.round(d - 0.5) + " m" + (rise > 1 ? " (rising " + rise + ")" : rise < -1 ? " (falling " + -rise + ")" : "") + ", then ";
    }
}
