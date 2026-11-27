package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayDeque;
import java.util.Properties;

/**
 * Talking to other agents with the game's own three channels: public chat, team chat (/teammsg), private (/msg).
 *
 * How much talking costs is the experimenter's rule (rules.properties on the server, sent to every agent):
 *  - free (the default): a message goes out at once, even while the world stands still for my thinking —
 *    agents may talk things over for as long as they like;
 *  - chat_takes_time: saying it takes the world time a person needs to say it (voice-chat pace, chars_per_second):
 *    the message arrives when that time has passed, and the world must be running for it to pass.
 * Messages are read from the game's chat types (who, which channel, to whom), not from the text on screen.
 */
public final class Talk {
    public static volatile boolean takesTime = false;
    public static volatile double charsPerSecond = 15;
    public static volatile int thinkLimitS = 0;

    static String rulesSaid = null;
    static void onRules(Properties p) {
        takesTime = Boolean.parseBoolean(p.getProperty("chat_takes_time", "false"));
        charsPerSecond = Double.parseDouble(p.getProperty("chars_per_second", "15"));
        thinkLimitS = Integer.parseInt(p.getProperty("think_limit_seconds", "0"));
        String s = "rules here: " + (takesTime
                ? "talking takes time (about " + Math.round(charsPerSecond) + " characters a second; the world must run for a message to arrive)"
                : "talking is free (messages go out at once, even while the world stands still)")
            + "; " + (thinkLimitS > 0 ? "thinking limit " + thinkLimitS + " s (past it the world goes on without me)" : "no thinking limit");
        if (!s.equals(rulesSaid)) { rulesSaid = s; Events.note("rules", s); }
    }

    /** world ticks it takes to say this (when talking takes time): ~half a second to start, then the words */
    public static int ticksToSay(String text) { return 10 + (int) Math.ceil(text.length() / charsPerSecond * 20); }

    record Pending(int atWorldTick, String channel, String to, String text) {}
    static final ArrayDeque<Pending> SPEAKING = new ArrayDeque<>();

    /** "say": send now (free chat), or once the time to say it has passed. Returns what to tell the brain. */
    static String say(String channel, String to, String text) {
        text = text.strip().replace('\n', ' ');
        if (text.isEmpty()) return "nothing to say";
        if (text.length() > 256) return "too long: a chat message holds 256 characters at most";
        if (channel.equals("private") && (to == null || to.isBlank())) return "private message to whom? (to=NAME)";
        if (channel.equals("team") && Minecraft.getInstance().player.getTeam() == null) return "I'm not in a team (no team chat)";
        if (!takesTime) { send(channel, to, text); return "said (" + channel + ")"; }
        int start = SPEAKING.isEmpty() ? HonestClient.worldTicks : SPEAKING.peekLast().atWorldTick();
        int n = ticksToSay(text);
        SPEAKING.addLast(new Pending(start + n, channel, to, text));
        return "saying it (" + channel + "): it arrives after ~" + n + " ticks of world time";
    }

    static void send(String channel, String to, String text) {
        var c = Minecraft.getInstance().player.connection;
        switch (channel) {
            case "team" -> c.sendCommand("teammsg " + text);
            case "private" -> c.sendCommand("msg " + to + " " + text);
            default -> c.sendChat(text);
        }
    }

    /** every client tick: finish what has been said long enough */
    static void tick() {
        while (!SPEAKING.isEmpty() && HonestClient.worldTicks >= SPEAKING.peekFirst().atWorldTick()) {
            Pending p = SPEAKING.removeFirst();
            try { send(p.channel(), p.to(), p.text()); } catch (Exception ignored) {}
        }
    }
    static int stillSpeakingTicks() { return SPEAKING.isEmpty() ? 0 : Math.max(0, SPEAKING.peekLast().atWorldTick() - HonestClient.worldTicks); }

    // ------------------------------------------------------------------ hearing
    public static volatile long heardSeq = 0; public static volatile String lastHeard = null;

    static void onChat(Component msg, PlayerChatMessage signed, ChatType.Bound params) {
        String type = params.chatType().unwrapKey().map(k -> k.identifier().getPath()).orElse("chat");
        String name = params.name().getString();
        String target = params.targetName().map(Component::getString).orElse(null);
        String text = signed != null ? signed.signedContent() : msg.getString();
        String me = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getGameProfile().name();
        JsonObject o = new JsonObject();
        o.addProperty("kind", "chat");
        String channel, from, to;
        switch (type) {
            case "team_msg_command_incoming" -> { channel = "team"; from = name; to = target; }
            case "team_msg_command_outgoing" -> { channel = "team"; from = me; to = target; }
            case "msg_command_incoming" -> { channel = "private"; from = name; to = me; }
            case "msg_command_outgoing" -> { channel = "private"; from = me; to = target; }
            case "emote_command" -> { channel = "public"; from = name; to = null; text = "* " + name + " " + text; }
            default -> { channel = "public"; from = name; to = null; }
        }
        o.addProperty("channel", channel);
        o.addProperty("from", from);
        if (to != null) o.addProperty("to", to);
        o.addProperty("text", text);
        o.addProperty("tick", HonestClient.ticks);
        Events.push(o);
        if (!from.equals(me)) { lastHeard = from + (channel.equals("public") ? "" : " (" + channel + ")") + ": " + text; heardSeq++; }
    }

    // ------------------------------------------------------------------ who's here (the Tab list, my team)
    static JsonArray online(Minecraft mc) {
        JsonArray a = new JsonArray();
        var c = mc.getConnection(); LocalPlayer me = mc.player;
        if (c == null || me == null) return a;
        for (var info : c.getOnlinePlayers()) {
            if (info.getGameMode() == GameType.SPECTATOR) continue;   // (watchers: the agents never learn of them)
            JsonObject j = new JsonObject();
            String n = info.getProfile().name();
            j.addProperty("name", n);
            PlayerTeam t = info.getTeam();
            if (t != null) j.addProperty("team", t.getName());
            if (n.equals(me.getGameProfile().name())) j.addProperty("me", true);
            a.add(j);
        }
        return a;
    }
}
