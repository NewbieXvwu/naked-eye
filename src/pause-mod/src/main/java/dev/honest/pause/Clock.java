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

    static final class Holding { final long sinceMs = System.currentTimeMillis(); String why; boolean expired; }
    static final Map<UUID, Holding> HOLDS = new HashMap<>();
    static int thinkLimitS = 0; static long limitStamp = -1;

    static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Hold.TYPE, Hold.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Say.TYPE, Say.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Hold.TYPE, (h, ctx) -> onHold(ctx.player(), h));
    }

    static void onHold(ServerPlayer p, Hold h) {
        UUID id = p.getUUID();
        if (!h.on()) { HOLDS.remove(id); apply(p.level().getServer()); return; }   // (letting go is always fine)
        if (!Watch.isAgent(p)) return;                      // (a watcher can't stop the world)
        if (h.on()) {
            Holding x = HOLDS.get(id);
            if (x == null) { x = new Holding(); HOLDS.put(id, x); }
            x.why = h.why();                                // (an expired hold stays expired until the agent acts)
        } else HOLDS.remove(id);
        apply(p.level().getServer());
    }

    static void left(ServerPlayer p) {
        HOLDS.remove(p.getUUID());
        MinecraftServer s = p.level().getServer();
        apply(s);
    }

    static Set<String> lastAgents = Set.of(); static int announceAt = -1;
    /** every agent hears who else shares the world, since that decides how its clock behaves (told ~2 s after a
     *  change: a client that has just joined can't take our messages yet) */
    static void watchAgents(MinecraftServer s) {
        Set<String> now = new TreeSet<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) if (Watch.isAgent(p)) now.add(p.getGameProfile().name());
        if (!now.equals(lastAgents) && announceAt < 0) announceAt = s.getTickCount() + 40;
        if (announceAt < 0 || s.getTickCount() < announceAt) return;
        announceAt = -1;
        List<String> what = new ArrayList<>();
        for (String n : now) if (!lastAgents.contains(n) && lastAgents.size() > 0) what.add(n + " joined");
        for (String n : lastAgents) if (!now.contains(n)) what.add(n + " left");
        lastAgents = now;
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
        if (server.getTickCount() % 20 == 0) loadLimit();
        watchAgents(server);
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

    static void loadLimit() {
        try {
            Path f = Path.of("config", "naked-eye", "clock.properties");
            if (!Files.exists(f)) { thinkLimitS = 0; return; }
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp == limitStamp) return;
            limitStamp = stamp;
            Properties pr = new Properties();
            try (var in = Files.newInputStream(f)) { pr.load(in); }
            thinkLimitS = Integer.parseInt(pr.getProperty("think_limit_seconds", "0").strip());
        } catch (Exception ignored) {}
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
