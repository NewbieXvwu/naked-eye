package dev.honest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The agent's hands. Everything here ends up as a key being held, a click being counted, or the
 * camera turning at a human-plausible rate. Higher-level tasks (walk to, dig, hit) are just
 * scripted sequences of those inputs, driven by honest perception.
 */
public final class Controller {
    private Controller() {}

    public static final float MAX_TURN_PER_TICK = 40f;   // cap handed to Baritone's own smoothing (800 deg/s peak)
    /** aim skill (Fitts' law constants). Defaults ~ a strong player: 90 deg in ~0.25 s, 180 deg in ~0.3 s */
    public static double AIM_A_MS = Double.parseDouble(System.getProperty("honest.aim.a", "30")),
                         AIM_B_MS = Double.parseDouble(System.getProperty("honest.aim.b", "50"));
    static float[] lastAim;
    public static int trackId = 0; static int trackLost = 0;
    static float[] flick; // {startYaw, startPitch, targetYaw, targetPitch, tick, durationTicks}
    public static final int MAX_CPS = 12;                // clicks per second

    static Float targetYaw, targetPitch;
    static final Map<String, Integer> holds = new HashMap<>();
    static final Deque<Long> clickTimes = new ArrayDeque<>();
    static Task task;
    static String lastTaskResult = "none";
    public static volatile boolean active = true;        // honest control owns the mouse

    static final String[] KEYS = {"forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"};

    static KeyMapping key(Minecraft mc, String n) {
        var o = mc.options;
        return switch (n) {
            case "forward" -> o.keyUp; case "back" -> o.keyDown; case "left" -> o.keyLeft; case "right" -> o.keyRight;
            case "jump" -> o.keyJump; case "sneak" -> o.keyShift; case "sprint" -> o.keySprint;
            case "attack" -> o.keyAttack; case "use" -> o.keyUse;
            default -> throw new IllegalArgumentException("unknown key " + n);
        };
    }

    public interface Task {
        /** @return null while running, else a result string */
        String tick(Minecraft mc, LocalPlayer pl);
        String describe();
        default void cancel(Minecraft mc) {}
        /** true if the task drives the keys itself (e.g. Baritone) — then we leave them alone */
        default boolean ownsInput() { return false; }
    }

    // ------------------------------------------------------------------ per-tick driver
    public static void tick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (pl == null) return;
        if (task != null) {
            String r;
            try { r = task.tick(mc, pl); } catch (Throwable t) { r = "error: " + t; }
            if (r != null) {
                lastTaskResult = task.describe() + " -> " + r;
                Events.note("task", lastTaskResult);
                task = null;
                holds.clear();
            }
        }
        // camera: a human aim model instead of a flat speed cap.
        //  big re-aims = one ballistic "flick": duration from Fitts' law (a + b*log2(1 + angle/5deg)), minimum-jerk
        //  velocity profile (accelerate, peak, decelerate); small changes = smooth pursuit, closing ~60% per tick.
        //  Never a teleport: even a 180-degree flick by a strong player takes ~7 ticks here.
        // lock-on: keep the crosshair on a nearby thing I'm fighting (a player just keeps looking at it), while no task
        // needs my eyes elsewhere. Lets go when it's dead, far (>16 m), or out of sight for a second.
        if (trackId != 0 && task == null) {
            var te = mc.level.getEntity(trackId);
            if (te == null || !te.isAlive() || te.distanceTo(pl) > 16) { Events.note("task", "lock-on released (" + (te == null || !te.isAlive() ? "target gone" : "too far") + ")"); trackId = 0; }
            else if (Perception.anyPointVisible(mc.level, pl.getEyePosition(), te.getBoundingBox())) {
                trackLost = 0;
                var bb = te.getBoundingBox();
                if (!humanAim) lookAt(pl, bb.getCenter());
                else {
                    // a person's aim point wanders over the body (lazy: it only jumps now and then, ~every 1.5 s),
                    // biased a little above centre, and the hand follows what the eye saw ~2 ticks ago
                    if (aimOff == null || RNG.nextInt(30) == 0)
                        aimOff = new Vec3(clampG(0.25) * bb.getXsize(), (0.1 + clampG(0.2)) * bb.getYsize(), clampG(0.25) * bb.getZsize());
                    Vec3 seen = bb.getCenter().subtract(te.position().subtract(te.xo, te.yo, te.zo).scale(2));
                    lookAt(pl, seen.add(aimOff));
                }
                aimSample(pl, bb);
            }
            else if (++trackLost > 20) { Events.note("task", "lock-on released (lost sight)"); trackId = 0; }
        }
        // the server moved my view (teleport, respawn, "facing ..."): drop any half-finished aim, don't drag it back
        if (lastAim != null && (Math.abs(Mth.wrapDegrees(pl.getYRot() - lastAim[0])) > 5 || Math.abs(pl.getXRot() - lastAim[1]) > 5)) {
            targetYaw = null; targetPitch = null; flick = null;
        }
        if (targetYaw != null || targetPitch != null) {
            float ty = targetYaw != null ? targetYaw : pl.getYRot(), tp = targetPitch != null ? targetPitch : pl.getXRot();
            float dy = Mth.wrapDegrees(ty - pl.getYRot()), dp = tp - pl.getXRot();
            double ang = Math.hypot(dy, dp);
            boolean retarget = flick == null || Math.hypot(Mth.wrapDegrees(ty - flick[2]), tp - flick[3]) > 3;
            if (ang > 20 && retarget) {
                double ms = AIM_A_MS + AIM_B_MS * Math.log(1 + ang / 5.0) / Math.log(2);
                flick = new float[]{pl.getYRot(), pl.getXRot(), ty, tp, 0, (float) Math.max(2, Math.ceil(ms / 50.0))};
            }
            if (flick != null && ang > 1.5) {
                flick[4]++;
                double tau = Math.min(1, flick[4] / flick[5]), s = tau * tau * tau * (10 - 15 * tau + 6 * tau * tau);
                turnBy(mc, pl, Mth.wrapDegrees((float) (flick[0] + Mth.wrapDegrees(flick[2] - flick[0]) * s) - pl.getYRot()),
                        (flick[1] + (flick[3] - flick[1]) * s) - pl.getXRot());
                if (tau >= 1) flick = null;
            } else {
                flick = null;
                boolean pursuing = humanAim && trackId != 0 && task == null;
                // following something that moves: the hand lags (gain < 1, varies) and trembles a little; it never pins
                // the exact spot the way a fixed-gain servo does. Plain re-aims at still things settle exactly.
                double gain = pursuing ? 0.45 + 0.3 * RNG.nextDouble() : (ang < 1.5 ? 1 : 0.6);
                double tremor = pursuing ? 0.07 : 0;
                turnBy(mc, pl, dy * gain + RNG.nextGaussian() * tremor, dp * gain + RNG.nextGaussian() * tremor);
                if (ang < 1.5) { targetYaw = null; targetPitch = null; }
            }
        }
        lastAim = new float[]{pl.getYRot(), pl.getXRot()};
        // keys
        // queued drops (see Api "drop"): one stack per few ticks: swap it into hotbar 8, select it, press Q per item
        if (!dropQueue.isEmpty() && pl.containerMenu == pl.inventoryMenu) {
            if (dropStage == 0) {
                int sl = dropQueue.peekFirst();
                var inv = pl.getInventory();
                if (inv.getItem(sl).isEmpty()) { dropQueue.pollFirst(); }
                else {
                    int hb = sl < 9 ? sl : 8;
                    if (sl >= 9) mc.gameMode.handleContainerInput(pl.inventoryMenu.containerId, sl, 8, net.minecraft.world.inventory.ContainerInput.SWAP, pl);
                    dropHb = hb; dropStage = 1;
                }
            } else if (dropStage == 1) {
                if (pl.getInventory().getSelectedSlot() != dropHb) press(mc.options.keyHotbarSlots[dropHb]);
                dropStage = 2;
            } else {
                var st = pl.getInventory().getItem(dropHb);
                if (!st.isEmpty() && pl.getInventory().getSelectedSlot() == dropHb) {
                    try { if (CLICKS == null) { CLICKS = KeyMapping.class.getDeclaredField("clickCount"); CLICKS.setAccessible(true); }
                          CLICKS.setInt(mc.options.keyDrop, st.getCount()); } catch (Exception ignored) {}
                }
                dropQueue.pollFirst(); dropStage = 0;
            }
        }
        // Sixth reflex: standing IN fire (a burning ruined portal: death #20, 14 HP burned away while a path task
        // refused to route through flames) -> step into the nearest neighbouring cell that isn't fire or lava
        {
            BlockPos fp = pl.blockPosition();
            var fs = mc.level.getBlockState(fp);
            boolean inFire = fs.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock || pl.isInLava();
            if (inFire) {
                Vec3 best = null; double bd = 99;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = fp.relative(d);
                    var ns = mc.level.getBlockState(n); var na = mc.level.getBlockState(n.above());
                    if (ns.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock || !ns.getFluidState().isEmpty() && ns.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                    if (!Vis.isAirish(ns) && !ns.getCollisionShape(mc.level, n).isEmpty()) continue; // a wall
                    if (!Vis.isAirish(na) && !na.getCollisionShape(mc.level, n.above()).isEmpty()) continue;
                    Vec3 c = Vec3.atBottomCenterOf(n); double dd = c.distanceTo(pl.position());
                    if (dd < bd) { bd = dd; best = c; }
                }
                if (best != null) {
                    if (pl.tickCount % 20 == 0) Events.note("reflex", "standing in fire -> stepping out");
                    if (task != null && !(task instanceof Fight)) { task.cancel(mc); task = null; }
                    Vec3 away = best.subtract(pl.position()); Vec3 f = Vec3.directionFromRotation(0, pl.getYRot()); Vec3 r = new Vec3(-f.z, 0, f.x);
                    double a = away.normalize().dot(f), b = away.normalize().dot(r);
                    if (a > 0.3) holds.put("forward", 2); else if (a < -0.3) holds.put("back", 2);
                    if (b > 0.3) holds.put("right", 2); else if (b < -0.3) holds.put("left", 2);
                    holds.put("jump", 2);
                }
            }
        }
        // Fifth reflex: something hostile is right on me and I'm not fighting -> fight back (a person doesn't keep
        // walking/mining while a zombie chews on them; half the deaths were exactly that)
        if (antiMelee && !(task instanceof Fight) && pl.tickCount % 5 == 0) {
            boolean hurtRecently = pl.hurtTime > 0 || (HonestClient.ticks - lastHurtTick >= 0 && HonestClient.ticks - lastHurtTick < 40);
            if (pl.getHealth() < lastHp) lastHurtTick = HonestClient.ticks;
            lastHp = pl.getHealth();
            Entity best = null; double bd = 99;
            for (var e : mc.level.getEntities(pl, pl.getBoundingBox().inflate(4))) {
                if (!(e instanceof net.minecraft.world.entity.monster.Enemy) || !e.isAlive()) continue;
                if (e instanceof net.minecraft.world.entity.Mob m && !m.isAggressive() && !hurtRecently) continue;
                if (!Perception.anyPointVisible(mc.level, pl.getEyePosition(), e.getBoundingBox())) continue;
                double d = e.distanceTo(pl); if (d < bd) { bd = d; best = e; }
            }
            if (best != null && (hurtRecently || bd < 2.5)) {
                Events.note("reflex", "a " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(best.getType()).getPath() + " is on me -> fighting back");
                start(mc, new Fight(best.getId(), true, false, "auto", 4));
            }
        }
        // Fourth reflex: a visible creeper swelling within 5 m and I'm not already fighting it -> walk straight back from it
        if (antiCreeper && !(task instanceof Fight)) {
            boolean found = false;
            for (var e : mc.level.getEntities(pl, pl.getBoundingBox().inflate(5))) {
                if (e instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwelling(1f) > 0.01
                        && Perception.anyPointVisible(mc.level, pl.getEyePosition(), cr.getBoundingBox())) {
                    if (!creeperFlee) Events.note("reflex", "creeper swelling " + Math.round(cr.distanceTo(pl)) + " m away -> backing off");
                    creeperFlee = true;
                    lookAt(pl, cr.getBoundingBox().getCenter());
                    if (task != null) { task.cancel(mc); lastTaskResult = task.describe() + " -> interrupted (creeper)"; task = null; }
                    holds.put("back", 3); found = true;
                    break;
                }
            }
            if (!found) creeperFlee = false;
            // heard the hiss but can't see it (behind me, round a corner): move straight away from the sound NOW,
            // without turning (turning first wastes the fuse). Keys relative to where I face.
            if (!found && Events.lastHiss != null && HonestClient.ticks - Events.lastHissTick >= 0 && HonestClient.ticks - Events.lastHissTick < 30 && Events.lastHiss.distanceTo(pl.position()) < 8) {
                Vec3 away = pl.position().subtract(Events.lastHiss); away = new Vec3(away.x, 0, away.z);
                if (away.lengthSqr() > 1e-4) {
                    if (HonestClient.ticks - Events.lastHissTick < 2) Events.note("reflex", "creeper hissing out of sight -> moving away from the sound");
                    Vec3 f = Vec3.directionFromRotation(0, pl.getYRot()); Vec3 r = new Vec3(-f.z, 0, f.x);
                    double a = away.normalize().dot(f), b = away.normalize().dot(r);
                    if (task != null && !(task instanceof Fight)) { task.cancel(mc); task = null; }
                    if (a > 0.3) { holds.put("forward", 2); holds.put("sprint", 2); } else if (a < -0.3) holds.put("back", 2);
                    if (b > 0.3) holds.put("right", 2); else if (b < -0.3) holds.put("left", 2);
                }
            }
        }
        // Third reflex: standing in lava with a water bucket in the hotbar -> look down and pour it (a reflex, not a plan;
        // the slow brain needed 30 ticks and burned to death with the bucket in hand)
        // Seventh reflex: falling to a landing that would hurt badly, a water bucket in the hotbar -> look down and pour it
        // just before the ground (the "water bucket clutch"); then scoop it back up. Not where water boils away.
        {
            boolean boils = mc.level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES);
            var inv = pl.getInventory();
            if (clutch == 0 && antiFall && !boils && Fall.falling(pl)) {
                Object[] g = Fall.ground(pl);
                int dmg = g == null ? 0 : Fall.landingDamage(pl, (double) g[0], (net.minecraft.world.level.block.state.BlockState) g[1]);
                int wb = -1; for (int i = 0; i < 9; i++) if (Perception.itemName(inv.getItem(i)).equals("water_bucket")) wb = i;
                if (wb >= 0 && dmg >= Math.min(4, pl.getHealth() - 1)) {
                    clutch = 1; clutchSlot = wb; Events.note("reflex", "falling to a " + dmg + " HP landing -> water bucket clutch");
                }
            }
            if (clutch == 1) {
                if (inv.getSelectedSlot() != clutchSlot) press(mc.options.keyHotbarSlots[clutchSlot]);
                lookAngles(pl.getYRot(), 90f);
                if (mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK && bh.getDirection() == Direction.UP
                        && inv.getSelectedSlot() == clutchSlot && pl.getXRot() > 75) { click(mc, "use"); clutch = 2; clutchT = 0; }
                if (pl.onGround() || pl.isInWater()) clutch = pl.isInWater() ? 2 : 0;   // (landed before it was in reach)
            } else if (clutch == 2 && ++clutchT > 4 && (pl.onGround() || pl.isInWater())) {
                // down safely: take the water back (a player always does: it's the only bucket)
                if (Perception.itemName(pl.getMainHandItem()).equals("bucket")) { lookAngles(pl.getYRot(), 90f); if (clutchT > 7) { click(mc, "use"); clutch = 0; Events.note("reflex", "landed in my water -> scooping it back up"); } }
                else clutch = 0;
            }
        }
        // (in the Nether water boils away: no point looking down to pour it, those ticks are better spent getting out)
        boolean waterBoils = mc.level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES);
        if (antiLava && !waterBoils && (pl.isInLava() || pl.isOnFire() && !pl.isInWater() || lavaPour > 0)) {
            var inv = pl.getInventory(); int wb = -1;
            for (int i = 0; i < 9; i++) if (Perception.itemName(inv.getItem(i)).equals("water_bucket")) wb = i;
            if (lavaPour == 0 && wb >= 0) { lavaPour = 1; Events.note("reflex", "in lava -> pouring my water bucket at my feet"); }
            if (lavaPour > 0) {
                lavaPour++;
                if (wb >= 0 && inv.getSelectedSlot() != wb) press(mc.options.keyHotbarSlots[wb]);
                lookAngles(pl.getYRot(), 90f);
                if (pl.getXRot() > 80 && wb >= 0 && inv.getSelectedSlot() == wb) { click(mc, "use"); lavaPour = 0; }
                if (lavaPour > 20) lavaPour = 0;
            }
        }
        if (task != null && task.ownsInput()) { drowning = false; return; }
        // The one default reflex: head underwater and losing air with nothing else steering -> swim up.
        // A slow brain polling every few seconds otherwise drowns while thinking. Off via act {type:"reflex", anti_drown:false}.
        // holding sneak = diving on purpose, so the reflex stays out of it — until a quarter of the air is left: then it
        // wins, like a diver's instinct (a held-down sneak once sank me to the sea floor with 3 air left)
        boolean lastGasp = pl.getAirSupply() < pl.getMaxAirSupply() / 4;
        boolean swimUp = antiDrown && pl.isUnderWater() && pl.getAirSupply() < pl.getMaxAirSupply() && (!holds.containsKey("sneak") || lastGasp);
        if (swimUp && lastGasp && holds.remove("sneak") != null) Events.note("reflex", "almost out of air -> letting go of sneak and swimming up");
        if (swimUp && !drowning) Events.note("reflex", "head underwater, air " + (100 * Math.max(0, pl.getAirSupply()) / pl.getMaxAirSupply()) + "% -> holding jump to swim up");
        drowning = swimUp;
        // Second default reflex: buried (gravel/sand fell on my head) -> dig the block my head is in.
        // Suffocation kills in ~10 s; a brain that polls every few seconds plus a stopped task = death (it happened).
        boolean buried = antiSuffocate && pl.isInWall();
        if (buried) {
            BlockPos head = BlockPos.containing(pl.getEyePosition());
            if (!suffocating) Events.note("reflex", "buried in " + Perception.itemName(new net.minecraft.world.item.ItemStack(mc.level.getBlockState(head).getBlock())) + " -> digging out");
            pickTool(mc, pl, mc.level.getBlockState(head));
            lookAt(pl, Vec3.atCenterOf(head));
            holds.put("attack", -1);
        } else if (suffocating) holds.remove("attack");
        suffocating = buried;
        for (String k : KEYS) {
            Integer t = holds.get(k);
            boolean down = t != null && (t > 0 || t == -1) || (swimUp && k.equals("jump"));
            key(mc, k).setDown(down);
            if (t != null && t > 0) { if (t == 1) holds.remove(k); else holds.put(k, t - 1); }
        }
    }

    public static boolean antiDrown = true, antiSuffocate = true, antiLava = true, antiCreeper = true, antiFall = true;
    static int clutch = 0, clutchSlot = 0, clutchT = 0;
    static boolean creeperFlee = false;
    public static boolean antiMelee = true; static int lastHurtTick = -999; static float lastHp = 20;
    public static final java.util.ArrayDeque<Integer> dropQueue = new java.util.ArrayDeque<>(); static int dropStage = 0, dropHb = 0;
    static int lavaPour = 0;
    static boolean suffocating = false;
    static boolean drowning = false;

    // ------------------------------------------------------------------ primitives
    public static void hold(String k, int ticks) { holds.put(k, ticks <= 0 ? -1 : ticks); }
    public static void release(String k) { holds.remove(k); }
    /** stop breaking at once: the held button, the key itself, and the block half-broken under the crosshair */
    public static void letGoOfAttack(Minecraft mc) { holds.remove("attack"); key(mc, "attack").setDown(false); mc.gameMode.stopDestroyBlock(); }
    public static void releaseAll() { holds.clear(); }
    /** let go of every key without forgetting what the task wants held (used while the game is paused) */
    public static void releaseKeys(Minecraft mc) {
        // keep holding "use" through a pause if I'm mid-eat or have the shield up: letting go cancels the eat (32 ticks)
        // and a frame-by-frame player could then never finish a meal. The frozen world doesn't advance it anyway.
        boolean using = mc.player != null && mc.player.isUsingItem();
        for (String k : KEYS) if (!(using && k.equals("use"))) key(mc, k).setDown(false);
    }

    public static boolean click(Minecraft mc, String k) {
        long now = HonestClient.ticks;   // a game second, so the limit means the same at any tick rate
        while (!clickTimes.isEmpty() && now - clickTimes.peekFirst() >= 20) clickTimes.removeFirst();
        if (clickTimes.size() >= MAX_CPS) return false;
        clickTimes.addLast(now);
        press(key(mc, k));
        return true;
    }

    private static java.lang.reflect.Field CLICKS;

    /** One key press on this mapping (what vanilla's input handler does when the key goes down). */
    public static void press(KeyMapping km) {
        try {
            if (CLICKS == null) { CLICKS = KeyMapping.class.getDeclaredField("clickCount"); CLICKS.setAccessible(true); }
            CLICKS.setInt(km, CLICKS.getInt(km) + 1);
        } catch (Exception e) {
            KeyMapping.click(km.getDefaultKey()); // fallback
        }
    }

    static final Random RNG = new Random();
    public static boolean humanAim = true;
    static Vec3 aimOff;
    static double clampG(double sd) { return Mth.clamp(RNG.nextGaussian() * sd, -0.35, 0.35); }

    /** degrees of view per mouse count at the current sensitivity (vanilla MouseHandler.turnPlayer -> Entity.turn) */
    static double mouseStep(Minecraft mc) { double f = mc.options.sensitivity().get() * 0.6 + 0.2; return f * f * f * 8.0 * 0.15; }

    /** every view change goes in whole mouse counts, like a real mouse: a hand can't move a third of a count.
     *  (humanAim=false: the old free-float turn, for A/B) */
    static void turnBy(Minecraft mc, LocalPlayer pl, double dyaw, double dpitch) {
        if (humanAim) { double g = mouseStep(mc); dyaw = Math.round(dyaw / g) * g; dpitch = Math.round(dpitch / g) * g; }
        if (dyaw != 0) pl.setYRot(pl.getYRot() + (float) dyaw);
        if (dpitch != 0) pl.setXRot(Mth.clamp(pl.getXRot() + (float) dpitch, -90f, 90f));
    }

    // ---- aim log (experimenter only): how a lock-on actually sits on the target, tick by tick
    static final ArrayList<double[]> aimLog = new ArrayList<>();
    static float[] lastLogRot;
    static void aimSample(LocalPlayer pl, net.minecraft.world.phys.AABB bb) {
        Vec3 eye = pl.getEyePosition(), look = pl.getViewVector(1);
        Vec3 to = bb.getCenter().subtract(eye).normalize();
        double err = Math.toDegrees(Math.acos(Mth.clamp(look.dot(to), -1, 1)));
        boolean on = bb.clip(eye, eye.add(look.scale(12))).isPresent();
        double dyaw = lastLogRot == null ? Double.NaN : Mth.wrapDegrees(pl.getYRot() - lastLogRot[0]);
        double dpitch = lastLogRot == null ? Double.NaN : pl.getXRot() - lastLogRot[1];
        lastLogRot = new float[]{pl.getYRot(), pl.getXRot()};
        aimLog.add(new double[]{err, on ? 1 : 0, dyaw, dpitch});
    }
    public static JsonObject aimReport(Minecraft mc, boolean reset) {
        JsonObject j = new JsonObject();
        int n = aimLog.size(); j.addProperty("ticks", n);
        if (n > 0) {
            double[] e = aimLog.stream().mapToDouble(a -> a[0]).sorted().toArray();
            j.addProperty("err_mean_deg", Math.round(Arrays.stream(e).average().orElse(0) * 100) / 100.0);
            j.addProperty("err_p50_deg", Math.round(e[n / 2] * 100) / 100.0);
            j.addProperty("err_p90_deg", Math.round(e[n * 9 / 10] * 100) / 100.0);
            j.addProperty("pinned_on_centre_pct", Math.round(100.0 * Arrays.stream(e).filter(x -> x < 0.15).count() / n));
            j.addProperty("crosshair_on_body_pct", Math.round(100.0 * aimLog.stream().filter(a -> a[1] > 0).count() / n));
            double g = mouseStep(mc);
            var moves = aimLog.stream().filter(a -> !Double.isNaN(a[2]) && (a[2] != 0 || a[3] != 0)).toList();
            long whole = moves.stream().filter(a -> Math.abs(a[2] / g - Math.rint(a[2] / g)) < 1e-3 && Math.abs(a[3] / g - Math.rint(a[3] / g)) < 1e-3).count();
            j.addProperty("moving_ticks", moves.size());
            j.addProperty("whole_mouse_counts_pct", moves.isEmpty() ? 0 : Math.round(100.0 * whole / moves.size()));
            j.addProperty("still_ticks_pct", Math.round(100.0 * (n - moves.size()) / n));
        }
        if (reset) { aimLog.clear(); lastLogRot = null; }
        return j;
    }

    public static void lookAngles(float yaw, float pitch) { targetYaw = yaw; targetPitch = Mth.clamp(pitch, -90, 90); }

    public static void lookAt(LocalPlayer pl, Vec3 p) {
        Vec3 d = p.subtract(pl.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        lookAngles(yaw, pitch);
    }

    static boolean aligned() { return targetYaw == null && targetPitch == null; }

    public static void start(Minecraft mc, Task t) {
        if (task != null) { task.cancel(mc); Events.note("task", task.describe() + " -> cancelled"); }
        holds.clear();
        task = t;
    }

    public static void stop(Minecraft mc) {
        if (task != null) { task.cancel(mc); lastTaskResult = task.describe() + " -> stopped"; }
        task = null; holds.clear(); targetYaw = null; targetPitch = null;
    }

    /** nothing in motion that the world must keep running for: no task, no held key, no camera turn, no reflex.
     *  (Client ticks keep running while the world is frozen, so a held key would just count down uselessly.) */
    public static boolean idleNow(LocalPlayer pl) {
        return task == null && holds.isEmpty() && flick == null && targetYaw == null && dropQueue.isEmpty()
                && lavaPour == 0 && clutch == 0 && !creeperFlee && !pl.isUsingItem() && !BaritoneBridge.busy();
    }

    public static JsonObject status() {
        JsonObject j = new JsonObject();
        j.addProperty("task", task == null ? "idle" : task.describe());
        j.addProperty("last_result", lastTaskResult);
        j.addProperty("held_keys", holds.keySet().toString());
        return j;
    }

    // ================================================================== tasks

    /** Hold attack on a block you can see until it breaks. */
    public static final class Dig implements Task {
        final BlockPos pos; int t = 0, cleared = 0; BlockPos clearing;
        public boolean force = false; // dig even with lava known right behind it
        public Dig(BlockPos p) { pos = p; }
        public String describe() { return "dig " + pos.toShortString(); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (++t > 600) return "timeout";
            // the lesson of death #10: opening a block that lava sits behind lets it flow straight in on top of you
            if (t == 1 && !force) {
                // the oldest rule of the game: don't dig the block you stand on unless you know what's under it
                var bb = pl.getBoundingBox();
                boolean underMe = pl.onGround() && pos.getY() == Mth.floor(pl.getY()) - 1
                        && pos.getX() + 1 > bb.minX && pos.getX() < bb.maxX && pos.getZ() + 1 > bb.minZ && pos.getZ() < bb.maxZ;
                if (underMe) {
                    BlockState below = Perception.MAP.get(pos.getX(), pos.getY() - 1, pos.getZ());
                    boolean floorRightUnder = below != null && !Vis.isAirish(below) && below.getFluidState().isEmpty();
                    if (!floorRightUnder) {
                        int air = 0; BlockState s = below;
                        for (int k = 1; k < 40 && s != null && Vis.isAirish(s) && s.getFluidState().isEmpty(); k++) { air++; s = Perception.MAP.get(pos.getX(), pos.getY() - 1 - k, pos.getZ()); }
                        String what = below == null ? "I haven't seen what's under it"
                                : !below.getFluidState().isEmpty() ? "there's " + Perception.itemName(new net.minecraft.world.item.ItemStack(below.getBlock())) + " under it"
                                : s == null ? "there's a drop under it, at least " + air + " m (the bottom unseen)" : "there's a " + air + " m drop under it";
                        return "refused: that's the block I'm standing on, and " + what + " — never dig straight down (dig beside me and look first, or force)";
                    }
                }
            }
            if (t == 1 && !force) for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue; // lava under it can't climb up into the hole
                BlockPos n = pos.relative(d);
                BlockState ns = Perception.MAP.get(n.getX(), n.getY(), n.getZ());
                if (ns != null && ns.getFluidState().is(net.minecraft.tags.FluidTags.LAVA))
                    return "refused: I remember lava at " + n.toShortString() + " right behind that block — it would pour in (plug/quench it first, or force)";
            }
            if (Vis.isAirish(mc.level.getBlockState(pos))) {
                Perception.MAP.see(pos, mc.level.getBlockState(pos));
                // let go of the button THIS tick: held one tick longer, it starts on whatever is behind the block — in a
                // tunnel that was the next column's floor-level block, opened before the one above it, and the lava
                // behind it came pouring through a slit my eyes (1.6 m up, the block above still there) couldn't see into
                if (t > 1) letGoOfAttack(mc);
                return cleared > 0 ? "done (cleared " + cleared + " block(s) in the way)" : "done";
            }
            if (pl.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.8) return "out_of_reach";
            if (t == 1) pickTool(mc, pl, mc.level.getBlockState(pos));
            lookAt(pl, Vec3.atCenterOf(pos));
            HitResult hr = mc.hitResult;
            boolean onIt = hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(pos);
            if (onIt) { holds.put("attack", -1); return null; }
            // something else is under the crosshair once we're aimed: break it out of the way, like a person would
            if (aimError(pl, Vec3.atCenterOf(pos)) < 4f && hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
                if (!bh.getBlockPos().equals(clearing)) {
                    if (clearing != null) cleared++;
                    clearing = bh.getBlockPos().immutable();
                    if (cleared >= 3) return "too much in the way";
                }
                holds.put("attack", -1);
            } else holds.remove("attack");
            return null;
        }
    }

    /** Like a player glancing at the hotbar: hold the hotbar item that breaks this block fastest (and actually drops it). */
    static void pickTool(Minecraft mc, LocalPlayer pl, BlockState st) {
        var inv = pl.getInventory(); int best = inv.getSelectedSlot(); float bestScore = -1;
        for (int i = 0; i < 9; i++) {
            var it = inv.getItem(i);
            // a tool on its last few uses is as good as broken: don't pick it (it then refused to dig at all)
            if (it.isDamageableItem() && it.getMaxDamage() - it.getDamageValue() <= Math.max(3, it.getMaxDamage() / 30)) continue;
            float sp = it.getDestroySpeed(st);
            boolean drops = !st.requiresCorrectToolForDrops() || it.isCorrectToolForDrops(st);
            // must drop the block; then a tool that actually speeds it up; then the CHEAPEST such tool
            // (don't burn the iron pickaxe on stone when a stone one is right there)
            float score = (drops ? 1000 : 0) + (sp > 1f ? 200 : 0) + Math.min(sp, 20f) - (it.isDamageableItem() ? it.getMaxDamage() / 50f : 0)
                    // the sword is for fighting: chopping leaves with it costs 2 durability a block (it happened). Last resort only
                    - (it.is(net.minecraft.tags.ItemTags.SWORDS) ? 250 : 0);
            if (score > bestScore + 1e-3) { bestScore = score; best = i; }
        }
        if (best != inv.getSelectedSlot()) press(mc.options.keyHotbarSlots[best]);
    }

    static float aimError(LocalPlayer pl, Vec3 p) {
        Vec3 d = p.subtract(pl.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return Math.abs(Mth.wrapDegrees(yaw - pl.getYRot())) + Math.abs(pitch - pl.getXRot());
    }

    /** Aim at a visible entity and swing when the attack cooldown has recovered. */
    public static final class Hit implements Task {
        final int id; final int swings; int done = 0, t = 0, unseen = 0;
        public Hit(int id, int swings) { this.id = id; this.swings = swings; }
        public String describe() { return "hit entity " + id + " x" + swings; }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (++t > 600) return "timeout after " + done + " swings";
            Entity e = mc.level.getEntity(id);
            if (e == null || !e.isAlive()) return "target gone after " + done + " swings";
            boolean visible = Perception.anyPointVisible(mc.level, pl.getEyePosition(), e.getBoundingBox());
            if (!visible) { if (++unseen > 30) return "lost sight"; return null; }
            unseen = 0;
            lookAt(pl, e.getBoundingBox().getCenter());
            double dist = pl.getEyePosition().distanceTo(e.getBoundingBox().getCenter());
            if (dist > 3.2) { holds.put("forward", 2); }
            HitResult hr = mc.hitResult;
            if (hr instanceof EntityHitResult eh && eh.getEntity() == e && pl.getAttackStrengthScale(0f) >= 0.95f) {
                if (click(mc, "attack")) done++;
                if (done >= swings) return "swung " + done + " times";
            }
            return null;
        }
    }

    /** Right-click whatever the crosshair is on (place block / open / use item). */
    public static final class UseAt implements Task {
        final Vec3 aim; int t = 0;
        public UseAt(Vec3 aim) { this.aim = aim; }
        public String describe() { return "use at " + String.format("%.1f %.1f %.1f", aim.x, aim.y, aim.z); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            lookAt(pl, aim);
            if (++t > 60) return "timeout";
            if (aimError(pl, aim) < 3f && t > 2) { click(mc, "use"); return "clicked"; }
            return null;
        }
    }

    /**
     * Put a block from the hotbar into an empty cell: pick a known solid neighbour whose face points at the cell,
     * select the item with its number key, aim at that face, right-click, and confirm by looking.
     * (What a player does without thinking; doing it via raw use_at coordinates was the #1 fiddly failure.)
     */
    static Vec3 facePoint(BlockPos cell, Direction d, double su, double sv) {
        Vec3 face = Vec3.atCenterOf(cell).add(d.getStepX() * 0.49, d.getStepY() * 0.49, d.getStepZ() * 0.49);
        Direction u = d.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
        Direction v = d.getAxis() == Direction.Axis.Y ? Direction.EAST : d.getClockWise();
        return face.add(u.getStepX() * su + v.getStepX() * sv, u.getStepY() * su + v.getStepY() * sv, u.getStepZ() * su + v.getStepZ() * sv);
    }

    /** try several cells in turn until one takes the block */
    public static final class PlaceAny implements Task {
        final java.util.List<BlockPos> cells; final String item; int i = 0; Place cur; String last = "";
        public PlaceAny(java.util.List<BlockPos> cells, String item) { this.cells = cells; this.item = item; }
        public String describe() { return "place " + (item == null ? "held item" : item) + (cur != null ? " at " + cur.cell.toShortString() : " next to me"); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (cur == null) { if (i >= cells.size()) return "couldn't place anywhere next to me (" + cells.size() + " spots; last: " + last + ")"; cur = new Place(cells.get(i++), item); }
            String r = cur.tick(mc, pl);
            if (r == null) return null;
            if (r.equals("placed")) return "placed at " + cur.cell.toShortString();
            last = r; cur = null; release("use"); return null;
        }
    }

    public static final class Place implements Task {
        final BlockPos cell; final String item; int t = 0, tries = 0, idx = 0; List<Direction> faces; String why = "";
        /** a reflex (plugging lava): click as soon as the crosshair is on a right face, don't settle the aim first */
        public boolean quick = false;
        public Place(BlockPos cell, String item) { this.cell = cell; this.item = item; }
        public String describe() { return "place " + (item == null ? "held item" : item) + " at " + cell.toShortString(); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (++t > 200) return "timeout";
            if (t == 1) {
                // still "mining" from the last dig (attack held) = right-clicks are ignored by the game: let go first
                release("attack"); key(mc, "attack").setDown(false); mc.gameMode.stopDestroyBlock();
                if (!mc.level.getBlockState(cell).canBeReplaced()) return "cell isn't empty (" + Perception.itemName(new net.minecraft.world.item.ItemStack(mc.level.getBlockState(cell).getBlock())) + ")";
                if (pl.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cell))) return "I'm standing in that cell";
                if (item != null) {
                    int slot = -1;
                    for (int i = 0; i < 9; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals(item)) { slot = i; break; }
                    if (slot < 0) { // open the inventory, hover it, press 9 — what a player does
                        for (int i = 9; i < 36; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals(item)) {
                            if (pl.containerMenu != pl.inventoryMenu) return "close the open screen first";
                            mc.gameMode.handleContainerInput(pl.inventoryMenu.containerId, i, 8, net.minecraft.world.inventory.ContainerInput.SWAP, pl);
                            slot = 8; break;
                        }
                    }
                    if (slot < 0) return "I have no " + item;
                    if (pl.getInventory().getSelectedSlot() != slot) press(mc.options.keyHotbarSlots[slot]);
                }
                faces = new ArrayList<>();
                Vec3 eye = pl.getEyePosition();
                for (Direction d : Direction.values()) {
                    BlockPos n = cell.relative(d);
                    BlockState s = Perception.MAP.get(n.getX(), n.getY(), n.getZ());
                    // never click a replaceable thing (grass, leaf litter, snow): the block would go INTO it instead
                    if (s == null || Vis.isAirish(s) || !s.getFluidState().isEmpty() || s.canBeReplaced()) continue;
                    Vec3 face = Vec3.atCenterOf(cell).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
                    if (eye.distanceTo(face) > 4.4) continue;
                    // the face we click must point back toward us (we look at it from the cell's side)
                    Vec3 toEye = eye.subtract(face);
                    if (toEye.dot(new Vec3(-d.getStepX(), -d.getStepY(), -d.getStepZ())) <= 0.05) continue;
                    // can I actually see some of that face? (from the water's edge the bank hid them all: 25 wasted tries)
                    boolean seen = false;
                    for (double[] o : new double[][]{{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}}) {
                        Vec3 pt = facePoint(cell, d, o[0], o[1]);
                        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(Vec3.atCenterOf(n).subtract(pt).scale(0.02)),
                                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, pl));
                        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(n)) { seen = true; break; }
                    }
                    if (!seen) continue;
                    faces.add(d);
                }
                if (quick) // a reflex: the face I look at most squarely (a grazing side face kept missing: 14 ticks lost)
                    faces.sort(Comparator.comparingDouble(d -> {
                        Vec3 fc = Vec3.atCenterOf(cell).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
                        return eye.subtract(fc).normalize().dot(new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()));   // most negative = face-on
                    }));
                else faces.sort(Comparator.comparingDouble(d -> eye.distanceTo(Vec3.atCenterOf(cell).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5))));
                if (faces.isEmpty()) return "no known solid face to place against within reach";
                return null;
            }
            // a reflex: if the crosshair is already on a face that puts the block into the cell, click now (the eyes are
            // on the hole I just opened; that's what a player's quick right-click does)
            if (quick && t <= 3 && mc.hitResult instanceof BlockHitResult bh0 && mc.hitResult.getType() == HitResult.Type.BLOCK
                    && bh0.getBlockPos().relative(bh0.getDirection()).equals(cell) && !mc.level.getBlockState(bh0.getBlockPos()).canBeReplaced()
                    && (item == null || Perception.itemName(pl.getMainHandItem()).equals(item))) {
                click(mc, "use"); return null;
            }
            if (!mc.level.getBlockState(cell).canBeReplaced()) {
                Perception.MAP.see(cell, mc.level.getBlockState(cell));
                return "placed";
            }
            // each face: its centre, then 4 points toward its edges — the centre is often hidden behind a ledge or my
            // own block when I look at it steeply, while a corner of the same face is in plain view (it kept failing)
            if (idx >= faces.size() * 5) return "couldn't place (tried " + faces.size() + " faces, 5 spots each; last: " + why + ")";
            Direction d = faces.get(idx / 5);
            int k = idx % 5;
            Vec3 face = facePoint(cell, d, k == 1 ? 0.3 : k == 2 ? -0.3 : 0, k == 3 ? 0.3 : k == 4 ? -0.3 : 0);
            lookAt(pl, face);
            if (aimError(pl, face) < (quick ? 4f : 2f) && t > (quick ? 1 : 3)) {
                HitResult hr = mc.hitResult;
                // what matters is where the block would GO: clicked block + clicked face == our cell (and the clicked block
                // isn't itself replaceable grass/litter, which would swallow the block instead)
                if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && bh.getBlockPos().relative(bh.getDirection()).equals(cell)
                        && !mc.level.getBlockState(bh.getBlockPos()).canBeReplaced()) {
                    if (tries++ < 3) click(mc, "use"); else { idx++; tries = 0; why = "clicked 3x, nothing appeared"; }
                } else {
                    idx++; tries = 0;
                    why = hr instanceof BlockHitResult bh2 && hr.getType() == HitResult.Type.BLOCK
                            ? "crosshair was on " + bh2.getBlockPos().toShortString() + " " + bh2.getDirection().getName() + " face"
                            : "crosshair on " + (hr == null ? "nothing" : hr.getType().name().toLowerCase());
                }
            }
            return null;
        }
    }

    /** Right-click a block (open a table/furnace/chest, press a button…): aim at whichever face is actually
     *  under the crosshair, and only click when the crosshair is on THAT block — not on leaves in front of it. */
    public static final class Interact implements Task {
        final BlockPos pos; int t = 0, idx = 0, clicked = 0;
        static final Vec3[] OFF = {new Vec3(0, 0, 0), new Vec3(0, 0.45, 0), new Vec3(0.45, 0, 0), new Vec3(-0.45, 0, 0), new Vec3(0, 0, 0.45), new Vec3(0, 0, -0.45), new Vec3(0, -0.45, 0)};
        public Interact(BlockPos p) { pos = p; }
        public String describe() { return "interact with " + pos.toShortString(); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (++t > 160) return "couldn't get the crosshair onto it";
            if (mc.gui.screen() != null) return "opened " + mc.gui.screen().getTitle().getString();
            if (clicked > 0 && ++clicked > 6) return "clicked (nothing opened)";
            if (clicked > 0) return null;
            if (pl.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.6) return "out of reach";
            Vec3 aim = Vec3.atCenterOf(pos).add(OFF[idx % OFF.length]);
            lookAt(pl, aim);
            if (aimError(pl, aim) < 2f) {
                if (mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(pos)) {
                    click(mc, "use"); clicked = 1;
                } else idx++;
            }
            return null;
        }
    }

    /** Fill an empty bucket from a liquid source block: aim so that the bucket's own ray (which skips flowing
     *  liquid but stops at walls) lands on THAT source, then right-click. */
    public static final class Scoop implements Task {
        final BlockPos pos; int t = 0, idx = 0, clicked = 0; String lastBlocker = "?";
        public Scoop(BlockPos p) { pos = p; }
        public String describe() { return "scoop " + pos.toShortString(); }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (++t > 200) return "couldn't get a clear line to that source";
            String held = Perception.itemName(pl.getMainHandItem());
            if (t == 1) {
                if (!held.equals("bucket")) {
                    int slot = -1;
                    for (int i = 0; i < 9; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals("bucket")) slot = i;
                    if (slot < 0) return "no empty bucket in the hotbar";
                    press(mc.options.keyHotbarSlots[slot]);
                }
                return null;
            }
            if (held.endsWith("_bucket")) return "filled: " + held;
            if (clicked > 0) { if (++clicked > 8) { clicked = 0; idx++; } return null; }
            if (pl.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.8) return "out of reach";
            Vec3 aim = Vec3.atCenterOf(pos).add(Interact.OFF[idx % Interact.OFF.length].scale(0.9));
            if (idx >= Interact.OFF.length) return "no clear line of sight to the source (the bucket's ray hit " + lastBlocker + ")";
            lookAt(pl, aim);
            if (aimError(pl, aim) < 2f) {
                Vec3 eye = pl.getEyePosition(), end = eye.add(pl.getViewVector(1f).scale(5));
                var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, pl));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) { click(mc, "use"); clicked = 1; }
                else { idx++; lastBlocker = hit.getType() == HitResult.Type.BLOCK ? Perception.blockName(mc.level.getBlockState(hit.getBlockPos())) + " at " + hit.getBlockPos().toShortString() : "nothing"; }
            }
            return null;
        }
    }

    /** Walk to a goal using only the known map; replan as new terrain is seen. */
    public static final class Walk implements Task {
        final BlockPos goal; final double risk;
        List<BlockPos> path = List.of(); int idx = 0, t = 0, sinceReplan = 999, stuckTicks = 0, replans = 0;
        Vec3 lastPos; int lastMapVersion = -1; boolean complete;
        public Walk(BlockPos goal, double risk) { this.goal = goal; this.risk = risk; }
        public String describe() { return "walk to " + goal.toShortString() + " (risk " + risk + ")"; }

        void replan(LocalPlayer pl) {
            BlockPos start = BlockPos.containing(pl.getX(), pl.getY() + 0.01, pl.getZ());
            Nav.Result r = Nav.plan(start, goal, risk, 30000);
            path = r.path(); complete = r.complete(); idx = Math.min(1, path.size() - 1);
            sinceReplan = 0; replans++;
            lastMapVersion = Perception.MAP.version();
        }

        public String tick(Minecraft mc, LocalPlayer pl) {
            t++; sinceReplan++;
            Vec3 p = pl.position();
            if (Math.abs(p.x - (goal.getX() + 0.5)) < 0.8 && Math.abs(p.z - (goal.getZ() + 0.5)) < 0.8 && Math.abs(p.y - goal.getY()) < 0.6)
                return "arrived";
            if (t > 20 * 120) return "gave up after 2 minutes";
            boolean pathInvalid = false;
            if (Perception.MAP.version() != lastMapVersion && idx < path.size()) {
                for (int i = idx; i < Math.min(path.size(), idx + 6); i++) {
                    BlockPos b = path.get(i);
                    if (Nav.stand(b.getX(), b.getY(), b.getZ(), risk) >= Nav.INF) { pathInvalid = true; break; }
                }
                lastMapVersion = Perception.MAP.version();
            }
            if (path.isEmpty() || idx >= path.size() || pathInvalid || sinceReplan > 60) {
                replan(pl);
                if (path.size() <= 1) {
                    if (replans > 4) return complete ? "arrived" : "no known route (explore or lower caution)";
                    holds.clear();
                    // at the frontier: look at the ground toward the goal so it becomes known
                    float yawToGoal = (float) Math.toDegrees(Math.atan2(-(goal.getX() + 0.5 - p.x), goal.getZ() + 0.5 - p.z));
                    targetYaw = yawToGoal + (replans % 2 == 0 ? 25f : -25f);
                    targetPitch = 50f;
                    sinceReplan = 45; // replan again shortly, after the eyes have done their job
                    return null;
                }
            }
            BlockPos wp = path.get(idx);
            Vec3 c = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
            double hd = Math.hypot(c.x - p.x, c.z - p.z);
            if (hd < 0.45 && Math.abs(c.y - p.y) < 1.2) { idx++; if (idx >= path.size()) return null; wp = path.get(idx); c = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5); }
            float yaw = (float) Math.toDegrees(Math.atan2(-(c.x - p.x), c.z - p.z));
            targetYaw = yaw; targetPitch = 20f; // look where you walk (also feeds the known map)
            float off = Math.abs(Mth.wrapDegrees(yaw - pl.getYRot()));
            if (off < 50) holds.put("forward", 2); else holds.remove("forward");
            boolean climbing = wp.getY() > p.y + 0.5;
            if (climbing || (pl.horizontalCollision && pl.onGround())) holds.put("jump", 2);
            // sneak along edges: vanilla won't let a sneaking player walk off a block edge
            BlockPos here = pl.blockPosition();
            boolean descending = wp.getY() < p.y - 0.5;
            if (!descending && !climbing && (Nav.edge(here.getX(), here.getY(), here.getZ()) || Nav.edge(wp.getX(), wp.getY(), wp.getZ())))
                holds.put("sneak", 2);
            // stuck detection
            if (t % 40 == 0) {
                if (lastPos != null && lastPos.distanceTo(p) < 0.4) { stuckTicks += 40; holds.put("jump", 4); sinceReplan = 999; }
                else stuckTicks = 0;
                lastPos = p;
                if (stuckTicks >= 160) return "stuck";
            }
            return null;
        }
        public void cancel(Minecraft mc) { holds.clear(); }
    }

    /** Turn in place a full circle to look around (feeds the known map). */
    public static final class LookAround implements Task {
        int t = 0; final float start; final float pitch;
        public LookAround(LocalPlayer pl, float pitch) { start = pl.getYRot(); this.pitch = pitch; }
        public String describe() { return "look around"; }
        // three turns: level, then looking down (floors, drops, stairs going down), then up (shafts, ledges)
        public String tick(Minecraft mc, LocalPlayer pl) {
            t++;
            int pass = Math.min(2, (t - 1) / 48), u = (t - 1) % 48;
            targetPitch = pass == 0 ? pitch : pass == 1 ? 55f : -40f;
            targetYaw = start + (pass == 1 ? -1 : 1) * Math.min(360f, u * 7.7f); // slow enough for the eyes to keep up
            if (t >= 144) return "done";
            return null;
        }
    }
}
