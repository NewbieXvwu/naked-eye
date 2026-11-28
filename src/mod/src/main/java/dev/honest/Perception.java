package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Builds the agent's observation using only what a human at this camera could perceive.
 * There are no per-mob or per-block rules here: visibility is geometry + light, and entity
 * appearance is whatever the vanilla renderer extracted into its RenderState.
 */
public final class Perception {
    private Perception() {}

    public static final KnownMap MAP = new KnownMap();

    // ---- tunables (all "physical" constants of a human player, not game rules) ----
    static final int RAY_COLS = 48, RAY_ROWS = 27;   // sight sampling grid over the field of view
    static final double MAX_SIGHT = 64.0;            // capped by render distance anyway
    static final double NOMINAL_SCREEN_PX = 1080.0;  // a human's monitor, vertical pixels
    static final double MIN_PX_TO_NOTICE = 4.0;      // smaller than this you just don't notice it
    static final double MIN_PX_FOR_DETAIL = 28.0;    // smaller than this you only know "a zombie, over there"

    /** Field names in render states that are renderer plumbing rather than appearance. */
    static final Set<String> PLUMBING = Set.of(
            "x", "y", "z", "distanceToCameraSq", "lightCoords", "outlineColor", "shadowRadius", "shadowPieces",
            "leashStates", "passengerOffset", "nameTagAttachment", "ageInTicks", "partialTicks", "boundingBoxWidth",
            "boundingBoxHeight", "eyeHeight", "walkAnimationPos", "flightHistory", "beamOffset", "entityType",
            "ticksSinceKineticHitFeedback", "wornHeadAnimationPos", "flapTime", "isDiscrete",
            "maxCrossbowChargeDuration", "elytraRotX", "elytraRotY", "elytraRotZ", "attackArm", "mainArm",
            "speedValue", "attackTime", "ticksUsingItem", "seed", "bobOffset", "spin");

    // --------------------------------------------------------------------------------------
    // Sight sweep: called every few client ticks, accumulates the known map.
    // --------------------------------------------------------------------------------------
    static Map<String, Object[]> lastViewBlocks = new HashMap<>(); // name -> {count, nearestPos, nearestDist}
    static final Random RNG = new Random();
    /** A freshly constructed render state of each class = "nothing special to see". */
    static final Map<Class<?>, Object> DEFAULTS = new HashMap<>();

    public static void sweep(Minecraft mc) {
        LocalPlayer pl = mc.player;
        ClientLevel lvl = mc.level;
        if (pl == null || lvl == null) return;
        Vec3 eye = pl.getEyePosition();
        double vfov = Math.toRadians(mc.options.fov().get());
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double tanV = Math.tan(vfov / 2), tanH = tanV * aspect;
        Vec3 fwd = pl.getViewVector(1f).normalize();
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = fwd.cross(worldUp);
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        double sight = Math.min(MAX_SIGHT, mc.options.getEffectiveRenderDistance() * 16.0);

        Map<String, Object[]> view = new HashMap<>();
        // how far a 1-block face is discernible at each raw light level, for the current Brightness slider
        double slider = Luma.slider(), vfovDeg = mc.options.fov().get();
        final double[] faceRange = new double[16];
        for (int l = 0; l < 16; l++) faceRange[l] = Math.max(2.0, Luma.maxDistance(1.0, Luma.perceived(l, slider), vfovDeg));
        // jitter the sampling grid every sweep (like saccades) so coverage accumulates over time
        double jr = RNG.nextDouble(), jc = RNG.nextDouble();
        // peripheral grid + a dense fovea where you're looking (±5°): what you look straight at, you see
        List<double[]> rays = new ArrayList<>(RAY_ROWS * RAY_COLS + 81);
        for (int r = 0; r < RAY_ROWS; r++) for (int c = 0; c < RAY_COLS; c++)
            rays.add(new double[]{((c + jc) / RAY_COLS * 2 - 1) * tanH, ((r + jr) / RAY_ROWS * 2 - 1) * tanV});
        double fj = RNG.nextDouble(), fk = RNG.nextDouble();
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++)
            rays.add(new double[]{((c + fj) / 9 * 2 - 1) * 0.0875, ((r + fk) / 9 * 2 - 1) * 0.0875});
        final boolean eyeWet = pl.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        {
            for (double[] ray : rays) {
                double h = ray[0], v = ray[1];
                Vec3 dir = fwd.add(right.scale(h)).add(up.scale(-v)).normalize();
                final int[] water = {0};
                final BlockPos.MutableBlockPos prev = BlockPos.containing(eye).mutable();
                Vis.march(lvl, eye, dir, sight, (p, s, dist) -> {
                    // light is judged where it lands: the open cell in front of the face we hit
                    int rawL = lvl.getMaxLocalRawBrightness(Vis.opaque(s) ? prev : p);
                    boolean lit = dist <= faceRange[Math.min(15, Math.max(0, rawL))];
                    if (Vis.isAirish(s)) { MAP.see(p, s); prev.set(p); return true; }
                    prev.set(p);
                    if (!lit) return !Vis.opaque(s); // can't make it out; dark opaque stops sight
                    MAP.see(p, s);
                    if (!s.getFluidState().isEmpty() && !Vis.opaque(s)) { // water: murky
                        // a kelp or seagrass plant stands in water but is still a plant you can see
                        if (!s.is(net.minecraft.world.level.block.Blocks.WATER)) tally(view, s, p, dist);
                        else if (water[0] == 0 && !eyeWet) tally(view, s, p, dist); // the surface you see (still or flowing)
                        // from the air you see a few blocks into it; with your head under, ~12 m before the fog
                        return ++water[0] < (eyeWet ? 14 : 6);
                    }
                    if (Vis.opaque(s) || !s.getCollisionShape(lvl, p).isEmpty()) {
                        tally(view, s, p, dist);
                    }
                    return !Vis.opaque(s);
                });
            }
        }
        // touch / peripheral body sense: the blocks you are standing in and on
        BlockPos feet = pl.blockPosition();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos p = feet.offset(dx, dy, dz);
            MAP.see(p, lvl.getBlockState(p));
        }
        lastViewBlocks = view;
        try { Sightings.update(mc, pl, entities(mc, pl, lvl, true)); } catch (Throwable ignored) {}
    }

    private static void tally(Map<String, Object[]> view, BlockState s, BlockPos p, double dist) {
        String name = blockName(s);
        Object[] e = view.get(name);
        if (e == null) view.put(name, new Object[]{1, p.immutable(), dist});
        else {
            e[0] = (Integer) e[0] + 1;
            if (dist < (Double) e[2]) { e[1] = p.immutable(); e[2] = dist; }
        }
    }

    public static String blockName(BlockState s) {
        String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        // still vs flowing liquid is visible (flat surface vs sloped, streaming texture) and matters for buckets
        if (!s.getFluidState().isEmpty() && !s.getFluidState().isSource() && (n.equals("water") || n.equals("lava"))) return "flowing_" + n;
        return n;
    }

    // --------------------------------------------------------------------------------------
    // Observation
    // --------------------------------------------------------------------------------------
    public static JsonObject observe(Minecraft mc) {
        JsonObject o = new JsonObject();
        LocalPlayer pl = mc.player;
        ClientLevel lvl = mc.level;
        if (pl == null || lvl == null) { o.addProperty("status", "not_in_world"); return o; }
        o.add("self", self(mc, pl, lvl));
        o.add("crosshair", crosshair(mc, pl));
        if (Cheat.ON) {   // everything the client knows of, exact, nearest first — and the speedrun clock
            o.add("cheat", Cheat.brief(mc));
            o.add("entities", Cheat.ents(mc, 32, null, 16));
        } else {
            o.add("entities", entities(mc, pl, lvl, true));
            o.add("recently_lost_sight_of", Sightings.lost(mc, pl));
        }
        JsonArray on = Talk.online(mc);
        if (on.size() > 1) o.add("players_online", on);   // (the Tab list: only worth showing when I'm not alone)
        o.add("blocks_in_view", blocksInView(pl));
        o.addProperty("known_blocks", MAP.size());
        return o;
    }

    static JsonObject self(Minecraft mc, LocalPlayer pl, ClientLevel lvl) {
        JsonObject s = new JsonObject();
        // HUD: hearts are drawn in half-heart steps, i.e. integer HP
        s.addProperty("health", (int) Math.ceil(pl.getHealth()));
        s.addProperty("max_health", (int) Math.ceil(pl.getMaxHealth()));
        s.addProperty("food", pl.getFoodData().getFoodLevel());      // drawn in half-shank steps
        s.addProperty("armor", pl.getArmorValue());
        if (pl.getAirSupply() < pl.getMaxAirSupply())                 // bubbles only appear when used
            s.addProperty("air_bubbles", Math.max(0, (int) Math.ceil(pl.getAirSupply() / 30.0)));
        s.addProperty("xp_level", pl.experienceLevel);
        if (pl.getTeam() instanceof net.minecraft.world.scores.PlayerTeam t) s.addProperty("team", t.getName());
        // F3 screen: position, facing, biome, light, day count
        JsonArray pos = new JsonArray();
        pos.add(round(pl.getX(), 2)); pos.add(round(pl.getY(), 2)); pos.add(round(pl.getZ(), 2));
        s.add("pos", pos);
        s.addProperty("yaw", round(Mth.wrapDegrees(pl.getYRot()), 1));
        s.addProperty("pitch", round(pl.getXRot(), 1));
        s.addProperty("facing", pl.getDirection().getName());
        s.addProperty("dimension", lvl.dimension().toString().replaceAll(".*/ ?", "").replace("]", "").trim());
        s.addProperty("biome", lvl.getBiome(pl.blockPosition()).getRegisteredName().replace("minecraft:", ""));
        int rawHere = lvl.getMaxLocalRawBrightness(pl.blockPosition());
        s.addProperty("light", rawHere);
        s.addProperty("looks_pct", (int) Math.round(100 * Luma.perceived(rawHere, Luma.slider()))); // how bright your surroundings look
        long dt = lvl.getOverworldClockTime() % 24000L;
        s.addProperty("time", dt < 12000 ? "day" : dt < 13800 ? "sunset" : dt < 22200 ? "night" : "sunrise");
        // the sky is plain to see: rain streaks, a dark storm sky, lightning. Thunder = mobs spawn even by day
        if (lvl.getThunderLevel(1f) > 0.5f) s.addProperty("weather", "THUNDERSTORM (dark enough for monsters to spawn in the open, even by day)");
        else if (lvl.getRainLevel(1f) > 0.3f) s.addProperty("weather", "raining");
        s.addProperty("on_ground", pl.onGround());
        JsonObject motion = Motion.self(pl);
        if (motion != null) s.add("motion", motion);   // (as of the last tick the world ran: it holds while frozen)
        JsonObject fall = Fall.describe(pl);
        if (fall != null) s.add("FALLING", fall);   // (HP is still full mid-fall; the landing is what kills)
        if (pl.isInWater()) s.addProperty("in_water", true);
        if (pl.isOnFire()) s.addProperty("on_fire", true);
        if (mc.gui.screen() != null) s.addProperty("open_screen", mc.gui.screen().getTitle().getString().isEmpty() ? "screen" : mc.gui.screen().getTitle().getString());
        if (pl.isDeadOrDying()) s.addProperty("dead", true);
        s.addProperty("selected_slot", pl.getInventory().getSelectedSlot());
        if (Pause.paused != null && !"idle".equals(Pause.pausedKind)) s.addProperty("paused", Pause.paused);   // (the thinking freeze is normal, not news)
        s.addProperty("pause_allowed", Pause.allow);
        JsonArray inv = new JsonArray();
        for (int i = 0; i < 36; i++) {
            ItemStack st = pl.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            JsonObject it = new JsonObject();
            it.addProperty("slot", i);
            it.addProperty("item", itemName(st));
            it.addProperty("count", st.getCount());
            if (st.isDamageableItem()) it.addProperty("durability_pct", (int) (100 - 100.0 * st.getDamageValue() / st.getMaxDamage()));
            inv.add(it);
        }
        s.add("inventory", inv);
        // an open container is on screen: everything in its slots is visible
        if (pl.containerMenu != pl.inventoryMenu) {
            JsonObject c = new JsonObject();
            var menu = pl.containerMenu;
            c.addProperty("kind", menu.getClass().getSimpleName().replace("Menu", "").toLowerCase(java.util.Locale.ROOT));
            JsonArray slots = new JsonArray();
            for (var sl : menu.slots) {
                JsonObject j = new JsonObject();
                j.addProperty("slot", sl.index);
                if (sl.container == pl.getInventory()) j.addProperty("your_inv_slot", sl.getContainerSlot());
                ItemStack st = sl.getItem();
                if (st.isEmpty()) { if (sl.container == pl.getInventory()) continue; j.addProperty("item", "empty"); }
                else { j.addProperty("item", itemName(st)); j.addProperty("count", st.getCount()); }
                slots.add(j);
            }
            c.add("slots", slots);
            if (!menu.getCarried().isEmpty()) c.addProperty("on_cursor", itemName(menu.getCarried()) + " x" + menu.getCarried().getCount());
            if (menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu f) { // the flame and arrow you see
                c.addProperty("lit", f.isLit());
                c.addProperty("flame_pct", Math.round(f.getLitProgress() * 100));
                c.addProperty("arrow_pct", Math.round(f.getBurnProgress() * 100));
            }
            s.add("container", c);
        }
        JsonObject eq = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack st = pl.getItemBySlot(slot);
            if (!st.isEmpty()) eq.addProperty(slot.getName(), itemName(st));
        }
        if (eq.size() > 0) s.add("equipment", eq);
        return s;
    }

    static JsonObject crosshair(Minecraft mc, LocalPlayer pl) {
        JsonObject c = new JsonObject();
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
            BlockState s = mc.level.getBlockState(bh.getBlockPos());
            c.addProperty("block", blockName(s));
            JsonArray p = new JsonArray();
            p.add(bh.getBlockPos().getX()); p.add(bh.getBlockPos().getY()); p.add(bh.getBlockPos().getZ());
            c.add("pos", p);
            c.addProperty("face", bh.getDirection().getName());
        } else if (hr instanceof EntityHitResult eh) {
            c.addProperty("entity", eh.getEntity().getId());
            c.addProperty("type", entityName(eh.getEntity()));
        } else c.addProperty("nothing", true);
        return c;
    }

    // --------------------------------------------------------------------------------------
    // Entities
    // --------------------------------------------------------------------------------------
    static JsonArray entities(Minecraft mc, LocalPlayer pl, ClientLevel lvl, boolean honest) {
        Vec3 eye = pl.getEyePosition();
        Vec3 fwd = pl.getViewVector(1f).normalize();
        double vfov = Math.toRadians(mc.options.fov().get());
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double tanV = Math.tan(vfov / 2), tanH = tanV * aspect;
        Vec3 right = fwd.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        double sight = Math.min(MAX_SIGHT, mc.options.getEffectiveRenderDistance() * 16.0);

        List<JsonObject> out = new ArrayList<>();
        for (Entity e : lvl.entitiesForRendering()) {
            if (e == pl || Review.isMe(mc, e)) continue;   // (in review: the agent's own body in the replay)
            AABB bb = e.getBoundingBox();
            Vec3 center = bb.getCenter();
            double dist = center.distanceTo(eye);
            // another player's name tag: the game draws it up to 64 m away, through walls and in the dark — unless
            // they sneak (then only where you could see them anyway) or their team hides it from me
            boolean tag = e instanceof net.minecraft.world.entity.player.Player op && nameTagShown(pl, op, dist);
            boolean tagOnly = false;
            if (honest) {
                if (dist > sight) continue;
                if (e.isInvisible() && !tag) continue;
                if (!inFov(center.subtract(eye), fwd, right, up, tanH, tanV, bb)) continue;
                if (!anyPointVisible(lvl, eye, bb)) { if (tag && !e.isDiscrete()) tagOnly = true; else continue; }
            }
            double size = Math.max(bb.getYsize(), Math.max(bb.getXsize(), bb.getZsize()));
            double px = Math.toDegrees(2 * Math.atan2(size / 2, Math.max(dist, 0.1))) / Math.toDegrees(vfov) * NOMINAL_SCREEN_PX;
            // how bright does it look? use the light the renderer itself shades it with (covers
            // always-lit mobs like blazes without special cases), darkened sky as the renderer does
            EntityRenderState rs = extract(mc, e);
            int packed = rs != null ? rs.lightCoords : 0xF000F0;
            double L = Luma.perceived(Luma.raw(Luma.blockOf(packed), Luma.skyOf(packed), lvl.getSkyDarken()), Luma.slider());
            boolean noticed = px >= Luma.pxToNotice(L) || dist <= 1.5;
            boolean eyesOnly = false;
            if (honest && !noticed && tag) tagOnly = true;   // (too dark or too small, but the name floats there)
            else if (honest && !noticed) {
                // emissive eye layers (spiders, endermen) glow at full brightness in the dark
                if (hasGlowingEyes(mc, e) && px * 0.12 >= MIN_PX_TO_NOTICE) eyesOnly = true;
                else continue;
            }

            JsonObject j = new JsonObject();
            j.addProperty("id", e.getId());
            j.addProperty("type", entityName(e));
            if (tag) {
                j.addProperty("name", e.getName().getString());
                if (e.getTeam() instanceof net.minecraft.world.scores.PlayerTeam t) {
                    j.addProperty("team", t.getName());
                    if (pl.getTeam() != null) j.addProperty("teammate", t.isAlliedTo(pl.getTeam()));
                }
            }
            Vec3 rel = center.subtract(eye);
            double bearing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-rel.x, rel.z)) - pl.getYRot());
            double elev = Math.toDegrees(Math.atan2(rel.y, Math.sqrt(rel.x * rel.x + rel.z * rel.z)));
            j.addProperty("dist", honest ? perceivedDistance(dist) : round(dist, 2));
            // direction is what eyes are GOOD at (it's where on the screen it is): give it precisely.
            // distance is what they're bad at: that stays a ~10% guess
            j.addProperty("bearing", Math.round(bearing * 10) / 10.0);   // + right, - left of where you face
            j.addProperty("elevation", Math.round(elev * 10) / 10.0);
            // approx world position implied by (bearing, distance) — no information beyond those two
            double pd = honest ? perceivedDistance(dist) : dist;
            Vec3 est = eye.add(rel.normalize().scale(pd));
            JsonArray ap = new JsonArray();
            ap.add(Math.round(est.x)); ap.add(Math.round(est.y - bb.getYsize() / 2)); ap.add(Math.round(est.z));
            j.add("approx_pos", ap);
            if (honest) { JsonObject mv = Motion.seen(e, eye, dist); if (mv != null) j.add("moving", mv); }
            if (tagOnly) {
                j.addProperty("detail", "only_name_tag_visible");   // (through a wall, or too dark to see them)
            } else if (eyesOnly) {
                j.addProperty("detail", "only_glowing_eyes_visible");
            } else if (!honest || px >= Luma.pxForDetail(L)) {
                JsonObject look = renderStateAppearance(mc, e, rs);
                if (look != null && look.size() > 0) j.add("looks", look);
                if (e instanceof LivingEntity le) {
                    JsonObject gear = new JsonObject();
                    for (EquipmentSlot sl : EquipmentSlot.values()) {
                        if (sl == EquipmentSlot.BODY || sl == EquipmentSlot.SADDLE) continue;
                        ItemStack st = le.getItemBySlot(sl);
                        if (!st.isEmpty()) gear.addProperty(sl.getName(), itemName(st) + (st.hasFoil() ? " (glinting)" : ""));
                    }
                    if (gear.size() > 0) j.add("gear", gear);
                }
                if (e instanceof ItemEntity ie) j.addProperty("item", itemName(ie.getItem())); // the icon you see
            } else j.addProperty("detail", L < 0.5 ? "too_dark_to_make_out" : "too_far_to_make_out");
            // a creeper about to blow swells up and flashes WHITE — the one thing you can't miss even far off or in the dark
            if (e instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwelling(1f) > 0.01)
                j.addProperty("WARNING", String.format("swelling and flashing — about to explode (%.0f%% of the fuse)", 100 * cr.getSwelling(1f)));
            if (!honest && e instanceof LivingEntity le) { // debug only: the truth
                j.addProperty("TRUE_health", round(le.getHealth(), 1));
            }
            out.add(j);
        }
        out.sort(Comparator.comparingDouble(a -> a.get("dist").getAsDouble()));
        JsonArray arr = new JsonArray();
        out.forEach(arr::add);
        return arr;
    }

    static boolean inFov(Vec3 rel, Vec3 fwd, Vec3 right, Vec3 up, double tanH, double tanV, AABB bb) {
        double z = rel.dot(fwd);
        double rad = Math.max(bb.getXsize(), bb.getYsize()) / 2;
        if (z <= -rad) return false;
        double zz = Math.max(z, 0.05);
        double xx = Math.abs(rel.dot(right)) - rad, yy = Math.abs(rel.dot(up)) - rad;
        return xx / zz <= tanH && yy / zz <= tanV;
    }

    /** vanilla's rule for drawing another player's name tag (LivingEntityRenderer.shouldShowName, 64 m) */
    static boolean nameTagShown(LocalPlayer me, net.minecraft.world.entity.player.Player p, double dist) {
        if (dist > 64 || p.isInvisibleTo(me) || p.isSpectator()) return false;
        var them = p.getTeam(); var mine = me.getTeam();
        if (them == null) return true;
        return switch (them.getNameTagVisibility()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> mine != null && them.isAlliedTo(mine);
            case HIDE_FOR_OWN_TEAM -> mine == null || !them.isAlliedTo(mine);
        };
    }

    static boolean anyPointVisible(ClientLevel lvl, Vec3 eye, AABB bb) {
        Vec3 c = bb.getCenter();
        AABB s = bb.deflate(bb.getXsize() * 0.15, bb.getYsize() * 0.1, bb.getZsize() * 0.15);
        Vec3[] pts = {c, new Vec3(c.x, s.maxY, c.z), new Vec3(c.x, s.minY, c.z),
                new Vec3(s.minX, s.maxY, s.minZ), new Vec3(s.maxX, s.maxY, s.maxZ),
                new Vec3(s.minX, s.minY, s.maxZ), new Vec3(s.maxX, s.minY, s.minZ)};
        for (Vec3 p : pts) if (Vis.clear(lvl, eye, p)) return true;
        return false;
    }

    /** Humans judge distance to within roughly 10%; nearby things a bit better. */
    static double perceivedDistance(double d) {
        if (d < 4) return round(d * 2, 0) / 2.0;
        double mag = Math.pow(10, Math.floor(Math.log10(d)) - 1);
        return round(Math.round(d / mag) * mag, 1);
    }

    /**
     * Generic appearance: ask the vanilla renderer to extract its RenderState for this entity,
     * then serialize every public field that isn't renderer plumbing. No per-entity knowledge.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static EntityRenderState extract(Minecraft mc, Entity e) {
        try {
            EntityRenderer r = mc.getEntityRenderDispatcher().getRenderer(e);
            return (EntityRenderState) r.createRenderState(e, 1.0f);
        } catch (Throwable t) { return null; }
    }

    static final Map<Class<?>, Boolean> EYES = new HashMap<>();

    /** Generic: does this entity's renderer carry an emissive eyes layer? (no per-mob list) */
    static boolean hasGlowingEyes(Minecraft mc, Entity e) {
        try {
            Object r = mc.getEntityRenderDispatcher().getRenderer(e);
            return EYES.computeIfAbsent(r.getClass(), c -> {
                if (!(r instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?, ?>)) return false;
                try {
                    Field f = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredField("layers");
                    f.setAccessible(true);
                    for (Object layer : (List<?>) f.get(r))
                        if (layer instanceof net.minecraft.client.renderer.entity.layers.EyesLayer<?, ?>) return true;
                } catch (Exception ignored) {}
                return false;
            });
        } catch (Throwable t) { return false; }
    }

    static JsonObject renderStateAppearance(Minecraft mc, Entity e, EntityRenderState st) {
        try {
            if (st == null) st = extract(mc, e);
            Object def = DEFAULTS.computeIfAbsent(st.getClass(), c -> {
                try { return c.getDeclaredConstructor().newInstance(); } catch (Exception ex) { return null; }
            });
            JsonObject j = new JsonObject();
            for (Class<?> k = st.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (!Modifier.isPublic(m) || Modifier.isStatic(m) || PLUMBING.contains(f.getName())) continue;
                    if (j.has(f.getName())) continue;
                    Object v = f.get(st);
                    if (v == null) continue;
                    if (def != null) { // only report what differs from "a default, unremarkable one"
                        Object dv = f.get(def);
                        if (Objects.equals(v, dv)) continue;
                        if (v instanceof Number n && dv instanceof Number dn && Math.abs(n.doubleValue() - dn.doubleValue()) < 0.005) continue;
                    }
                    String key = f.getName();
                    if (v instanceof Boolean b) { j.addProperty(key, b); }
                    else if (v instanceof Float || v instanceof Double) {
                        double d = round(((Number) v).doubleValue(), 2);
                        if (Double.isFinite(d)) j.addProperty(key, d);
                    }
                    else if (v instanceof Integer || v instanceof Long) { j.addProperty(key, ((Number) v).longValue()); }
                    else if (v instanceof Enum<?> en) j.addProperty(key, en.name().toLowerCase(Locale.ROOT));
                    else if (v instanceof Component c) { String s = c.getString(); if (!s.isEmpty()) j.addProperty(key, s); }
                    else if (v instanceof String s) { if (!s.isEmpty()) j.addProperty(key, s); }
                    // anything else (item render states, models, lists) is drawn but not describable as a value
                }
            }
            return j;
        } catch (Throwable t) {
            JsonObject j = new JsonObject();
            j.addProperty("render_state_error", t.getClass().getSimpleName());
            return j;
        }
    }

    static JsonArray blocksInView(LocalPlayer pl) {
        List<Map.Entry<String, Object[]>> es = new ArrayList<>(lastViewBlocks.entrySet());
        es.sort(Comparator.comparingDouble(e -> (Double) e.getValue()[2]));
        JsonArray a = new JsonArray();
        for (var e : es) {
            JsonObject j = new JsonObject();
            j.addProperty("block", e.getKey());
            j.addProperty("seen_faces", (Integer) e.getValue()[0]);
            BlockPos p = (BlockPos) e.getValue()[1];
            JsonArray pa = new JsonArray(); pa.add(p.getX()); pa.add(p.getY()); pa.add(p.getZ());
            j.add("nearest", pa);
            j.addProperty("dist", round((Double) e.getValue()[2], 1));
            a.add(j);
        }
        return a;
    }

    // --------------------------------------------------------------------------------------
    // Top-down sketch of the KNOWN map (what the agent remembers seeing)
    // --------------------------------------------------------------------------------------
    public static String sketch(Minecraft mc, int radius) {
        LocalPlayer pl = mc.player;
        if (pl == null) return "not in world";
        int px = pl.getBlockX(), py = pl.getBlockY(), pz = pl.getBlockZ();
        StringBuilder sb = new StringBuilder();
        sb.append("north is up. @=you  ?=never seen  .=walkable known ground (digit=height vs you if !=0)\n");
        sb.append("#=wall/cliff  ~=water  !=lava/fire  T=tree  ^=drop >3  ,=plants/snow  b=interesting block\n");
        for (int z = pz - radius; z <= pz + radius; z++) {
            for (int x = px - radius; x <= px + radius; x++) {
                if (x == px && z == pz) { sb.append('@'); continue; }
                sb.append(cell(x, py, z));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static char cell(int x, int py, int z) {
        // find the walking surface nearest to our height among known blocks
        for (int d = 0; d <= 6; d++) {
            for (int sgn : new int[]{1, -1}) {
                int y = py + d * sgn;
                if (d == 0 && sgn == -1) continue;
                BlockState feet = MAP.get(x, y, z), below = MAP.get(x, y - 1, z);
                if (feet == null || below == null) continue;
                String bn = blockName(below), fn = blockName(feet);
                if (fn.contains("lava") || fn.contains("fire") || bn.contains("lava") || bn.contains("magma")) return '!';
                if (fn.contains("water") || bn.contains("water")) return '~';
                if (fn.contains("log") || fn.contains("leaves")) return 'T';
                boolean feetFree = Vis.isAirish(feet) || feet.getCollisionShape(mc().level, new BlockPos(x, y, z)).isEmpty();
                boolean floor = !Vis.isAirish(below) && below.getFluidState().isEmpty() && !below.getCollisionShape(mc().level, new BlockPos(x, y - 1, z)).isEmpty();
                if (feetFree && floor) {
                    if (fn.contains("ore") || bn.contains("ore") || fn.contains("chest") || fn.contains("crafting") || fn.contains("furnace")) return 'b';
                    if (!Vis.isAirish(feet)) return ',';
                    int dy = y - py;
                    if (dy == 0) return '.';
                    if (dy < -3) return '^';
                    return Character.forDigit(Math.min(9, Math.abs(dy)), 10);
                }
            }
        }
        // no walkable surface known nearby: is anything at all known here?
        for (int y = py - 6; y <= py + 6; y++) {
            BlockState s = MAP.get(x, y, z);
            if (s != null && !Vis.isAirish(s)) {
                String n = blockName(s);
                if (n.contains("ore")) return 'b';
                if (n.contains("lava")) return '!';
                if (n.contains("water")) return '~';
                if (n.contains("log") || n.contains("leaves")) return 'T';
                return '#';
            }
        }
        return '?';
    }

    static Minecraft mc() { return Minecraft.getInstance(); }

    // --------------------------------------------------------------------------------------
    public static String entityName(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); }
    public static String itemName(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }
    public static double round(double v, int dp) { double m = Math.pow(10, dp); return Math.round(v * m) / m; }
}
