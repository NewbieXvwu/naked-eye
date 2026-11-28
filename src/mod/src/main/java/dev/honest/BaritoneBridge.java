package dev.honest;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.HonestWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Bridge to the honest Baritone fork. Only loaded when Baritone is present.
 * Baritone plans over the agent's memory (HonestWorld.lookup), turns at most as fast as our own
 * controller, and all of its world-scanning entry points (chat/prefix commands) are switched off.
 */
public final class BaritoneBridge {
    private BaritoneBridge() {}

    public static boolean present() { return FabricLoader.getInstance().isModLoaded("baritone"); }

    public static void install() {
        if (Cheat.ON) { installCheat(); return; }
        HonestWorld.lookup = (x, y, z) -> Perception.MAP.getOrAssume(x, y, z);
        HonestWorld.maxTurnPerTick = Controller.MAX_TURN_PER_TICK;
        Settings s = BaritoneAPI.getSettings();
        s.freeLook.value = false;          // no looking one way while walking another
        s.smoothLook.value = false;        // our turn-rate cap replaces it
        s.randomLooking.value = 0d;
        s.randomLooking113.value = 0d;
        s.chatControl.value = false;       // "#mine diamond_ore" would scan raw chunks = X-ray
        s.prefixControl.value = false;
        s.avoidance.value = false;         // mob avoidance reads true positions through walls
        s.allowParkour.value = false;
        s.allowDownward.value = false;
        // no water-bucket clutches: with a bucket in the bag Baritone plans 20-block drops it then fails to land
        // (fell 8 blocks, 9 -> 4 HP). A drop is only as safe as plain fall damage allows
        s.allowWaterBucketFall.value = false;
        // digging through rock you only *assume* is there should cost more than walking tunnels you know:
        // otherwise it carves a fresh shaft (burning your best pickaxe) right past your own staircase
        s.blockBreakAdditionalPenalty.value = 8.0;
        s.preferCheapestAdequateTool.value = true; // stone pickaxe for stone, iron only where it's needed
        s.itemSaver.value = true;
        s.useSwordToMine.value = false;             // it chewed a stone sword from 96% to 5% digging gravel
        s.chunkCaching.value = false;               // Baritone's own world cache is built from loaded chunks = X-ray; never use it                  // never use a tool down to its last few uses     // never dig straight down: you can't see what's under you
        s.renderPath.value = false;
        s.logAsToast.value = false;
        HonestClient.LOG.info("[honest] Baritone fork wired to the agent's memory");
    }

    /** cheat mode: vanilla Baritone — plans over the real loaded chunks (its own cache too), turns instantly, may
     *  parkour, dig down, clutch with a water bucket, steer around mobs it knows of through walls, and sort the
     *  inventory. Its commands (mine, goto, explore, follow, farm, tunnel, …) come through the API, not the chat. */
    static void installCheat() {
        HonestWorld.lookup = null;
        HonestWorld.maxTurnPerTick = 0f;
        Settings s = BaritoneAPI.getSettings();
        s.chatControl.value = false;       // commands arrive through /act {"type":"baritone"} instead
        s.prefixControl.value = false;
        s.freeLook.value = false;
        s.smoothLook.value = false;
        s.randomLooking.value = 0d;
        s.randomLooking113.value = 0d;
        s.allowParkour.value = true;
        s.allowParkourPlace.value = false;
        s.allowDownward.value = true;
        s.allowWaterBucketFall.value = true;
        s.allowInventory.value = true;
        s.avoidance.value = true;
        s.chunkCaching.value = true;
        s.preferCheapestAdequateTool.value = true;
        s.itemSaver.value = true;
        s.useSwordToMine.value = false;
        s.renderPath.value = false;
        s.logAsToast.value = false;
        s.mineScanDroppedItems.value = true;
        s.logger.value = msg -> { lastMessage = msg.getString(); Events.note("baritone", lastMessage); };
        HonestClient.LOG.info("[honest] CHEAT MODE: Baritone on the real world, full power");
    }
    static volatile String lastMessage = "";

    /** a Baritone command (the ones you'd type after '#'), run as a task until Baritone has nothing left to do */
    public static final class Cmd implements Controller.Task {
        final String cmd; final int limit; final float stopHp; int t = 0, idle = 0, sinceChange = 0, lastInv = 0; float startHp; net.minecraft.world.phys.Vec3 lastPos;
        public Cmd(String cmd, int limitTicks, float stopHp) { this.cmd = cmd; this.limit = limitTicks; this.stopHp = stopHp; }
        public String describe() { return "baritone " + cmd; }
        public boolean ownsInput() { return true; }
        public String tick(Minecraft mc, LocalPlayer pl) {
            if (t++ == 0) {
                startHp = pl.getHealth(); lastMessage = "";
                if (!b().getCommandManager().execute(cmd)) return "baritone doesn't know '" + cmd + "' (try: help)";
                return null;
            }
            if (pl.isDeadOrDying()) { cancel(mc); return "died"; }
            if (pl.getHealth() <= stopHp && pl.getHealth() < startHp) { cancel(mc); return String.format("stopped: HP %.0f (stop at %.0f)", pl.getHealth(), stopHp); }
            if (limit > 0 && t > limit) { cancel(mc); return "time limit (" + limit + " ticks) — " + lastMessage; }
            // stuck: Baritone "busy" but nothing changes (its item saver refused my only, worn-out pickaxe and it
            // circled for 10 real minutes, half a game hour). No move and no inventory change for 600 ticks = give up
            int inv = 0; for (int i = 0; i < 36; i++) inv = inv * 31 + pl.getInventory().getItem(i).getCount() * 7 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(pl.getInventory().getItem(i).getItem());
            if (lastPos == null || pl.position().distanceTo(lastPos) > 2 || inv != lastInv) { lastPos = pl.position(); lastInv = inv; sinceChange = 0; }
            else if (++sinceChange > 600) { cancel(mc); return "stuck: no progress for 600 ticks (worn-out tool? no path?) — " + lastMessage; }
            if (active()) { idle = 0; return null; }
            if (++idle < 10) return null;   // (between two paths Baritone is briefly idle)
            return "done" + (lastMessage.isEmpty() ? "" : ": " + lastMessage);
        }
        public void cancel(Minecraft mc) { BaritoneBridge.cancel(); }
    }

    /** hold Baritone still for a moment (the shield reflex): its own pause/resume commands */
    public static void pause(boolean on) { try { b().getCommandManager().execute(on ? "pause" : "resume"); } catch (Throwable ignored) {} }

    static boolean active() {
        IBaritone b = b();
        return b.getPathingBehavior().isPathing() || b.getPathingControlManager().mostRecentInControl().isPresent();
    }

    public static void setAllowBreak(boolean v) { BaritoneAPI.getSettings().allowBreak.value = v; }
    public static void setAllowPlace(boolean v) { BaritoneAPI.getSettings().allowPlace.value = v; }

    static IBaritone b() { return BaritoneAPI.getProvider().getPrimaryBaritone(); }

    public static boolean busy() {
        IBaritone b = b();
        return b.getPathingBehavior().isPathing() || b.getCustomGoalProcess().isActive() || Cheat.ON && active();
    }

    public static void cancel() { b().getPathingBehavior().cancelEverything(); }

    /** Walk to a block (or next to it, if near=true) with Baritone, as a Controller task. */
    public static final class Go implements Controller.Task {
        final BlockPos goal; final boolean near; int t = 0;
        final baritone.api.pathing.goals.Goal g;
        java.util.Set<String> until = java.util.Set.of(); float startHp = -1;
        public Go(BlockPos goal) { this(goal, false); }
        public Go(BlockPos goal, boolean near) {
            this.goal = goal; this.near = near;
            this.g = near ? new baritone.api.pathing.goals.GoalGetToBlock(goal) : new GoalBlock(goal);
        }
        /** head for an X/Z column at whatever height the ground turns out to be */
        public Go(int x, int z, boolean xz) { this.goal = new BlockPos(x, 0, z); this.near = false; this.g = new baritone.api.pathing.goals.GoalXZ(x, z); }
        /** go to a Y level anywhere (Baritone picks the way; with the rock prior it digs stairs) */
        public Go(int y) { this.goal = new BlockPos(0, y, 0); this.near = false; this.g = new baritone.api.pathing.goals.GoalYLevel(y); }
        /** stop as soon as any of these blocks is in view (reflex: "tell me when you see diamonds / lava") */
        public Go until(java.util.Set<String> blocks) { this.until = blocks; return this; }
        public String describe() { return g instanceof baritone.api.pathing.goals.GoalYLevel ? "baritone go to y=" + goal.getY() : "baritone go " + (near ? "next to " : "") + goal.toShortString(); }
        public boolean ownsInput() { return true; }
        public String tick(Minecraft mc, LocalPlayer pl) {
            // sprinting burns ~10x the food of walking: when food is getting short, walk (a hungry player does)
            BaritoneAPI.getSettings().allowSprint.value = pl.getFoodData().getFoodLevel() >= 16;
            if (t++ == 0) { startHp = pl.getHealth(); b().getCustomGoalProcess().setGoalAndPath(g); return null; }
            if (!until.isEmpty()) for (var e : Perception.lastViewBlocks.entrySet())
                for (String u : until) if (e.getKey().contains(u)) {
                    BaritoneBridge.cancel();
                    return "stopped: saw " + e.getKey() + " at " + ((BlockPos) e.getValue()[1]).toShortString();
                }
            if (pl.getHealth() < startHp - 4) { BaritoneBridge.cancel(); return String.format("stopped: took damage (%.0f -> %.0f HP)", startHp, pl.getHealth()); }
            if (pl.isUnderWater() && pl.getAirSupply() < pl.getMaxAirSupply() / 2) { BaritoneBridge.cancel(); return "stopped: underwater, air " + (100 * Math.max(0, pl.getAirSupply()) / pl.getMaxAirSupply()) + "%"; }
            int limit = g instanceof baritone.api.pathing.goals.GoalYLevel ? 20 * 600 : 20 * 180;
            if (t > limit) { BaritoneBridge.cancel(); return "gave up after " + limit / 1200 + " minutes"; }
            if (t < 10 || busy()) return null;
            double d = pl.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(goal));
            if (g.isInGoal(pl.blockPosition()) || (!(g instanceof baritone.api.pathing.goals.GoalYLevel) && !(g instanceof baritone.api.pathing.goals.GoalXZ) && d < 1.2)) return "arrived";
            if (g instanceof baritone.api.pathing.goals.GoalYLevel)
                return String.format("stopped at y=%d, %d short of y=%d (no route Baritone believes in: water/lava/void nearby? look around)", pl.blockPosition().getY(), pl.blockPosition().getY() - goal.getY(), goal.getY());
            if (g instanceof baritone.api.pathing.goals.GoalXZ) return String.format("stopped %.0f m short (horizontally) — no route it believes in", Math.hypot(pl.getX() - goal.getX(), pl.getZ() - goal.getZ()));
            return String.format("stopped %.1f m short (no known route? explore or look around)", d);
        }
        public void cancel(Minecraft mc) { BaritoneBridge.cancel(); }
    }
}
