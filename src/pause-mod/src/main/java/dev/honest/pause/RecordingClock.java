package dev.honest.pause;

import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Recordings skip the time the world stands frozen (the agent thinking): the replay plays straight through, while
 * every packet sent during a freeze (chunks, block updates) is still recorded. How long it thought stays on record:
 * here in the server log, per freeze, and as "accumulated_paused_time" in each replay's metadata.
 */
final class RecordingClock {
    private static boolean frozen;
    private static long frozenAt;
    private static Method all, pause, resume;
    private static boolean unavailable;

    static void tick(MinecraftServer server) {
        boolean now = !server.tickRateManager().runsNormally();
        if (now) for (Object r : recorders()) call(pause, r);   // (also catches recorders started mid-freeze)
        if (now == frozen) return;
        frozen = now;
        if (now) frozenAt = System.currentTimeMillis();
        else {
            for (Object r : recorders()) call(resume, r);
            org.slf4j.LoggerFactory.getLogger("naked-eye").info("[clock] frozen for {} s (thinking)", String.format("%.1f", (System.currentTimeMillis() - frozenAt) / 1000.0));
        }
    }

    private static Collection<?> recorders() {
        if (unavailable) return java.util.List.of();
        try {
            if (all == null) {
                Class<?> c = Class.forName("net.casual.arcade.replay.recorder.player.ReplayPlayerRecorders");
                all = c.getMethod("recorders");
                Class<?> r = Class.forName("net.casual.arcade.replay.recorder.ReplayRecorder");
                pause = r.getMethod("pause"); resume = r.getMethod("resume");
            }
            return (Collection<?>) all.invoke(null);
        } catch (Throwable t) { unavailable = true; return java.util.List.of(); }   // no ServerReplay here
    }

    private static void call(Method m, Object r) { try { m.invoke(r); } catch (Throwable ignored) {} }
}
