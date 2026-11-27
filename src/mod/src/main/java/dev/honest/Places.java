package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * A cave as a person remembers it: places and how they connect — not a voxel dump.
 *
 * Built only from the KnownMap. Every standable cell (solid floor, two known-open cells above) is a node;
 * cells are grouped into "places" (connected pieces inside 8x8x8 blocks of the world), and places are
 * linked by how you get between them: walk (both ways), drop N m (one way!), or the reverse: climb, which
 * walking can't do. Openings into never-seen space are "frontiers". Language models are bad at reading
 * 3D layouts out of ASCII, but fine at reasoning over a small labelled graph — so this is what the brain gets.
 */
public final class Places {
    static final int R = 32, RV = 24, MAX_DROP = 12;
    static final byte UNKNOWN = 0, OPEN = 1, SOLID = 2, WATER = 3, HAZARD = 4;

    /** what the last listing called P3 / F2: ids are only meaningful until the next listing */
    static final Map<String, BlockPos> LAST = new HashMap<>();
    /** named landmarks, set by the agent ("mark") */
    static final Map<String, BlockPos> MARKS = new LinkedHashMap<>();

    /** marks of the other dimensions (MARKS holds the ones of the dimension I'm in) */
    static final Map<String, Map<String, BlockPos>> OTHER_DIMS = new HashMap<>();
    static String markDim = "overworld";
    static void switchDim(String d) {
        if (d.equals(markDim)) return;
        OTHER_DIMS.put(markDim, new LinkedHashMap<>(MARKS));
        MARKS.clear();
        var m = OTHER_DIMS.remove(d); if (m != null) MARKS.putAll(m);
        markDim = d; LAST.clear();
    }
    /** "home" was marked in the overworld, and I'm in the Nether: say so */
    static String markElsewhere(String name) {
        for (var e : OTHER_DIMS.entrySet()) if (e.getValue().containsKey(name)) return e.getKey().replace("the_", "the ");
        return null;
    }

    static final java.nio.file.Path MARK_FILE = java.nio.file.Path.of("honest_marks.txt");
    static void saveMarks() {
        StringBuilder sb = new StringBuilder();
        for (var e : MARKS.entrySet()) sb.append(e.getKey()).append(' ').append(e.getValue().asLong()).append(' ').append(markDim).append('\n');
        for (var d : OTHER_DIMS.entrySet()) for (var e : d.getValue().entrySet())
            sb.append(e.getKey()).append(' ').append(e.getValue().asLong()).append(' ').append(d.getKey()).append('\n');
        try { java.nio.file.Files.writeString(MARK_FILE, sb); } catch (Exception ignored) {}
    }
    static { // marks survive restarts, like the memory map does ("name pos [dimension]"; no dimension = the overworld)
        try { for (String l : java.nio.file.Files.readAllLines(MARK_FILE)) {
            String[] t = l.split(" "); String d = t.length > 2 ? t[2] : "overworld";
            (d.equals(markDim) ? MARKS : OTHER_DIMS.computeIfAbsent(d, k -> new LinkedHashMap<>())).put(t[0], BlockPos.of(Long.parseLong(t[1])));
        } } catch (Exception ignored) {}
    }

    record Place(int id, List<int[]> cells, Set<Integer> out) {}

    int x0, y0, z0, W, H;
    byte[] cls;
    int[] placeOf; // per cell index, -1 if not standable
    final List<Place> places = new ArrayList<>();
    // directed edges between places: key from*65536+to -> best (lowest) "kind cost": 0 walk, h = drop height
    final Map<Long, Integer> edges = new HashMap<>();
    final Set<Long> roughDrops = new HashSet<>(); // landing spot inferred: the cliff foot itself wasn't seen

    int idx(int i, int j, int k) { return (j * W + k) * W + i; }
    boolean in(int i, int j, int k) { return i >= 0 && j >= 0 && k >= 0 && i < W && k < W && j < H; }
    byte c(int i, int j, int k) { return in(i, j, k) ? cls[idx(i, j, k)] : UNKNOWN; }
    boolean open(int i, int j, int k) { byte b = c(i, j, k); return b == OPEN; }
    boolean standable(int i, int j, int k) { return open(i, j, k) && open(i, j + 1, k) && c(i, j - 1, k) == SOLID; }

    static byte classify(BlockState s) {
        if (s == null) return UNKNOWN;
        if (!s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return SOLID;
        if (s.getFluidState().is(FluidTags.LAVA)) return HAZARD;
        if (!s.getFluidState().isEmpty()) return WATER;
        String n = s.getBlock().getDescriptionId();
        if (n.contains("fire") || n.contains("powder_snow")) return HAZARD;
        return OPEN;
    }

    public static String describe(Minecraft mc) {
        Places p = new Places();
        p.build(mc.player.blockPosition());
        return p.render(mc);
    }

    void build(BlockPos me) {
        x0 = me.getX() - R; y0 = me.getY() - RV; z0 = me.getZ() - R; W = 2 * R + 1; H = 2 * RV + 1;
        cls = new byte[W * W * H];
        for (int j = 0; j < H; j++) for (int k = 0; k < W; k++) for (int i = 0; i < W; i++)
            cls[idx(i, j, k)] = classify(Perception.MAP.get(x0 + i, y0 + j, z0 + k));

        // union-find over standable cells, only merging within the same 8x8x8 world block
        placeOf = new int[cls.length];
        Arrays.fill(placeOf, -1);
        for (int j = 1; j < H - 2; j++) for (int k = 0; k < W; k++) for (int i = 0; i < W; i++)
            if (standable(i, j, k)) { int a = idx(i, j, k); placeOf[a] = a; }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> oneWay = new ArrayList<>(); // {fromIdx, toIdx, height}
        List<int[]> twoWay = new ArrayList<>();
        for (int j = 1; j < H - 2; j++) for (int k = 0; k < W; k++) for (int i = 0; i < W; i++) {
            if (placeOf[idx(i, j, k)] < 0) continue;
            int a = idx(i, j, k);
            for (int[] d : dirs) {
                int ni = i + d[0], nk = k + d[1];
                if (!in(ni, j, nk)) continue;
                if (standable(ni, j, nk)) twoWay.add(new int[]{a, idx(ni, j, nk)});
                else if (standable(ni, j + 1, nk) && open(i, j + 2, k)) twoWay.add(new int[]{a, idx(ni, j + 1, nk)});
                else if (open(ni, j, nk) && open(ni, j + 1, nk)) { // walk off an edge: where do you land?
                    int y = j - 1;
                    while (y > 0 && (open(ni, y, nk) || c(ni, y, nk) == WATER) && j - y <= MAX_DROP + 1) y--;
                    int h = j - (y + 1);
                    if (h >= 2 && h <= MAX_DROP && c(ni, y, nk) == SOLID && standable(ni, y + 1, nk))
                        oneWay.add(new int[]{a, idx(ni, y + 1, nk), h});
                    else { // the foot of a cliff right under the edge is never visible from on top of it;
                           // but the floor a bit further out often is — you'd land about there
                        outer:
                        for (int t = 2; t <= 5; t++) {
                            int ti = i + t * d[0], tk = k + t * d[1];
                            for (int m = 1; m < t; m++) { byte f = c(i + m * d[0], j, k + m * d[1]), g = c(i + m * d[0], j + 1, k + m * d[1]); if (f == SOLID || g == SOLID) break outer; }
                            for (int ty = j - 2; ty >= j - MAX_DROP && ty > 0; ty--) {
                                byte col = c(ti, ty + 2, tk);
                                if (col == SOLID || col == HAZARD) break;
                                if (standable(ti, ty, tk)) { oneWay.add(new int[]{a, idx(ti, ty, tk), j - ty, 1}); break outer; }
                            }
                        }
                    }
                }
            }
        }
        // links that reach a cell at the very edge of the scanned box point at a cell with no place: drop them
        twoWay.removeIf(e -> placeOf[e[0]] < 0 || placeOf[e[1]] < 0);
        oneWay.removeIf(e -> placeOf[e[0]] < 0 || placeOf[e[1]] < 0);
        // grow places from seeds over two-way walking links, capped in size so a big cavern becomes a few
        // ~20-block pieces and a room stays one piece (fixed 8^3 grid cells cut rooms at arbitrary lines)
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] e : twoWay) { adj.computeIfAbsent(e[0], x -> new ArrayList<>()).add(e[1]); adj.computeIfAbsent(e[1], x -> new ArrayList<>()).add(e[0]); }
        int[] owner = new int[cls.length]; Arrays.fill(owner, -1);
        for (int a = 0; a < cls.length; a++) {
            if (placeOf[a] < 0 || owner[a] >= 0) continue;
            int pid = places.size(); Place pl = new Place(pid, new ArrayList<>(), new HashSet<>()); places.add(pl);
            int[] sq = coords(a);
            Deque<Integer> q = new ArrayDeque<>(List.of(a)); owner[a] = pid;
            while (!q.isEmpty()) {
                int u = q.poll(); pl.cells.add(coords(u));
                for (int v : adj.getOrDefault(u, List.of())) {
                    if (owner[v] >= 0) continue;
                    int[] vq = coords(v);
                    if (Math.abs(vq[0] - sq[0]) > 10 || Math.abs(vq[2] - sq[2]) > 10 || Math.abs(vq[1] - sq[1]) > 4) continue;
                    owner[v] = pid; q.add(v);
                }
            }
        }
        // crumbs (1-3 cells left between big pieces) join the neighbour they touch
        for (Place pl : places) {
            if (pl.cells.size() > 3) continue;
            for (int[] q : pl.cells) {
                for (int v : adj.getOrDefault(idx(q[0], q[1], q[2]), List.of()))
                    if (owner[v] != pl.id && places.get(owner[v]).cells.size() > 3) {
                        int to = owner[v];
                        for (int[] r : pl.cells) owner[idx(r[0], r[1], r[2])] = to;
                        places.get(to).cells.addAll(pl.cells); pl.cells.clear();
                        break;
                    }
                if (pl.cells.isEmpty()) break;
            }
        }
        // renumber, dropping emptied places
        List<Place> kept = new ArrayList<>(); int[] remap = new int[places.size()];
        for (Place pl : places) { if (pl.cells.isEmpty()) continue; remap[pl.id] = kept.size(); kept.add(new Place(kept.size(), pl.cells, new HashSet<>())); }
        places.clear(); places.addAll(kept);
        for (int a = 0; a < cls.length; a++) if (placeOf[a] >= 0) placeOf[a] = remap[owner[a]];
        for (int[] e : twoWay) {
            int pa = placeOf[e[0]], pb = placeOf[e[1]];
            if (pa != pb) { edge(pa, pb, 0); edge(pb, pa, 0); }
        }
        for (int[] e : oneWay) {
            int pa = placeOf[e[0]], pb = placeOf[e[1]];
            if (pa != pb) { edge(pa, pb, e[2]); if (e.length > 3) roughDrops.add(((long) pa << 20) | pb); }
        }
    }

    void edge(int a, int b, int h) { edges.merge(((long) a << 20) | b, h, Math::min); places.get(a).out.add(b); }
    Integer edgeKind(int a, int b) { return edges.get(((long) a << 20) | b); }
    long blockKey(int a) { int[] q = coords(a); return BlockPos.asLong(Math.floorDiv(x0 + q[0], 8), Math.floorDiv(y0 + q[1], 8), Math.floorDiv(z0 + q[2], 8)); }
    int[] coords(int a) { int i = a % W, k = (a / W) % W, j = a / (W * W); return new int[]{i, j, k}; }
    static int find(int[] p, int a) { while (p[a] != a) { p[a] = p[p[a]]; a = p[a]; } return a; }
    static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[a] = b; }

    BlockPos world(int[] q) { return new BlockPos(x0 + q[0], y0 + q[1], z0 + q[2]); }

    /** representative cell: the member nearest the centroid */
    BlockPos rep(Place p) {
        double cx = 0, cy = 0, cz = 0;
        for (int[] q : p.cells) { cx += q[0]; cy += q[1]; cz += q[2]; }
        int n = p.cells.size(); cx /= n; cy /= n; cz /= n;
        int[] best = p.cells.get(0); double bd = 1e9;
        for (int[] q : p.cells) { double d = Math.pow(q[0] - cx, 2) + Math.pow(q[1] - cy, 2) * 4 + Math.pow(q[2] - cz, 2); if (d < bd) { bd = d; best = q; } }
        return world(best);
    }

    // ------------------------------------------------------------------ rendering
    String render(Minecraft mc) {
        LocalPlayer pl = mc.player;
        LAST.clear();
        StringBuilder sb = new StringBuilder();
        sb.append(Sonar.describe(mc)).append('\n');
        BlockPos me = pl.blockPosition();
        int here = placeNear(me);
        if (here < 0) { sb.append("(I'm not standing on known ground — no place graph from here)\n"); return sb.toString(); }

        // Dijkstra from here over the directed place graph
        int n = places.size();
        double[] dist = new double[n]; int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY); Arrays.fill(prev, -1);
        BlockPos[] reps = new BlockPos[n];
        for (int i = 0; i < n; i++) reps[i] = rep(places.get(i));
        dist[here] = 0;
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        pq.add(new double[]{0, here});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll(); int u = (int) cur[1];
            if (cur[0] > dist[u]) continue;
            for (int v : places.get(u).out) {
                double w = Math.sqrt(reps[u].distSqr(reps[v])) + edgeKind(u, v) * 2.0;
                if (dist[u] + w < dist[v]) { dist[v] = dist[u] + w; prev[v] = u; pq.add(new double[]{dist[v], v}); }
            }
        }
        // reverse reachability: from which places can I get BACK here? (BFS on reversed edges)
        boolean[] canReturn = new boolean[n];
        Deque<Integer> dq = new ArrayDeque<>(List.of(here)); canReturn[here] = true;
        Map<Integer, List<Integer>> rev = new HashMap<>();
        for (Place p : places) for (int v : p.out) rev.computeIfAbsent(v, x -> new ArrayList<>()).add(p.id);
        while (!dq.isEmpty()) { int v = dq.poll(); for (int u : rev.getOrDefault(v, List.of())) if (!canReturn[u]) { canReturn[u] = true; dq.add(u); } }

        // list: reachable places by distance, nearest first (cap), plus a count of the rest
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) if (dist[i] < Double.POSITIVE_INFINITY) order.add(i);
        order.sort(Comparator.comparingDouble(i -> dist[i]));
        int shown = Math.min(order.size(), 16);
        Map<Integer, String> label = new HashMap<>();
        for (int r = 0; r < shown; r++) label.put(order.get(r), "P" + r);
        sb.append(String.format("PLACES I know around here (%d reachable on foot, %d shown; ids valid until the next listing)%n", order.size(), shown));
        int fCount = 0;
        for (int r = 0; r < shown; r++) {
            int pid = order.get(r); Place p = places.get(pid); BlockPos rp = reps[pid];
            LAST.put("P" + r, rp);
            sb.append(String.format("%s%s  %s, %d floor cells, floor y %s%s%s%n", "P" + r, r == 0 ? " (HERE)" : "",
                    r == 0 ? "around me" : rel(pl, rp), p.cells.size(), yRange(p), light(mc, p), feats(p)));
            // connections
            List<String> cons = new ArrayList<>();
            Set<Integer> nb = new TreeSet<>(p.out);
            for (Place q : places) if (q.out.contains(pid)) nb.add(q.id);
            int hidden = 0;
            for (int q : nb) {
                if (!label.containsKey(q)) { hidden++; continue; } // keep lines short: only links between listed places
                String ql = label.get(q);
                Integer to = edgeKind(pid, q), back = edgeKind(q, pid);
                if (to != null && to == 0) cons.add("↔ " + ql);
                else if (to != null) cons.add("↓ drop " + (roughDrops.contains(((long) pid << 20) | q) ? "~" : "") + to + " m to " + ql + (back == null ? " — ONE WAY" : "") + (to > 3 ? " (fall damage ~" + (to - 3) + ")" : ""));
                else if (back != null) cons.add("↑ " + ql + " is " + back + " m up (climb: pillar/dig stairs)");
            }
            if (hidden > 0) cons.add("+" + hidden + " links to farther places");
            if (!cons.isEmpty()) sb.append("     ").append(String.join("; ", cons)).append('\n');
            if (r > 0) {
                List<String> path = new ArrayList<>();
                for (int v = pid; v != -1; v = prev[v]) path.add(0, label.getOrDefault(v, "·"));
                sb.append("     route from here: ").append(String.join(" → ", path));
                if (!canReturn[pid]) sb.append("   ⚠ NO WAY BACK on foot once there");
                sb.append('\n');
            }
            for (String f : frontiers(pl, p)) { LAST.put("F" + fCount, BlockPos.of(Long.parseLong(f.split("\\|")[0]))); sb.append("     F").append(fCount++).append(": ").append(f.split("\\|")[1]).append('\n'); }
        }
        if (order.size() > shown) sb.append("  … ").append(order.size() - shown).append(" more places farther away\n");
        int unreachable = n - order.size();
        if (unreachable > 0) sb.append("  (").append(unreachable).append(" known places I can't reach on foot from here)\n");
        // landmarks
        for (var m : MARKS.entrySet()) {
            int mp = placeNear(m.getValue());
            String how = mp < 0 ? "not on the known graph near here" : dist[mp] == Double.POSITIVE_INFINITY ? "not reachable on foot from here"
                    : "reachable" + (label.containsKey(mp) ? " (" + label.get(mp) + ")" : "") + (canReturn[mp] ? "" : ", but no way back");
            String back = mp >= 0 && canReturn[mp] ? "" : "";
            sb.append("MARK ").append(m.getKey()).append(": ").append(rel(pl, m.getValue())).append(" — ").append(how).append(back).append('\n');
            LAST.put(m.getKey(), m.getValue());
        }
        return sb.toString();
    }

    int placeNear(BlockPos p) {
        int bi = p.getX() - x0, bj = p.getY() - y0, bk = p.getZ() - z0;
        for (int dy : new int[]{0, -1, 1, -2}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int i = bi + dx, j = bj + dy, k = bk + dz;
            if (in(i, j, k) && placeOf[idx(i, j, k)] >= 0) return placeOf[idx(i, j, k)];
        }
        return -1;
    }

    String yRange(Place p) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] q : p.cells) { lo = Math.min(lo, y0 + q[1]); hi = Math.max(hi, y0 + q[1]); }
        return lo == hi ? String.valueOf(lo) : lo + ".." + hi;
    }

    String light(Minecraft mc, Place p) {
        int max = 0; boolean sky = false;
        for (int[] q : p.cells) {
            BlockPos b = world(q).above();
            max = Math.max(max, mc.level.getMaxLocalRawBrightness(b));
            if (mc.level.getBrightness(LightLayer.SKY, b) >= 14) sky = true;
        }
        return sky ? ", open to the sky" : max >= 8 ? ", lit" : max >= 1 ? ", dim" : ", pitch dark";
    }

    /** notable blocks seen on the walls/floor/ceiling of this place */
    String feats(Place p) {
        int minI = W, maxI = 0, minJ = H, maxJ = 0, minK = W, maxK = 0;
        for (int[] q : p.cells) { minI = Math.min(minI, q[0]); maxI = Math.max(maxI, q[0]); minJ = Math.min(minJ, q[1]); maxJ = Math.max(maxJ, q[1]); minK = Math.min(minK, q[2]); maxK = Math.max(maxK, q[2]); }
        Map<String, Integer> f = new TreeMap<>();
        for (int j = minJ - 2; j <= maxJ + 4; j++) for (int k = minK - 2; k <= maxK + 2; k++) for (int i = minI - 2; i <= maxI + 2; i++) {
            BlockState s = Perception.MAP.get(x0 + i, y0 + j, z0 + k);
            if (s == null) continue;
            String nm = Perception.itemName(new net.minecraft.world.item.ItemStack(s.getBlock()));
            if (nm.isEmpty() || nm.equals("air")) nm = s.getBlock().getDescriptionId().replace("block.minecraft.", "");
            if (nm.endsWith("_ore") || nm.equals("lava") || nm.equals("water") || nm.contains("torch") || nm.contains("chest")
                    || nm.contains("spawner") || nm.startsWith("sculk") || nm.contains("amethyst") || nm.equals("crafting_table") || nm.equals("furnace"))
                f.merge(nm.replace("wall_", ""), 1, Integer::sum);
        }
        if (f.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var e : f.entrySet()) parts.add((e.getKey().equals("lava") ? "⚠lava" : e.getKey()) + (e.getValue() > 1 ? "×" + e.getValue() : ""));
        return " — sees: " + String.join(", ", parts);
    }

    /** openings from this place into space I have never seen; returns "posLong|description" */
    List<String> frontiers(LocalPlayer pl, Place p) {
        // from each floor cell, flood a few steps through known open space (any direction, so a passage that
        // dips down or a hole in the floor counts); if that space touches never-seen cells, it's an opening
        List<int[]> fr = new ArrayList<>();
        int[][] six = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] q : p.cells) {
            Set<Integer> seen = new HashSet<>(); Deque<int[]> dq = new ArrayDeque<>();
            dq.add(new int[]{q[0], q[1], q[2], 0}); seen.add(idx(q[0], q[1], q[2]));
            int unknown = 0;
            while (!dq.isEmpty() && unknown < 4) {
                int[] u = dq.poll();
                for (int[] d : six) {
                    int i = u[0] + d[0], j = u[1] + d[1], k = u[2] + d[2];
                    if (!in(i, j, k)) continue;
                    byte cc = c(i, j, k);
                    if (cc == UNKNOWN) { unknown++; continue; }
                    if (cc == OPEN && u[3] < 3 && seen.add(idx(i, j, k))) dq.add(new int[]{i, j, k, u[3] + 1});
                }
            }
            if (unknown >= 2) fr.add(q);
        }
        if (fr.size() < 2) return List.of();
        // cluster by rough direction from the place's centre, so "an opening to the north" is one frontier
        BlockPos c = rep(p);
        Map<String, List<int[]>> byDir = new TreeMap<>();
        for (int[] q : fr) { BlockPos w = world(q); byDir.computeIfAbsent(compass(w.getX() - c.getX(), w.getZ() - c.getZ()), x -> new ArrayList<>()).add(q); }
        List<String> out = new ArrayList<>();
        for (var e : byDir.entrySet()) {
            if (e.getValue().size() < 2) continue;
            int[] q = e.getValue().get(e.getValue().size() / 2);
            BlockPos w = world(q);
            out.add(w.asLong() + "|unexplored opening on the " + e.getKey() + " side (" + e.getValue().size() + " cells border unseen space), " + rel(pl, w));
            if (out.size() >= 3) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ words
    static String compass(double dx, double dz) {
        if (Math.hypot(dx, dz) < 1e-6) return "centre";
        String[] n = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double a = Math.toDegrees(Math.atan2(-dx, dz)); // MC yaw convention: 0 = south
        return n[Math.floorMod((int) Math.round(a / 45.0), 8)];
    }

    /** "12 m north-east (ahead-right), 5 m below" — compass AND body-relative, so nobody has to rotate frames */
    static String rel(LocalPlayer pl, BlockPos p) {
        double dx = p.getX() + 0.5 - pl.getX(), dz = p.getZ() + 0.5 - pl.getZ(), dy = p.getY() - pl.getBlockY();
        double h = Math.hypot(dx, dz);
        String body = "";
        if (h >= 1) {
            double bearing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - pl.getYRot());
            String[] b = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};
            body = " (" + b[Math.floorMod((int) Math.round(bearing / 45.0), 8)] + ")";
        }
        String v = Math.abs(dy) < 1 ? "same level" : String.format("%d m %s", (int) Math.abs(dy), dy > 0 ? "above" : "below");
        return h < 1 ? v : String.format("%d m %s%s, %s", Math.round(h), compass(dx, dz), body, v);
    }
}
