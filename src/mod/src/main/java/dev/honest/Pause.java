package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Press ESC": when something alarming happens, slow the whole world down so a slow brain can decide.
 *
 * A dedicated server can't truly pause, but `/tick rate 1` runs it 20x slower, and this client follows the
 * server's clock, so the player, the mobs and the falling gravel all slow down together. The command goes over
 * the experimenter's RCON channel — the Agent itself gets no operator rights (so no `/give` loophole).
 * The next action the brain sends resumes the game. Switch off for multiplayer-style rules: `allow = false`.
 */
public final class Pause {
    public static boolean allow = !"false".equals(System.getProperty("honest.pause", "true"));
    /** "freeze" = a true pause (needs the honest-pause mod on the server); "slow" = vanilla-only fallback, 1/20 speed */
    public static String mode = System.getProperty("honest.pause.mode", "freeze");
    /** "ESC while thinking": freeze the world whenever the brain has nothing running, resume on its next action.
     *  A human in single-player may sit on the pause menu for as long as they like; the world only moves while I act. */
    public static boolean idle = !"false".equals(System.getProperty("honest.pause.idle", "true"));
    public static final java.util.concurrent.atomic.AtomicInteger actsInFlight = new java.util.concurrent.atomic.AtomicInteger();
    public static volatile long lastActEndMs = 0;
    static int idleTicks = 0;
    public static volatile String paused = null;       // reason, or null when running
    static float resumeRate = 20f;
    static long pausedAtMs = 0, resumedAtMs = 0;
    static float lastHp = -1; static int lastAir = -1;
    static final Set<Integer> alarmedMobs = new HashSet<>();
    static final Map<String, Integer> quietUntil = new HashMap<>(); // cause -> client tick
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "honest-rcon"); t.setDaemon(true); return t; });

    /** called every client tick */
    public static void tick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (pl == null) return;
        float hp = pl.getHealth(); int air = pl.getAirSupply();
        // joined (or restarted) into a world that is still frozen from an earlier pause: own it, so the next action resumes
        // someone else unfroze the world under my thinking-pause: let go of it, so the idle pause can freeze it again
        if ("idle".equals(pausedKind) && paused != null && mc.level != null && mc.level.tickRateManager().runsNormally()
                && System.currentTimeMillis() - pausedAtMs > 3000) { paused = null; idleTicks = 0; }
        if (allow && paused == null && mc.level != null && !mc.level.tickRateManager().runsNormally() && mode.equals("freeze") && !serverClock()
                && System.currentTimeMillis() - resumedAtMs > 5000) { // (not the few ticks before our own unfreeze arrives)
            paused = "the game was already paused when I (re)joined"; pausedKind = idle ? "idle" : "manual"; pausedAtMs = System.currentTimeMillis();
        }
        if (paused != null && !"idle".equals(pausedKind) && System.currentTimeMillis() - pausedAtMs > 180_000) resume("auto-resumed after 3 minutes with no decision");
        if (allow && paused == null && !pl.isDeadOrDying()) {
            String why = null, kind = null;
            var hiss = Events.recentSound("Creeper hisses", 3);   // "Creeper hisses": the 1.5 s fuse has started somewhere near
            if (false && hiss != null && hp >= lastHp) { why = "a creeper is HISSING (" + (hiss.has("direction") ? hiss.get("direction").getAsString() : "?") + ", " + (hiss.has("loudness") ? hiss.get("loudness").getAsString() : "") + ") — it blows in ~1.5 s"; kind = "hiss"; }
            else if (lastHp >= 0 && hp < lastHp) { why = String.format("hurt %.0f -> %.0f HP%s", lastHp, hp, Api.hurtCause(pl)); kind = "hurt"; }
            else if (pl.isUnderWater() && air < pl.getMaxAirSupply() / 2 && lastAir >= pl.getMaxAirSupply() / 2) { why = "half my air is gone"; kind = "air"; }
            else if (pl.isOnFire() || pl.isInLava()) { why = pl.isInLava() ? "I'm in lava" : "I'm on fire"; kind = "fire"; }
            else {
                for (Entity e : mc.level.getEntities(pl, pl.getBoundingBox().inflate(5))) {
                    if (!(e instanceof Enemy) || alarmedMobs.contains(e.getId())) continue;
                    // mobs that are often peaceful (a spider by day, an enderman, a zombified piglin): only when they act hostile
                    if ((e instanceof net.minecraft.world.entity.monster.spider.Spider || e instanceof net.minecraft.world.entity.monster.Enderman
                            || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin)
                            && e instanceof net.minecraft.world.entity.Mob m && !m.isAggressive()) continue;
                    if (!Perception.anyPointVisible(mc.level, pl.getEyePosition(), e.getBoundingBox())) continue;
                    alarmedMobs.add(e.getId());
                    why = "a " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath() + " is " + Math.round(e.distanceTo(pl)) + " m from me"; kind = "mob";
                    break;
                }
            }
            // mid-fight, the fight IS the decision: pausing on every hit restarted it each time and the skeleton out-traded me.
            // Only break into a fight when it's going badly (HP low) or something new shows up besides my target.
            boolean fighting = Controller.task instanceof Fight;
            if (fighting && ("hurt".equals(kind) && hp > 6 || "mob".equals(kind))) why = null;
            if ("hurt".equals(kind) && pl.isOnFire() && "fire".equals(pausedKindLast) && HonestClient.ticks - lastFirePause < 200) why = null;
            if (why != null && pl.isOnFire()) { pausedKindLast = "fire"; lastFirePause = HonestClient.ticks; }
            if (why != null && HonestClient.ticks >= quietUntil.getOrDefault(kind, 0)) pause(why, kind);
        }
        // idle: the last action has returned and nothing is moving -> freeze until the next decision (3 ticks of grace,
        // so a script chaining actions back to back doesn't flap the server)
        // (dead counts too: deciding on the death screen is thinking, and the others wait for it)
        if (allow && idle && mode.equals("freeze") && paused == null && actsInFlight.get() == 0
                && System.currentTimeMillis() - lastActEndMs > 100 && Controller.idleNow(pl)) {
            if (++idleTicks >= 3) pause("thinking (idle — the world waits for my next action)", "idle");
        } else idleTicks = 0;
        speedGuard(mc);
        lastHp = hp; lastAir = air;
    }

    /** fast-forward (`/tick rate N`) is only fair while this client keeps pace: the server sends the rate, the client
     *  follows it, and both run N/20 times faster together. If the client falls behind, the world outruns me (like
     *  `/tick sprint`) — then drop back to normal speed. Measured: this box keeps up at 60, not at 100. */
    static long guardMs = 0; static int guardTicks = 0;
    static void speedGuard(Minecraft mc) {
        long now = System.currentTimeMillis();
        // (while frozen the client idles at 20 tps whatever the rate: measure only stretches where the world ran throughout)
        if (guardMs == 0 || mc.level == null || mc.level.tickRateManager().isFrozen()) { guardMs = now; guardTicks = HonestClient.ticks; return; }
        if (now - guardMs < 5000) return;
        float want = mc.level.tickRateManager().tickrate();
        float got = (HonestClient.ticks - guardTicks) * 1000f / (now - guardMs);
        guardMs = now; guardTicks = HonestClient.ticks;
        if (want > 21 && got < want * 0.85f) {
            // step down to what this machine did manage (whole multiples of 20), rather than all the way to 1x
            int next = Math.max(20, (int) (got / 20) * 20 >= want ? (int) want - 20 : (int) (got / 20) * 20);
            Events.note("pause", String.format(Locale.ROOT, "fast-forward %.0fx -> %dx: the world ran at %.0f tps but I only managed %.0f", want / 20, next / 20, want, got));
            IO.submit(() -> rcon("tick rate " + next));
        }
    }

    static String pausedKind = "manual"; static String pausedKindLast = ""; static int lastFirePause = -9999;
    public static void pause(String why, String kind) {
        if (paused != null) {   // already frozen for thinking; an explicit pause just relabels it (world is frozen already)
            if ("idle".equals(pausedKind) && !"idle".equals(kind)) { paused = why; pausedKind = kind; Events.note("pause", "⏸ PAUSED: " + why); }
            return;
        }
        paused = why; pausedKind = kind; pausedAtMs = System.currentTimeMillis();
        if ("idle".equals(kind)) { hold(true, why); return; }   // silent: it happens after every action
        Events.note("pause", "⏸ PAUSED" + (mode.equals("slow") ? " (world at 1/20 speed)" : "") + ": " + why + " — your next action resumes");
        if (mode.equals("freeze")) { hold(true, why); return; }
        IO.submit(() -> {
            String q = rcon("tick query");
            Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(q == null ? "" : q.replaceAll(".*[Tt]arget tick rate: ", ""));
            if (m.find()) { try { float r = Float.parseFloat(m.group(1)); if (r > 1.5f) resumeRate = r; } catch (Exception ignored) {} }
            rcon("tick rate 1");
        });
    }

    public static void resume(String why) {
        if (paused == null) return;
        String was = paused;
        paused = null; resumedAtMs = System.currentTimeMillis();
        // the same kind of alarm stays quiet for 2 s of game time, or continuous damage would re-pause every hit
        boolean wasIdle = "idle".equals(pausedKind);
        if (!wasIdle) { quietUntil.put(pausedKind, HonestClient.ticks + 40); Events.note("pause", "▶ resumed (" + why + ")"); }
        idleTicks = 0;
        float r = resumeRate;
        if (mode.equals("freeze")) { hold(false, why); return; }
        IO.submit(() -> rcon(String.format(Locale.ROOT, "tick rate %.1f", r)));
    }

    /** hold the world / let go. The server's clock (honest-pause mod) runs the world only while no agent holds it, so
     *  several agents can share a world: it stands still while any of us thinks. Without it, fall back to RCON. */
    static void hold(boolean on, String why) {
        if (serverClock()) dev.honest.pause.Clock.send(on, why);
        else IO.submit(() -> rcon(on ? "tick freeze" : "tick unfreeze"));
    }
    static boolean serverClock() {
        try { return net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.honest.pause.Clock.Hold.TYPE); }
        catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ minimal RCON client
    static String rcon(String cmd) {
        int port = Integer.getInteger("honest.rcon.port", 25575);
        String pass = System.getProperty("honest.rcon.pass", "honest");
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream(); InputStream in = s.getInputStream();
            send(out, 1, 3, pass); read(in);
            send(out, 2, 2, cmd);
            return read(in);
        } catch (IOException e) {
            Events.note("pause", "couldn't reach the server's RCON to change the tick rate: " + e.getMessage());
            return null;
        }
    }
    static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array()); out.flush();
    }
    static String read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] lenB = d.readNBytes(4);
        int len = ByteBuffer.wrap(lenB).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = d.readNBytes(len);
        return new String(rest, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
    }
}
