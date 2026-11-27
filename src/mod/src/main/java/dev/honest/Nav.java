package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * A* over the KNOWN map only (freespace-with-risk assumption, a la D* / Baritone's segmented
 * pathing). Unknown cells are allowed but cost `risk` each; known hazards are never entered.
 * If the goal can't be reached through known/assumed space within budget, we return the path to
 * the reachable node closest to the goal, i.e. we walk to the frontier and look again.
 */
public final class Nav {
    private Nav() {}

    static final double INF = 1e18;

    static boolean danger(BlockState s) {
        if (s == null) return false;
        String n = Perception.blockName(s);
        return n.contains("lava") || n.contains("fire") || n.equals("magma_block") || n.equals("cactus")
                || n.equals("sweet_berry_bush") || n.equals("powder_snow") || n.contains("campfire");
    }

    static boolean passable(BlockState s, int x, int y, int z) {
        if (s == null) return true; // assumed (costed separately)
        if (danger(s)) return false;
        if (!s.getFluidState().isEmpty()) return false; // MVP: no swimming
        return s.getCollisionShape(Minecraft.getInstance().level, new BlockPos(x, y, z)).isEmpty();
    }

    static boolean solid(BlockState s, int x, int y, int z) {
        // Asymmetric optimism: unknown AIR is assumed (worst case you bump into something), but
        // unknown GROUND is not — it might be a cliff (a "negative obstacle"). Look before you step.
        if (s == null) return false;
        if (danger(s) || Vis.isAirish(s) || !s.getFluidState().isEmpty()) return false;
        return !s.getCollisionShape(Minecraft.getInstance().level, new BlockPos(x, y, z)).isEmpty();
    }

    /** Cost to stand with feet at (x,y,z); INF if forbidden. */
    static double stand(int x, int y, int z, double risk) {
        KnownMap m = Perception.MAP;
        BlockState feet = m.get(x, y, z), head = m.get(x, y + 1, z), below = m.get(x, y - 1, z);
        if (!passable(feet, x, y, z) || !passable(head, x, y + 1, z) || !solid(below, x, y - 1, z)) return INF;
        int unknown = (feet == null ? 1 : 0) + (head == null ? 1 : 0) + (below == null ? 1 : 0);
        double c = unknown * risk;
        // keep a respectful distance from known hazards
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (danger(m.get(x + dx, y - 1, z + dz)) || danger(m.get(x + dx, y, z + dz))) c += 6;
        return c;
    }

    /** Is this standing spot next to an edge (a neighbour whose floor isn't known-solid)? */
    static boolean edge(int x, int y, int z) {
        KnownMap m = Perception.MAP;
        int[][] n = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : n) {
            int ax = x + d[0], az = z + d[1];
            BlockState side = m.get(ax, y, az);
            if (side != null && !passable(side, ax, y, az)) continue; // a wall there: can't fall that way
            boolean floor = false;
            for (int dy = 1; dy <= 2 && !floor; dy++) floor = solid(m.get(ax, y - dy, az), ax, y - dy, az);
            if (!floor) return true;
        }
        return false;
    }

    public record Result(List<BlockPos> path, boolean complete, int expanded) {}

    public static Result plan(BlockPos start, BlockPos goal, double risk, int maxNodes) {
        record N(long key, int x, int y, int z, double g, double f) {}
        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(N::f));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long sk = start.asLong();
        open.add(new N(sk, start.getX(), start.getY(), start.getZ(), 0, h(start.getX(), start.getY(), start.getZ(), goal)));
        best.put(sk, 0.0);
        long bestKey = sk; double bestH = h(start.getX(), start.getY(), start.getZ(), goal);
        int expanded = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        while (!open.isEmpty() && expanded < maxNodes) {
            N n = open.poll();
            if (n.g > best.getOrDefault(n.key, INF) + 1e-9) continue;
            expanded++;
            double hh = h(n.x, n.y, n.z, goal);
            if (hh < bestH) { bestH = hh; bestKey = n.key; }
            if (Math.abs(n.x - goal.getX()) <= 0 && Math.abs(n.z - goal.getZ()) <= 0 && Math.abs(n.y - goal.getY()) <= 1) {
                return new Result(rebuild(parent, n.key), true, expanded);
            }
            for (int[] d : dirs) {
                int nx = n.x + d[0], nz = n.z + d[1];
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag) { // no corner cutting
                    if (stand(n.x + d[0], n.y, n.z, risk) >= INF && !passable(Perception.MAP.get(n.x + d[0], n.y, n.z), n.x + d[0], n.y, n.z)) continue;
                    if (!passable(Perception.MAP.get(n.x + d[0], n.y, n.z), n.x + d[0], n.y, n.z) || !passable(Perception.MAP.get(n.x, n.y, n.z + d[1]), n.x, n.y, n.z + d[1])) continue;
                    if (!passable(Perception.MAP.get(n.x + d[0], n.y + 1, n.z), n.x + d[0], n.y + 1, n.z) || !passable(Perception.MAP.get(n.x, n.y + 1, n.z + d[1]), n.x, n.y + 1, n.z + d[1])) continue;
                }
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    if (diag && dy != 0) continue;
                    int ny = n.y + dy;
                    if (dy == 1 && !passable(Perception.MAP.get(n.x, n.y + 2, n.z), n.x, n.y + 2, n.z)) continue; // head room to jump
                    if (dy < 0) { // the column we step off into must be clear down to the landing
                        boolean ok = true;
                        for (int yy = ny + 1; yy <= n.y + 1; yy++)
                            if (!passable(Perception.MAP.get(nx, yy, nz), nx, yy, nz)) { ok = false; break; }
                        if (!ok) continue;
                    }
                    double sc = stand(nx, ny, nz, risk);
                    if (sc >= INF) continue;
                    double step = (diag ? 1.414 : 1.0) + (dy > 0 ? 0.6 : 0) + (dy < -1 ? 0.4 * -dy : 0) + sc;
                    long k = BlockPos.asLong(nx, ny, nz);
                    double g = n.g + step;
                    if (g < best.getOrDefault(k, INF)) {
                        best.put(k, g); parent.put(k, n.key);
                        open.add(new N(k, nx, ny, nz, g, g + h(nx, ny, nz, goal)));
                    }
                    break; // take the first valid dy for this direction (prefer level, then up, then down)
                }
            }
        }
        return new Result(rebuild(parent, bestKey), false, expanded);
    }

    static double h(int x, int y, int z, BlockPos g) {
        double dx = x - g.getX(), dy = y - g.getY(), dz = z - g.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static List<BlockPos> rebuild(Map<Long, Long> parent, long k) {
        LinkedList<BlockPos> p = new LinkedList<>();
        Long cur = k;
        while (cur != null) { p.addFirst(BlockPos.of(cur)); cur = parent.get(cur); }
        return p;
    }
}
