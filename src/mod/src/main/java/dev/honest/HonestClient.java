package dev.honest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HonestClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("honest");
    public static volatile int ticks = 0;
    /** ticks the world actually ran (the client ticks on while frozen): what "wait N ticks" counts */
    public static volatile int worldTicks = 0;
    public static volatile boolean followServerClock = Boolean.parseBoolean(System.getProperty("honest.fastclock", "true"));
    public static volatile int dbgMissTime = -1;
    /** no screen at all (Linux offscreen): never draw. Otherwise the window can be shown and hidden at will */
    public static final boolean noScreen = Boolean.getBoolean("honest.headless");
    /** skip drawing frames: true while the window is hidden (or there is no screen) — the game itself runs on */
    public static volatile boolean headless = noScreen;
    public static volatile boolean shown = !noScreen;
    static boolean startVisibilityDone = false;

    /** Show or hide the game window without touching the game: hidden = no drawing, few frames, the agent plays on.
     *  (call on the client thread) */
    public static String setShown(boolean show) {
        if (noScreen) return "there is no screen here (headless)";
        long h = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
        if (show) { headless = false; org.lwjgl.sdl.SDLVideo.SDL_ShowWindow(h); org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(h); }
        else { org.lwjgl.sdl.SDLVideo.SDL_HideWindow(h); headless = true; }
        shown = show;
        LOG.info("[honest] window " + (show ? "shown" : "hidden (not drawing; the agent keeps playing)"));
        return show ? "shown" : "hidden";
    }

    @Override
    public void onInitializeClient() {
        LOG.info("[honest] client mod loaded");
        try { Api.start(); } catch (Exception e) { LOG.error("[honest] failed to start API", e); }
        Review.load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // frozen world = paused game: hands off the controls (you can't play from the ESC menu), eyes still work
            // once in the world, start hidden unless asked to show (honest.show): saves power, the agent doesn't need it
            if (!startVisibilityDone && mc.player != null) { startVisibilityDone = true; if (!Boolean.getBoolean("honest.show")) setShown(false); }
            boolean frozen = mc.level != null && !mc.level.tickRateManager().runsNormally();
            if (!frozen && mc.level != null) worldTicks++;
            try { Review.tick(mc); } catch (Throwable t) { LOG.warn("[honest] review pose failed", t); }   // (before the eyes look)
            if (Review.ON) {} else if (!frozen || !Pause.allow) Controller.tick(mc); else Controller.releaseKeys(mc);   // (pausing off: not my pause)
            try { ArrowLog.tick(mc); } catch (Throwable t) { LOG.warn("[honest] arrow log failed", t); }
            try { Pause.tick(mc); } catch (Throwable t) { LOG.warn("[honest] pause check failed", t); }
            if (!Review.ON) try { FlightLog.tick(mc); } catch (Throwable t) { LOG.warn("[honest] flight log failed", t); }
            try { Api.tickWaits(mc); } catch (Throwable t) { LOG.warn("[honest] wait check failed", t); }   // wakes waiting requests
            // (frozen: nothing in the world can change, so the eyes rest — that was half a CPU core while thinking)
            if (++ticks % 4 == 0 && mc.player != null && mc.level != null && !frozen) {
                try { Perception.sweep(mc); } catch (Throwable t) { LOG.warn("[honest] sweep failed", t); }
            }
        });
        dev.honest.pause.Clock.onSay(t -> Events.note("pause", t));   // the server's clock: who else shares the world, time limits
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            mc.getSoundManager().addListener(Events::onSound);
            if (BaritoneBridge.present()) BaritoneBridge.install();
        });
        java.nio.file.Path mem = java.nio.file.Path.of("honest_memory.bin");
        Perception.MAP.load(mem);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (ticks % 1200 == 0) Perception.MAP.save(mem); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { Perception.MAP.save(mem); FlightLog.close(); });
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) Events.onChat(msg, "game_message"); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> Events.onChat(msg, "chat"));
    }
}
