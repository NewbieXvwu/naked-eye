package dev.honest;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Everything the agent has ever seen, and nothing else. Navigation may only consult this.
 * Stored as BlockPos.asLong -> Block state id. Absent = unknown.
 */
public final class KnownMap {
    public static final int UNKNOWN = -1;
    private Long2IntOpenHashMap map = new Long2IntOpenHashMap();
    /** per column (x,z): highest y at which we've seen something solid */
    private Long2IntOpenHashMap topSolid = new Long2IntOpenHashMap();
    { topSolid.defaultReturnValue(Integer.MIN_VALUE); }
    /** One memory per dimension: the Nether's (5, 100, 6) is not the overworld's (5, 100, 6). (They were one map: the
     *  overworld's caves, water and lava around spawn showed up as phantoms in the Nether near a portal built there.) */
    private final java.util.Map<String, Long2IntOpenHashMap[]> dims = new java.util.HashMap<>();
    private String dim = "overworld";
    public synchronized String dimension() { return dim; }
    public synchronized void switchTo(String d) {
        if (d.equals(dim)) return;
        dims.put(dim, new Long2IntOpenHashMap[]{map, topSolid});
        Long2IntOpenHashMap[] m = dims.remove(d);
        if (m == null) { m = new Long2IntOpenHashMap[]{new Long2IntOpenHashMap(), new Long2IntOpenHashMap()}; m[0].defaultReturnValue(UNKNOWN); m[1].defaultReturnValue(Integer.MIN_VALUE); }
        map = m[0]; topSolid = m[1]; dim = d; version++;
    }
    public static boolean terrainPrior = true;
    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    private static final BlockState ASSUMED_STONE = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
    private static final BlockState ASSUMED_DEEPSLATE = net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState();
    private int version = 0;

    public KnownMap() { map.defaultReturnValue(UNKNOWN); }
    private static final BlockState ASSUMED_NETHERRACK = net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState ASSUMED_END_STONE = net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState();

    public synchronized void see(BlockPos p, BlockState s) {
        int id = Block.getId(s);
        int old = map.put(p.asLong(), id);
        if (!s.isAir() && s.getFluidState().isEmpty() && s.canOcclude()) {
            long col = BlockPos.asLong(p.getX(), 0, p.getZ());
            if (p.getY() > topSolid.get(col)) topSolid.put(col, p.getY());
        }
        if (old != id) version++;
    }

    public synchronized BlockState get(int x, int y, int z) {
        int id = map.get(BlockPos.asLong(x, y, z));
        return id == UNKNOWN ? null : Block.stateById(id);
    }

    /**
     * What a sensible player would ASSUME about a cell: what they saw there, or — if never seen but
     * there's ground seen above it in the same column — "probably rock". Otherwise unknown (null).
     * A prior, not information: digging reveals the truth and the plan is redone.
     */
    public synchronized BlockState getOrAssume(int x, int y, int z) {
        int id = map.get(BlockPos.asLong(x, y, z));
        if (id != UNKNOWN) return Block.stateById(id);
        // (the Nether has a roof: "ground seen above, so rock below" would fill its caverns with imagined rock)
        if (dim.equals("the_nether")) return null;
        int top = topSolid.get(BlockPos.asLong(x, 0, z));
        if (top != Integer.MIN_VALUE && y < top) return dim.equals("the_end") ? ASSUMED_END_STONE : y < 0 ? ASSUMED_DEEPSLATE : ASSUMED_STONE;
        if (top == Integer.MIN_VALUE && terrainPrior) {
            // never seen this column: "the ground probably carries on" — take the nearest seen ground within 4 blocks.
            // Lets the planner head into unseen land the way a person walks toward the horizon; the eyes correct it.
            for (int r = 1; r <= 4; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int t = topSolid.get(BlockPos.asLong(x + dx, 0, z + dz));
                if (t != Integer.MIN_VALUE && t > -64) return y <= t ? (dim.equals("the_end") ? ASSUMED_END_STONE : ASSUMED_STONE) : AIR;
            }
        }
        return null;
    }

    public synchronized boolean known(int x, int y, int z) {
        return map.containsKey(BlockPos.asLong(x, y, z));
    }

    public synchronized void forget(BlockPos p) { if (map.remove(p.asLong()) != UNKNOWN) version++; }

    public synchronized int size() { return map.size(); }

    /** "where have I seen X?" — nearest remembered cells whose block id matches, from my own memory only */
    public synchronized java.util.List<BlockPos> recall(java.util.function.IntPredicate idMatches, BlockPos from, int limit) {
        java.util.List<BlockPos> hits = new java.util.ArrayList<>();
        for (var e : map.long2IntEntrySet()) if (idMatches.test(e.getIntValue())) hits.add(BlockPos.of(e.getLongKey()));
        hits.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(from)));
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    public synchronized int version() { return version; }

    public synchronized void clear() { map.clear(); topSolid.clear(); version++; }

    /** The agent's memory survives a client restart (it's the agent's, after all): the overworld in the given file (as
     *  it always was), each other dimension beside it (honest_memory.the_nether.bin, …). */
    public synchronized void save(java.nio.file.Path f) {
        dims.put(dim, new Long2IntOpenHashMap[]{map, topSolid});
        for (var e : dims.entrySet()) write(fileFor(f, e.getKey()), e.getValue()[0]);
        dims.remove(dim);
    }
    static java.nio.file.Path fileFor(java.nio.file.Path f, String d) {
        if (d.equals("overworld")) return f;
        String n = f.getFileName().toString();
        return f.resolveSibling(n.replaceFirst("\\.bin$", "") + "." + d + ".bin");
    }
    /** write to a temp file and rename: a client killed mid-save used to leave a truncated file = lost memories */
    static void write(java.nio.file.Path f, Long2IntOpenHashMap m) {
        java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp)))) {
            out.writeInt(m.size());
            for (var e : m.long2IntEntrySet()) { out.writeLong(e.getLongKey()); out.writeInt(e.getIntValue()); }
        } catch (Exception e) { return; }
        try { java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception ignored) {}
    }

    public synchronized void load(java.nio.file.Path f) {
        String keep = dim;
        for (String d : new String[]{"overworld", "the_nether", "the_end"}) {
            java.nio.file.Path p = fileFor(f, d);
            if (!java.nio.file.Files.exists(p)) continue;
            switchTo(d);
            read(p);
        }
        // a memory from before there was one per dimension: move what can only have been seen in the Nether out of the
        // overworld's (air, lava and gravel stay put: there's no telling where they were seen)
        if (java.nio.file.Files.exists(f) && !java.nio.file.Files.exists(fileFor(f, "the_nether"))) {
            switchTo("overworld");
            var moved = new java.util.ArrayList<long[]>();
            for (var e : map.long2IntEntrySet()) if (netherOnly(Block.stateById(e.getIntValue()))) moved.add(new long[]{e.getLongKey(), e.getIntValue()});
            if (!moved.isEmpty()) {
                for (long[] m : moved) map.remove(m[0]);
                switchTo("the_nether");
                for (long[] m : moved) see(BlockPos.of(m[0]), Block.stateById((int) m[1]));
                HonestClient.LOG.info("[honest] moved {} Nether blocks out of the overworld memory", moved.size());
            }
        }
        switchTo(keep);
    }
    static boolean netherOnly(BlockState s) {
        String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return n.equals("netherrack") || n.startsWith("soul_") || n.contains("basalt") || n.contains("blackstone") || n.contains("crimson")
            || n.contains("warped") || n.contains("nether_") || n.equals("glowstone") || n.equals("ancient_debris") || n.equals("magma_block")
            || n.equals("shroomlight") || n.contains("nylium") || n.equals("weeping_vines") || n.equals("twisting_vines");
    }
    private void read(java.nio.file.Path f) {
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(f)))) {
            int n = in.readInt();
            // pre-size: re-inserting a hash map's entries in its own iteration order into a smaller, growing table
            // clusters them catastrophically (minutes of CPU at 4M entries — the client "hung" at startup)
            map.ensureCapacity(n);
            for (int i = 0; i < n; i++) {
                long k = in.readLong(); int v = in.readInt(); map.put(k, v);
                BlockState st = Block.stateById(v);
                if (!st.isAir() && st.getFluidState().isEmpty() && st.canOcclude()) {
                    long col = BlockPos.asLong(BlockPos.getX(k), 0, BlockPos.getZ(k));
                    if (BlockPos.getY(k) > topSolid.get(col)) topSolid.put(col, BlockPos.getY(k));
                }
            }
            version++;
        } catch (Exception ignored) {}
    }
}
