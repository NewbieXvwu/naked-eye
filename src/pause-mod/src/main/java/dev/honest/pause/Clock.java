package dev.honest.pause;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * One clock for every agent in the world: it runs only while every agent is acting, and stands still while any of
 * them is thinking (or was just alarmed, or asked for a pause). With one agent that's the plain "ESC while thinking".
 *
 * Each agent's client says "I'm holding the world" / "I let go" (the Hold payload); the server freezes the world
 * while anyone holds. Thinking costs no game time, so a slow thinker loses nothing — it only makes everyone wait in
 * real time. To cap that wait: config/naked-eye/clock.properties, think_limit_seconds=N (0 = no limit). Past it the
 * world goes on without that agent until its next action.
 */
public final class Clock {
    public record Hold(boolean on, String why) implements CustomPacketPayload {
        public static final Type<Hold> TYPE = new Type<>(Identifier.fromNamespaceAndPath("naked_eye", "hold"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hold> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, Hold::on, ByteBufCodecs.STRING_UTF8, Hold::why, Hold::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** server -> agent: something about the clock it should know (who is thinking, its thinking time ran out) */
    public record Say(String text) implements CustomPacketPayload {
        public static final Type<Say> TYPE = new Type<>(Identifier.fromNamespaceAndPath("naked_eye", "clock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Say> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Say::text, Say::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static final class Holding { final long sinceMs = System.currentTimeMillis(); String why; boolean expired, joining; }
    static final Map<UUID, Holding> HOLDS = new HashMap<>();

    static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Hold.TYPE, Hold.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Say.TYPE, Say.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Hold.TYPE, (h, ctx) -> onHold(ctx.player(), h));
    }

    static void onHold(ServerPlayer p, Hold h) {
        UUID id = p.getUUID();
        Holding j = HOLDS.get(id); if (j != null && j.joining) { HOLDS.remove(id); }   // (its client speaks: the join hold is over)
        if (!h.on()) { HOLDS.remove(id); apply(p.level().getServer()); return; }   // (letting go is always fine)
        if (!Watch.isAgent(p)) return;                      // (a watcher can't stop the world)
        if (h.on()) {
            Holding x = HOLDS.get(id);
            if (x == null) { x = new Holding(); HOLDS.put(id, x); }
            x.why = h.why();                                // (an expired hold stays expired until the agent acts)
        } else HOLDS.remove(id);
        apply(p.level().getServer());
    }

    /** An agent just joined: its body is in the world before its client has the controls (loading screen, the mod
     *  starting up). Hold the world for it until its client speaks — a blaze burned a restarting agent to death in
     *  those few seconds. (No word for 60 s = a client without the mod: let go.) */
    static void joined(ServerPlayer p) {
        Holding x = new Holding(); x.why = "joining: waiting for its client to take the controls"; x.joining = true;
        HOLDS.put(p.getUUID(), x);
        apply(p.level().getServer());
    }

    static void left(ServerPlayer p) {
        HOLDS.remove(p.getUUID());
        MinecraftServer s = p.level().getServer();
        apply(s);
    }

    static Set<String> lastAgents = Set.of(); static int announceAt = -1;
    static final Set<String> recording = new HashSet<>();
    /** every agent in the world is recorded, each from its own place — also one added after the server started, and
     *  one that reconnects (the recorder's own list is fixed at start-up, and it drops a player who leaves) */
    static void record(MinecraftServer s, String name) {
        try {
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "replay start players " + name);
            recording.add(name);
        } catch (Throwable ignored) {}   // (no ServerReplay installed: nothing to do)
    }
    /** every agent hears who else shares the world, since that decides how its clock behaves (told ~2 s after a
     *  change: a client that has just joined can't take our messages yet) */
    static void watchAgents(MinecraftServer s) {
        Set<String> now = new TreeSet<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) if (Watch.isAgent(p)) now.add(p.getGameProfile().name());
        if (!now.equals(lastAgents) && announceAt < 0) announceAt = s.getTickCount() + 40;
        recording.retainAll(now);   // (gone: its recording ended; start it again if it comes back)
        for (String n : now) if (!recording.contains(n)) record(s, n);
        if (announceAt < 0 || s.getTickCount() < announceAt) return;
        announceAt = -1;
        List<String> what = new ArrayList<>();
        for (String n : now) if (!lastAgents.contains(n) && lastAgents.size() > 0) what.add(n + " joined");
        for (String n : lastAgents) if (!now.contains(n)) what.add(n + " left");
        lastAgents = now;
        Rules.sendAll(s);   // (newcomers need the rules too)
        agentsChanged(s, what.isEmpty() ? "" : String.join(", ", what) + ": ");
    }
    static void agentsChanged(MinecraftServer s, String what) {
        List<ServerPlayer> ag = s.getPlayerList().getPlayers().stream().filter(Watch::isAgent).toList();
        String msg = ag.size() > 1
            ? what + ag.size() + " agents share this world (" + String.join(", ", ag.stream().map(x -> x.getGameProfile().name()).toList())
              + "). The world runs only while every one of us is acting, and stands still while any of us thinks."
            : what + "I'm the only agent in this world.";
        if (ag.size() > 1 || !what.isEmpty()) for (ServerPlayer a : ag) say(a, msg);   // (alone from the start: nothing to say)
    }

    /** frozen while anyone online holds; running when nobody does (a release also lifts an experimenter's freeze,
     *  just as the agent's next action always did) */
    static void apply(MinecraftServer server) {
        HOLDS.keySet().removeIf(id -> { var p = server.getPlayerList().getPlayer(id); return p == null || !Watch.isAgent(p); });
        boolean hold = HOLDS.values().stream().anyMatch(x -> !x.expired);
        if (server.tickRateManager().isFrozen() != hold) server.tickRateManager().setFrozen(hold);
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0 && Rules.load()) Rules.sendAll(server);
        int thinkLimitS = Rules.thinkLimitS;
        watchAgents(server);
        if (HOLDS.values().removeIf(x -> x.joining && System.currentTimeMillis() - x.sinceMs > 60_000)) apply(server);
        if (thinkLimitS <= 0 || HOLDS.isEmpty()) return;
        long now = System.currentTimeMillis(); boolean changed = false;
        for (var e : HOLDS.entrySet()) {
            Holding x = e.getValue();
            if (x.expired || now - x.sinceMs < thinkLimitS * 1000L) continue;
            x.expired = true; changed = true;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) {
                say(p, "I thought for over " + thinkLimitS + " s (the limit here): the world goes on without me until my next action");
                org.slf4j.LoggerFactory.getLogger("naked-eye").info("[clock] {} ran out of thinking time ({})", p.getGameProfile().name(), x.why);
            }
        }
        if (changed) apply(server);
    }

    static void say(ServerPlayer p, String text) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Say.TYPE))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Say(text));
    }

    // ------------------------------------------------------------------ client side (this mod is on the client too)
    /** tell the server I hold the world (thinking, alarmed, paused) or let go of it; false if the server can't take it */
    public static boolean send(boolean on, String why) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(Hold.TYPE)) return false;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Hold(on, why == null ? "" : why));
        return true;
    }
    public static void onSay(java.util.function.Consumer<String> to) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Say.TYPE, (s, ctx) -> to.accept(s.text()));
    }
}
