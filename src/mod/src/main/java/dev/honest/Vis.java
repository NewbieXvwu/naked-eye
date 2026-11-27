package dev.honest;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Line-of-sight primitives. We do our own voxel traversal instead of ClipContext so that
 * "what blocks sight" is decided by how a block LOOKS (glass, leaves, water are see-through),
 * not by its collision or outline shape.
 */
public final class Vis {
    private Vis() {}

    /** Does this block stop sight? */
    public static boolean opaque(BlockState s) {
        if (s.isAir()) return false;
        if (s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA)) return true;
        if (s.getBlock() instanceof LeavesBlock) return false; // fancy-graphics leaves are see-through
        return s.canOcclude();
    }

    public interface VoxelVisitor {
        /** @return true to continue marching */
        boolean visit(BlockPos.MutableBlockPos p, BlockState s, double dist);
    }

    /** Amanatides & Woo voxel traversal from origin along unit dir, up to maxDist. */
    public static void march(ClientLevel level, Vec3 origin, Vec3 dir, double maxDist, VoxelVisitor v) {
        int x = (int) Math.floor(origin.x), y = (int) Math.floor(origin.y), z = (int) Math.floor(origin.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double tdx = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
        double tdy = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.y);
        double tdz = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
        double tmx = dir.x == 0 ? Double.MAX_VALUE : ((sx > 0 ? (x + 1 - origin.x) : (origin.x - x)) * tdx);
        double tmy = dir.y == 0 ? Double.MAX_VALUE : ((sy > 0 ? (y + 1 - origin.y) : (origin.y - y)) * tdy);
        double tmz = dir.z == 0 ? Double.MAX_VALUE : ((sz > 0 ? (z + 1 - origin.z) : (origin.z - z)) * tdz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double t = 0;
        while (t <= maxDist) {
            p.set(x, y, z);
            if (!level.isLoaded(p)) return;
            if (!v.visit(p, level.getBlockState(p), t)) return;
            if (tmx < tmy && tmx < tmz) { x += sx; t = tmx; tmx += tdx; }
            else if (tmy < tmz) { y += sy; t = tmy; tmy += tdy; }
            else { z += sz; t = tmz; tmz += tdz; }
        }
    }

    /** True if nothing opaque lies strictly between a and b. */
    public static boolean clear(ClientLevel level, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1e-6) return true;
        Vec3 dir = d.scale(1.0 / len);
        BlockPos target = BlockPos.containing(b);
        final boolean[] ok = {true};
        march(level, a, dir, len, (p, s, dist) -> {
            if (p.equals(target)) return false;
            if (opaque(s)) { ok[0] = false; return false; }
            return true;
        });
        return ok[0];
    }

    /**
     * Is this spot lit enough for a human to make anything out at this distance?
     * Uses the same raw brightness the renderer uses (block light vs. darkened sky light).
     */
    public static boolean lit(ClientLevel level, BlockPos pos, double dist) {
        if (dist <= 4.0) return true; // you can always make out things right next to you
        return level.getMaxLocalRawBrightness(pos) >= 1;
    }

    public static boolean isAirish(BlockState s) {
        return s.isAir() || s.is(Blocks.CAVE_AIR) || s.is(Blocks.VOID_AIR);
    }
}
