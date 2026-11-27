package dev.honest.pause;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Every message the agents send, kept: chat/<when the server started>.jsonl (public, team and private alike — the
 * point of letting AIs talk is reading it afterwards). Watchers see team and private messages too, marked as such
 * (the agents never learn that).
 */
public final class Chat {
    static Path file;

    /** a line an agent sent: public chat, or a command (only the talking ones are kept) */
    public static void sent(ServerPlayer p, String raw, boolean command) {
        if (!Watch.isAgent(p)) return;
        String channel = "public", to = null, text = raw;
        if (command) {
            String[] w = raw.strip().split("\\s+", 3);
            String c = w[0].toLowerCase();
            if ((c.equals("msg") || c.equals("tell") || c.equals("w")) && w.length == 3) { channel = "private"; to = w[1]; text = w[2]; }
            else if ((c.equals("teammsg") || c.equals("tm")) && w.length >= 2) {
                channel = "team"; text = raw.strip().substring(w[0].length()).strip();
                var t = p.getTeam(); to = t == null ? null : t.getName();
            } else if (c.equals("me") && w.length >= 2) text = "* " + raw.strip().substring(2).strip();
            else return;
        }
        var server = p.level().getServer();
        String from = p.getGameProfile().name(), ch = channel, target = to, t = text;
        server.execute(() -> {
            write(server, from, ch, target, t);
            if (!ch.equals("public")) {   // watchers hear public chat anyway; show them the rest
                Component line = Component.literal("§7" + (ch.equals("team") ? "[team " + target + "] " + from : "[" + from + " → " + target + "]") + ": §f" + t);
                for (ServerPlayer w : server.getPlayerList().getPlayers()) if (Watch.isWatcher(w)) w.sendSystemMessage(line);
            }
        });
    }

    static synchronized void write(net.minecraft.server.MinecraftServer s, String from, String channel, String to, String text) {
        try {
            if (file == null) {
                Files.createDirectories(Path.of("chat"));
                file = Path.of("chat", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl");
            }
            var o = new com.google.gson.JsonObject();
            o.addProperty("time", System.currentTimeMillis());
            o.addProperty("game_time", s.overworld().getGameTime());
            o.addProperty("channel", channel);
            o.addProperty("from", from);
            if (to != null) o.addProperty("to", to);
            o.addProperty("text", text);
            Files.writeString(file, o + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            org.slf4j.LoggerFactory.getLogger("naked-eye").info("[chat] {} {}{}: {}", channel, from, to == null ? "" : " -> " + to, text);
        } catch (IOException ignored) {}
    }

    /** watchers can't talk to the agents, by chat or by command */
    public static boolean talking(String command) {
        String c = command.strip().split("\\s+", 2)[0].toLowerCase();
        return switch (c) { case "msg", "tell", "w", "teammsg", "tm", "me", "say" -> true; default -> false; };
    }
}
