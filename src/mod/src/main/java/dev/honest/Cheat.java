package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Cheat mode (HONEST_CHEAT=1 ./start.sh): the goal is to finish the game as fast as possible, and the agent may know
 * EVERYTHING the vanilla server sends to this client — every block of every loaded chunk (X-ray), every entity it
 * knows of with its exact position and synced health, through walls and in the dark.
 *
 * What stays out is what the server never sends: the seed, where structures are, what's inside an unopened chest,
 * other players' inventories. And the hands stay a player's hands: nothing here sends a packet a normal client
 * wouldn't (no commands, no faked positions or fall flags); it's the same keys and clicks, only better informed,
 * aimed instantly, and driven by an unrestricted Baritone.
 */
public final class Cheat {
    private Cheat() {}

    public static final boolean ON = Boolean.getBoolean("honest.cheat");

    // ------------------------------------------------------------------ name matching
    /** "iron_ore" matches iron_ore and deepslate_iron_ore; "log" matches every *_log; "~glass" = contains */
    static boolean matches(String block, String q) {
        if (q.startsWith("~")) return block.contains(q.substring(1));
        return block.equals(q) || block.endsWith("_" + q);
    }

    static BitSet ids(Collection<String> qs) {
        BitSet b = new BitSet();
        for (BlockState st : Block.BLOCK_STATE_REGISTRY) {
            String n = Perception.blockName(st);
            for (String q : qs) if (matches(n, q)) { b.set(Block.getId(st)); break; }
        }
        return b;
    }

    /** every loaded chunk within r blocks (horizontally) of the player */
    static List<LevelChunk> chunks(ClientLevel lvl, BlockPos c, int r) {
        List<LevelChunk> out = new ArrayList<>();
        int cr = (r >> 4) + 1, cx = c.getX() >> 4, cz = c.getZ() >> 4;
        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) {
            var ch = lvl.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
            if (ch instanceof LevelChunk lc) out.add(lc);
        }
        return out;
    }

    // ------------------------------------------------------------------ chunk memory
    // Everything notable in every chunk the server ever sent me, kept after the chunk unloads: so a search isn't
    // capped by the view distance (~100 m) — it reaches every place I've been. Loaded chunks are always read live.
    static BitSet TRACK;
    static boolean tracked(String n) {
        return n.endsWith("_ore") || n.endsWith("_log") || n.endsWith("_stem") || n.endsWith("_bed") || n.endsWith("_wool") || n.endsWith("chest")
            || Set.of("ancient_debris", "obsidian", "crying_obsidian", "nether_portal", "end_portal", "end_portal_frame", "end_gateway",
                      "spawner", "trial_spawner", "barrel", "nether_bricks", "nether_brick_fence", "gilded_blackstone", "gold_block",
                      "iron_block", "diamond_block", "bell", "hay_block", "crafting_table", "furnace", "blast_furnace", "smoker",
                      "dragon_egg", "chorus_flower", "lava", "warped_nylium", "budding_amethyst").contains(n);
    }
    static BitSet track() {
        if (TRACK == null) { BitSet b = new BitSet(); for (BlockState st : Block.BLOCK_STATE_REGISTRY) if (tracked(Perception.blockName(st))) b.set(Block.getId(st)); TRACK = b; }
        return TRACK;
    }
    /** dimension -> chunk (ChunkPos.toLong) -> {packed pos, state id, packed pos, state id, …} */
    static final Map<String, Map<Long, long[]>> INDEX = new HashMap<>();
    static final int MAX_PER_CHUNK = 6000;   // (a lava sea or a fortress wall: plenty to find the nearest)

    /** a chunk arrived from the server: remember what's notable in it (replacing what I knew of it) */
    public static void onChunk(ClientLevel lvl, LevelChunk ch) {
        if (!ON) return;
        BitSet want = track(); long[] buf = new long[256]; int n = 0;
        LevelChunkSection[] secs = ch.getSections();
        int bx = ch.getPos().getMinBlockX(), bz = ch.getPos().getMinBlockZ();
        for (int i = 0; i < secs.length && n < MAX_PER_CHUNK * 2; i++) {
            LevelChunkSection sec = secs[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> want.get(Block.getId(st)))) continue;
            int by = ch.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16 && n < MAX_PER_CHUNK * 2; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = sec.getBlockState(x, y, z); int id = Block.getId(st);
                if (!want.get(id)) continue;
                if (n + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = BlockPos.asLong(bx + x, by + y, bz + z); buf[n++] = id;
            }
        }
        synchronized (INDEX) { INDEX.computeIfAbsent(lvl.dimension().identifier().getPath(), d -> new HashMap<>()).put(((long) (bz >> 4) << 32) | ((bx >> 4) & 0xffffffffL), Arrays.copyOf(buf, n)); }
    }

    /** the memory outlives a client restart (it's mine: the server sent it to me) */
    static final java.nio.file.Path INDEX_FILE = java.nio.file.Path.of("cheat_chunk_index.bin");
    public static void saveIndex() {
        if (!ON) return;
        java.nio.file.Path tmp = java.nio.file.Path.of("cheat_chunk_index.bin.tmp");
        synchronized (INDEX) {
            try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp)))) {
                out.writeInt(INDEX.size());
                for (var d : INDEX.entrySet()) {
                    out.writeUTF(d.getKey()); out.writeInt(d.getValue().size());
                    for (var c : d.getValue().entrySet()) { out.writeLong(c.getKey()); out.writeInt(c.getValue().length); for (long v : c.getValue()) out.writeLong(v); }
                }
            } catch (Exception e) { return; }
        }
        try { java.nio.file.Files.move(tmp, INDEX_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); } catch (Exception ignored) {}
    }
    public static void loadIndex() {
        if (!ON || !java.nio.file.Files.exists(INDEX_FILE)) return;
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(INDEX_FILE)))) {
            int nd = in.readInt();
            synchronized (INDEX) {
                for (int i = 0; i < nd; i++) {
                    String d = in.readUTF(); int nc = in.readInt(); Map<Long, long[]> m = INDEX.computeIfAbsent(d, k -> new HashMap<>());
                    for (int j = 0; j < nc; j++) { long k = in.readLong(); long[] a = new long[in.readInt()]; for (int q = 0; q < a.length; q++) a[q] = in.readLong(); m.put(k, a); }
                }
            }
        } catch (Exception e) { HonestClient.LOG.warn("[honest] couldn't read the chunk index", e); }
    }

    static int remembered(ClientLevel lvl) {
        synchronized (INDEX) { var m = INDEX.get(lvl.dimension().identifier().getPath()); return m == null ? 0 : m.size(); }
    }

    /** visit every block whose state id is in `want`, skipping chunk sections whose palette can't hold one; then the
     *  remembered notable blocks of chunks that are no longer loaded */
    interface Visit { void at(int x, int y, int z, BlockState s); }
    static void each(ClientLevel lvl, BlockPos c, int r, int ymin, int ymax, BitSet want, Visit v) {
        long r2 = (long) r * r;
        List<LevelChunk> live = chunks(lvl, c, Math.min(r, 16 * 40));
        if (r > 16 * 6) {
            Map<Long, long[]> mem;
            synchronized (INDEX) { var m = INDEX.get(lvl.dimension().identifier().getPath()); mem = m == null ? Map.of() : new HashMap<>(m); }
            for (var e : mem.entrySet()) {
                long cp = e.getKey(); int cx = (int) cp, cz = (int) (cp >> 32);
                if (lvl.hasChunk(cx, cz)) continue;   // (loaded: read live below)
                long ddx = cx * 16L + 8 - c.getX(), ddz = cz * 16L + 8 - c.getZ();
                if (ddx * ddx + ddz * ddz > (r + 12L) * (r + 12L)) continue;
                long[] a = e.getValue();
                for (int i = 0; i < a.length; i += 2) {
                    if (!want.get((int) a[i + 1])) continue;
                    int x = BlockPos.getX(a[i]), y = BlockPos.getY(a[i]), z = BlockPos.getZ(a[i]);
                    if (y < ymin || y > ymax) continue;
                    long qx = x - c.getX(), qz = z - c.getZ();
                    if (qx * qx + qz * qz > r2) continue;
                    v.at(x, y, z, Block.stateById((int) a[i + 1]));
                }
            }
        }
        for (LevelChunk ch : live) {
            LevelChunkSection[] secs = ch.getSections();
            int bx = ch.getPos().getMinBlockX(), bz = ch.getPos().getMinBlockZ();
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection sec = secs[i];
                if (sec == null || sec.hasOnlyAir()) continue;
                int by = ch.getSectionYFromSectionIndex(i) << 4;
                if (by + 15 < ymin || by > ymax) continue;
                if (!sec.maybeHas(st -> want.get(Block.getId(st)))) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    if (!want.get(Block.getId(s))) continue;
                    int wx = bx + x, wy = by + y, wz = bz + z;
                    if (wy < ymin || wy > ymax) continue;
                    long ddx = wx - c.getX(), ddz = wz - c.getZ();
                    if (ddx * ddx + ddz * ddz > r2) continue;
                    v.at(wx, wy, wz, s);
                }
            }
        }
    }

    /** a block a player can get at without digging: one of its faces touches air or a non-solid block */
    static boolean exposed(ClientLevel lvl, BlockPos p) {
        if (!lvl.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return true;   // (remembered: can't tell any more)
        for (Direction d : Direction.values()) {
            BlockState n = lvl.getBlockState(p.relative(d));
            if (n.isAir() || (!n.canOcclude() && n.getFluidState().isEmpty())) return true;
        }
        return false;
    }

    static JsonArray pos(BlockPos p) { JsonArray a = new JsonArray(); a.add(p.getX()); a.add(p.getY()); a.add(p.getZ()); return a; }
    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    // ------------------------------------------------------------------ /find
    static final class Cluster { BlockPos near; double d; int n; String block; final List<BlockPos> all = new ArrayList<>(); }

    /** group hits into veins (within `gap` blocks of a member), nearest first */
    static List<Cluster> cluster(List<BlockPos> hits, List<String> names, Vec3 from, int gap) {
        List<Cluster> cs = new ArrayList<>();
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < hits.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> Vec3.atCenterOf(hits.get(i)).distanceTo(from)));
        for (int i : order) {
            BlockPos h = hits.get(i); Cluster in = null;
            for (Cluster c : cs) { if (!c.block.equals(names.get(i))) continue; for (BlockPos m : c.all) if (m.distManhattan(h) <= gap) { in = c; break; } if (in != null) break; }
            if (in == null) { in = new Cluster(); in.near = h; in.d = Vec3.atCenterOf(h).distanceTo(from); in.block = names.get(i); cs.add(in); }
            in.all.add(h); in.n++;
        }
        return cs;
    }

    public static JsonObject find(Minecraft mc, List<String> qs, int r, int max, int ymin, int ymax) {
        LocalPlayer pl = mc.player; ClientLevel lvl = mc.level; JsonObject o = new JsonObject();
        if (pl == null || lvl == null) { o.addProperty("error", "not in world"); return o; }
        BitSet want = ids(qs);
        if (want.isEmpty()) { o.addProperty("error", "no block is called " + qs + " (use ids like iron_ore, oak_log, obsidian, lava)"); return o; }
        List<BlockPos> hits = new ArrayList<>(); List<String> names = new ArrayList<>();
        each(lvl, pl.blockPosition(), r, ymin, ymax, want, (x, y, z, s) -> {
            if (hits.size() < 200_000) { hits.add(new BlockPos(x, y, z)); names.add(Perception.blockName(s)); }
        });
        Vec3 eye = pl.getEyePosition();
        List<Cluster> cs = cluster(hits, names, eye, 2);
        JsonArray arr = new JsonArray();
        for (Cluster c : cs.subList(0, Math.min(max, cs.size()))) {
            JsonObject j = new JsonObject();
            j.addProperty("block", c.block);
            j.add("pos", pos(c.near));
            j.addProperty("dist", r1(c.d));
            j.addProperty("count", c.n);
            j.addProperty("exposed", c.all.stream().anyMatch(p -> exposed(lvl, p)));
            arr.add(j);
        }
        o.add("clusters", arr);
        o.addProperty("total_blocks", hits.size());
        o.addProperty("total_clusters", cs.size());
        o.addProperty("searched", "within " + r + " m, y " + Math.max(ymin, lvl.getMinY()) + ".." + Math.min(ymax, lvl.getMaxY())
                + ": loaded chunks live" + (r > 96 ? " + notable blocks remembered from " + remembered(lvl) + " chunks seen before" : ""));
        return o;
    }

    // ------------------------------------------------------------------ /ents
    public static JsonArray ents(Minecraft mc, double r, String filter, int max) {
        LocalPlayer pl = mc.player; ClientLevel lvl = mc.level; JsonArray out = new JsonArray();
        if (pl == null || lvl == null) return out;
        Vec3 eye = pl.getEyePosition();
        List<Entity> es = new ArrayList<>();
        for (Entity e : lvl.entitiesForRendering()) {
            if (e == pl || e.isRemoved()) continue;
            if (e.distanceTo(pl) > r) continue;
            String t = Perception.entityName(e);
            if (filter != null && !filter.isEmpty()) {
                boolean ok = false;
                for (String f : filter.split(",")) if (f.equals("hostile") ? e instanceof Enemy : t.contains(f) || f.equals("item") && e instanceof ItemEntity) ok = true;
                if (!ok) continue;
            }
            es.add(e);
        }
        es.sort(Comparator.comparingDouble(e -> e.distanceTo(pl)));
        for (Entity e : es.subList(0, Math.min(max, es.size()))) out.add(describe(mc, pl, e, eye));
        return out;
    }

    static JsonObject describe(Minecraft mc, LocalPlayer pl, Entity e, Vec3 eye) {
        JsonObject j = new JsonObject();
        j.addProperty("id", e.getId());
        j.addProperty("type", Perception.entityName(e));
        JsonArray p = new JsonArray(); p.add(r1(e.getX())); p.add(r1(e.getY())); p.add(r1(e.getZ())); j.add("pos", p);
        j.addProperty("dist", r1(e.distanceTo(pl)));
        if (e instanceof LivingEntity le) {
            j.addProperty("hp", r1(le.getHealth()));
            j.addProperty("max_hp", r1(le.getMaxHealth()));
            if (le.isBaby()) j.addProperty("baby", true);
            JsonObject gear = new JsonObject();
            for (var sl : net.minecraft.world.entity.EquipmentSlot.values()) {
                var st = le.getItemBySlot(sl);
                if (!st.isEmpty() && sl != net.minecraft.world.entity.EquipmentSlot.BODY && sl != net.minecraft.world.entity.EquipmentSlot.SADDLE)
                    gear.addProperty(sl.getName(), Perception.itemName(st));
            }
            if (gear.size() > 0) j.add("gear", gear);
        }
        if (e instanceof Enemy) j.addProperty("hostile", true);
        if (e instanceof Mob m && m.isAggressive()) j.addProperty("aggressive", true);
        if (e instanceof ItemEntity ie) { j.addProperty("item", Perception.itemName(ie.getItem())); j.addProperty("count", ie.getItem().getCount()); }
        if (e instanceof net.minecraft.world.entity.player.Player op) j.addProperty("name", op.getName().getString());
        if (e instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwelling(1f) > 0.01) j.addProperty("WARNING", "swelling — about to explode");
        j.addProperty("visible", Perception.anyPointVisible(mc.level, eye, e.getBoundingBox()));
        return j;
    }

    // ------------------------------------------------------------------ /scan: one screen of "what's around"
    static final String[][] WANT = {
        // overworld
        {"diamond_ore", "iron_ore", "gold_ore", "coal_ore", "redstone_ore", "lapis_ore", "emerald_ore", "obsidian", "crying_obsidian",
         "lava", "gravel", "chest", "spawner", "bell", "hay_block", "end_portal_frame", "nether_portal", "log", "bed"},
        // the_nether
        {"nether_bricks", "spawner", "chest", "gilded_blackstone", "gold_block", "ancient_debris", "nether_gold_ore", "obsidian",
         "crying_obsidian", "nether_portal", "warped_nylium", "soul_sand", "gravel", "lava"},
        // the_end
        {"end_portal", "end_gateway", "obsidian", "bedrock", "end_stone", "chorus_flower"}
    };
    static final Set<String> MOBS_TO_LIST = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "villager", "iron_golem", "enderman", "blaze",
            "piglin", "piglin_brute", "wither_skeleton", "ghast", "ender_dragon", "end_crystal", "horse", "cod", "salmon");

    public static String scan(Minecraft mc, int r) {
        LocalPlayer pl = mc.player; ClientLevel lvl = mc.level;
        if (pl == null || lvl == null) return "not in world";
        String dim = lvl.dimension().identifier().getPath();
        String[] want = dim.equals("the_nether") ? WANT[1] : dim.equals("the_end") ? WANT[2] : WANT[0];
        StringBuilder sb = new StringBuilder();
        BlockPos me = pl.blockPosition(); Vec3 eye = pl.getEyePosition();
        sb.append(String.format("%s at %s, %s (within %d m: loaded chunks + what I remember of %d chunks)\n", dim, me.toShortString(), igt(lvl), r, remembered(lvl)));
        // one pass for all the categories
        BitSet all = new BitSet(); Map<Integer, Integer> cat = new HashMap<>();
        for (int k = 0; k < want.length; k++) {
            BitSet b = ids(List.of(want[k]));
            for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) if (!cat.containsKey(i)) { cat.put(i, k); all.set(i); }
        }
        int[] count = new int[want.length]; BlockPos[] near = new BlockPos[want.length]; double[] nd = new double[want.length];
        Arrays.fill(nd, Double.MAX_VALUE);
        int[] exposedCount = new int[want.length];
        each(lvl, me, r, lvl.getMinY(), lvl.getMaxY(), all, (x, y, z, s) -> {
            int k = cat.get(Block.getId(s));
            count[k]++;
            double d = eye.distanceToSqr(x + .5, y + .5, z + .5);
            if (d < nd[k]) { nd[k] = d; near[k] = new BlockPos(x, y, z); }
        });
        sb.append("blocks (total in range · nearest · dist):\n");
        for (int k = 0; k < want.length; k++) {
            if (count[k] == 0) continue;
            sb.append(String.format("  %-18s %6d  nearest %s  %.0f m%s\n", want[k], count[k], near[k].toShortString(), Math.sqrt(nd[k]),
                    exposed(lvl, near[k]) ? "" : " (buried)"));
        }
        List<String> none = new ArrayList<>();
        for (int k = 0; k < want.length; k++) if (count[k] == 0) none.add(want[k]);
        if (!none.isEmpty()) sb.append("  none loaded: ").append(String.join(", ", none)).append('\n');
        // creatures: hostiles near me, and the useful kinds anywhere loaded
        Map<String, int[]> tally = new TreeMap<>(); Map<String, Entity> nearest = new HashMap<>();
        List<Entity> threats = new ArrayList<>();
        for (Entity e : lvl.entitiesForRendering()) {
            if (e == pl) continue;
            String t = Perception.entityName(e);
            if (e instanceof Enemy && e.distanceTo(pl) < 24) threats.add(e);
            if (!MOBS_TO_LIST.contains(t)) continue;
            tally.computeIfAbsent(t, x -> new int[1])[0]++;
            Entity n = nearest.get(t);
            if (n == null || e.distanceTo(pl) < n.distanceTo(pl)) nearest.put(t, e);
        }
        threats.sort(Comparator.comparingDouble(e -> e.distanceTo(pl)));
        sb.append("hostiles within 24 m: ");
        if (threats.isEmpty()) sb.append("none\n");
        else {
            sb.append('\n');
            for (Entity e : threats.subList(0, Math.min(8, threats.size())))
                sb.append(String.format("  #%d %s %.0f m at %s%s%s\n", e.getId(), Perception.entityName(e), e.distanceTo(pl), e.blockPosition().toShortString(),
                        e instanceof LivingEntity le ? String.format(" %.0f hp", le.getHealth()) : "",
                        Perception.anyPointVisible(lvl, eye, e.getBoundingBox()) ? " (in sight)" : " (behind cover)"));
        }
        if (!tally.isEmpty()) {
            sb.append("creatures loaded: ");
            List<String> parts = new ArrayList<>();
            for (var e : tally.entrySet()) {
                Entity n = nearest.get(e.getKey());
                parts.add(String.format("%s×%d (nearest #%d %.0f m)", e.getKey(), e.getValue()[0], n.getId(), n.distanceTo(pl)));
            }
            sb.append(String.join(", ", parts)).append('\n');
        }
        return sb.toString();
    }

    /** every block in a box (at most 32 per side): for planning a build or reading a structure */
    public static JsonObject box(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2) {
        JsonObject o = new JsonObject(); ClientLevel lvl = mc.level;
        int ax = Math.min(x1, x2), ay = Math.min(y1, y2), az = Math.min(z1, z2);
        int bx = Math.min(Math.max(x1, x2), ax + 31), by = Math.min(Math.max(y1, y2), ay + 31), bz = Math.min(Math.max(z1, z2), az + 31);
        JsonObject legend = new JsonObject(); Map<String, String> sym = new LinkedHashMap<>();
        String pool = "#ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789$%&*+=?";
        JsonArray layers = new JsonArray();
        for (int y = by; y >= ay; y--) {
            StringBuilder sb = new StringBuilder();
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) {
                    BlockState st = lvl.getBlockState(new BlockPos(x, y, z));
                    String n = Perception.blockName(st);
                    char ch;
                    if (st.isAir()) ch = '.';
                    else if (n.equals("water") || n.equals("flowing_water")) ch = '~';
                    else if (n.contains("lava")) ch = '!';
                    else { String c = sym.get(n); if (c == null) { c = String.valueOf(pool.charAt(Math.min(sym.size(), pool.length() - 1))); sym.put(n, c); } ch = c.charAt(0); }
                    sb.append(ch);
                }
                sb.append('\n');
            }
            JsonObject l = new JsonObject(); l.addProperty("y", y); l.addProperty("rows", sb.toString()); layers.add(l);
        }
        for (var e : sym.entrySet()) legend.addProperty(e.getValue(), e.getKey());
        o.add("legend", legend); o.add("layers", layers);
        o.addProperty("origin", "x " + ax + ".." + bx + " (left to right), z " + az + ".." + bz + " (top to bottom, north up)");
        return o;
    }

    /** a damage-over-time tick (withering, poison) with HP still above 6: in cheat mode, not worth waking the brain
     *  for — it interrupted eating the very food that would have outlasted it */
    public static boolean tick(LocalPlayer p, float hp) {
        String c = Hurt.cause(p);
        return hp > 6 && c != null && (c.startsWith("withering") || c.startsWith("magic"));
    }

    /** in-game time played: what a speedrun is timed by (the world stands still while the brain thinks) */
    public static String igt(ClientLevel lvl) {
        long t = lvl.getGameTime(); long s = t / 20;
        return String.format("IGT %d:%02d:%02d (%d ticks), day %d", s / 3600, s / 60 % 60, s % 60, t, lvl.getOverworldClockTime() / 24000 + 1);
    }

    /** the compact block observe() carries in cheat mode */
    public static JsonObject brief(Minecraft mc) {
        JsonObject o = new JsonObject();
        LocalPlayer pl = mc.player; ClientLevel lvl = mc.level;
        if (pl == null || lvl == null) return o;
        o.addProperty("mode", "cheat");
        o.addProperty("igt", igt(lvl));
        o.addProperty("game_ticks", lvl.getGameTime());
        long dt = lvl.getOverworldClockTime() % 24000L;
        o.addProperty("day_time", dt);
        Vec3 eye = pl.getEyePosition();
        JsonArray th = new JsonArray();
        List<Entity> es = new ArrayList<>();
        for (Entity e : lvl.entitiesForRendering()) if (e != pl && e instanceof Enemy && e.distanceTo(pl) < 20) es.add(e);
        es.sort(Comparator.comparingDouble(e -> e.distanceTo(pl)));
        for (Entity e : es.subList(0, Math.min(6, es.size()))) th.add(describe(mc, pl, e, eye));
        o.add("threats_20m", th);
        return o;
    }

    /** cheat mode: the block truth from the loaded chunk, null if not loaded */
    public static BlockState real(int x, int y, int z) {
        var lvl = Minecraft.getInstance().level;
        if (lvl == null || !lvl.hasChunk(x >> 4, z >> 4)) return null;
        if (y < lvl.getMinY() || y > lvl.getMaxY()) return null;
        return lvl.getBlockState(new BlockPos(x, y, z));
    }
}
