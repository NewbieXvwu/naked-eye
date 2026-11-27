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
import java.util.Properties;

/**
 * The experimenter's rules for a world, the same for every agent in it: config/naked-eye/rules.properties
 *
 *   think_limit_seconds=0    how long one agent may think while the world waits for it (0 = as long as it likes)
 *   chat_takes_time=false    true: a message takes the world time to say it before it arrives (like talking);
 *                            false: agents may talk as much as they like while the world stands still
 *   chars_per_second=15      speaking speed when chat takes time (voice-chat pace)
 *
 * Re-read when the file changes; every agent's client is sent the current rules (it needs to know how chat works).
 */
public final class Rules {
    public static int thinkLimitS = 0;
    public static boolean chatTakesTime = false;
    public static double charsPerSecond = 15;
    static long stamp = -2;

    /** server -> agent: "key=value" lines */
    public record Sync(String text) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath("naked_eye", "rules"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Sync::text, Sync::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Sync.TYPE, Sync.CODEC);
    }

    static String text() {
        return "think_limit_seconds=" + thinkLimitS + "\nchat_takes_time=" + chatTakesTime + "\nchars_per_second=" + charsPerSecond;
    }

    /** true if the rules changed */
    static boolean load() {
        try {
            Path f = Path.of("config", "naked-eye", "rules.properties");
            if (!Files.exists(f)) f = Path.of("config", "naked-eye", "clock.properties");   // (its earlier name)
            long s = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
            if (s == stamp) return false;
            stamp = s;
            Properties p = new Properties();
            if (s >= 0) try (var in = Files.newInputStream(f)) { p.load(in); }
            thinkLimitS = Integer.parseInt(p.getProperty("think_limit_seconds", "0").strip());
            chatTakesTime = Boolean.parseBoolean(p.getProperty("chat_takes_time", "false").strip());
            charsPerSecond = Math.max(1, Double.parseDouble(p.getProperty("chars_per_second", "15").strip()));
            return true;
        } catch (Exception e) { return false; }
    }

    static void send(ServerPlayer p) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Sync.TYPE))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Sync(text()));
    }
    static void sendAll(MinecraftServer s) { for (ServerPlayer p : s.getPlayerList().getPlayers()) if (Watch.isAgent(p)) send(p); }

    // ------------------------------------------------------------------ client side
    public static void onSync(java.util.function.Consumer<Properties> to) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Sync.TYPE, (s, ctx) -> {
            Properties p = new Properties();
            try { p.load(new java.io.StringReader(s.text())); } catch (Exception ignored) {}
            to.accept(p);
        });
    }
}
