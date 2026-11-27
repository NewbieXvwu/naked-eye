package dev.honest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Things you hear and read: subtitles (the game's own accessibility channel) and chat.
 * Subtitles carry exactly what the vanilla subtitle overlay shows: text + rough direction.
 */
public final class Events {
    private Events() {}

    private static final Deque<JsonObject> LOG = new ArrayDeque<>();
    private static long seq = 0;

    public static synchronized void push(JsonObject o) {
        o.addProperty("seq", ++seq);
        LOG.addLast(o);
        FlightLog.write("event", o.deepCopy());
        while (LOG.size() > 200) LOG.removeFirst();
    }

    public static synchronized JsonArray since(long cursor) {
        JsonArray a = new JsonArray();
        for (JsonObject o : LOG) if (o.get("seq").getAsLong() > cursor) a.add(o);
        return a;
    }

    public static synchronized long cursor() { return seq; }
    public static volatile Vec3 lastHiss; public static volatile int lastHissTick = -999;
    /** did a subtitle containing `what` show up in the last `ticks` ticks? (the "why did I just get hurt" clue) */
    public static synchronized JsonObject recentSound(String what, int ticks) {
        var pl = net.minecraft.client.Minecraft.getInstance().player;
        for (var it = LOG.descendingIterator(); it.hasNext(); ) {
            JsonObject o = it.next();
            if (!o.has("tick")) continue;
            if (pl == null || HonestClient.ticks - o.get("tick").getAsLong() > ticks) break;
            if (o.has("subtitle") && o.get("subtitle").getAsString().contains(what)) return o;
        }
        return null;
    }

    /** Called from our SoundManager hook, same arguments vanilla gives the subtitle overlay. */
    public static void onSound(SoundInstance s, WeighedSoundEvents ev, float range) {
        if (ev == null) return;
        Component sub = ev.getSubtitle();
        if (sub == null) return; // vanilla shows no subtitle for this sound either
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "sound");
        o.addProperty("subtitle", sub.getString());
        if (!s.isRelative()) {
            Vec3 at = new Vec3(s.getX(), s.getY(), s.getZ());
            Vec3 rel = at.subtract(pl.getEyePosition());
            double d = rel.length();
            if (d > range) return; // out of earshot: the overlay would not show it
            double bearing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-rel.x, rel.z)) - pl.getYRot());
            // the overlay only shows "<" or ">" (or nothing when in front); we give 8-way
            o.addProperty("direction", dir8(bearing));
            o.addProperty("loudness", d < range * 0.33 ? "close" : d < range * 0.66 ? "nearby" : "faint");
        }
        o.addProperty("tick", HonestClient.ticks);   // (not pl.tickCount: that restarts at 0 on respawn)
        if (sub.getString().contains("Creeper hisses") && !s.isRelative()) { lastHiss = new Vec3(s.getX(), s.getY(), s.getZ()); lastHissTick = HonestClient.ticks; }
        push(o);
    }

    public static void onChat(Component msg, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("text", msg.getString());
        push(o);
    }

    public static void note(String kind, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("text", text);
        push(o);
    }

    static String dir8(double b) {
        String[] n = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};
        int i = (int) Math.floorMod(Math.round(b / 45.0), 8);
        return n[i];
    }
}
