package dev.honest.pause;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * The agent's running commentary ("hc note …": how it's going, what's next), like subtitles on a video.
 * Client -> server as a custom payload; the server puts it on the action bar of the agent itself (so it lands in
 * the replay, where ReplayMod shows it) and of everyone watching from inside that agent. Other agents never see it.
 */
public record Note(String text) implements CustomPacketPayload {
    public static final Type<Note> TYPE = new Type<>(Identifier.fromNamespaceAndPath("naked_eye", "note"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Note> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Note::text, Note::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** client side: send my note to the server, if it has this mod (false otherwise) */
    public static boolean send(String text) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(TYPE)) return false;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Note(text));
        return true;
    }

    static void onServer(ServerPlayer agent, String text) {
        if (!Watch.isAgent(agent)) return;
        String t = text.strip(); if (t.isEmpty()) return;
        if (t.length() > 160) t = t.substring(0, 157) + "...";
        Component line = Component.literal("» " + t);
        var packet = new ClientboundSetActionBarTextPacket(line);
        agent.connection.send(packet);
        for (Map.Entry<UUID, ServerPlayer> e : Watch.inside.entrySet())
            if (e.getValue() == agent) {
                ServerPlayer w = agent.level().getServer().getPlayerList().getPlayer(e.getKey());
                if (w != null) w.connection.send(packet);
            }
        org.slf4j.LoggerFactory.getLogger("naked-eye").info("[note] {}: {}", agent.getGameProfile().name(), t);
    }
}
