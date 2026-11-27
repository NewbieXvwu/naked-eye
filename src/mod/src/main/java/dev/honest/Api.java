package dev.honest;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The agent's ONLY window into the game (port 7070), plus an experimenter-only port (7071)
 * that exposes ground truth for verification. The agent process never runs code in this JVM.
 */
public final class Api {
    private Api() {}

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** agent API port (the experimenter's is the next one); a second client on the same machine needs its own */
    static final int PORT = Integer.getInteger("honest.port", 7070);

    static final Object ACTING = new Object();
    public static void start() throws IOException {
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        agent.createContext("/observe", ex -> handle(ex, () -> {
            JsonObject o = observe(query(ex));
            JsonObject l = new JsonObject(); l.add("o", o); FlightLog.write("observe", l);   // exactly what the brain was shown
            return o; }));
        agent.createContext("/map", ex -> handle(ex, () -> {
            int r = Integer.parseInt(query(ex).getOrDefault("r", "12"));
            JsonObject j = new JsonObject();
            j.addProperty("sketch", onClient(() -> Perception.sketch(Minecraft.getInstance(), Math.min(r, 40))));
            return j;
        }));
        agent.createContext("/places", ex -> handle(ex, () -> { // the cave as a graph of places, in words
            JsonObject j = new JsonObject();
            j.addProperty("text", onClient(() -> Places.describe(Minecraft.getInstance())));
            return j;
        }));
        agent.createContext("/recall", ex -> handle(ex, () -> {
            String what = query(ex).getOrDefault("block", "lava");
            JsonObject j = new JsonObject();
            j.addProperty("text", onClient(() -> {
                var pl = Minecraft.getInstance().player;
                java.util.BitSet ids = new java.util.BitSet();
                for (var st : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY)
                    if (Perception.blockName(st).contains(what) && !(what.equals("water") || what.equals("lava")) || Perception.blockName(st).equals(what))
                        ids.set(net.minecraft.world.level.block.Block.getId(st));
                var hits = Perception.MAP.recall(ids::get, pl.blockPosition(), 400);
                if (hits.isEmpty()) return "I don't remember seeing any " + what;
                // group into clusters so a lava lake is one line, not 300
                StringBuilder sb = new StringBuilder("I remember " + what + " at (nearest first):\n");
                java.util.List<BlockPos> heads = new java.util.ArrayList<>(); java.util.List<Integer> counts = new java.util.ArrayList<>();
                for (BlockPos h : hits) {
                    int k = -1; for (int i = 0; i < heads.size(); i++) if (heads.get(i).distManhattan(h) <= 8) { k = i; break; }
                    if (k < 0) { heads.add(h); counts.add(1); } else counts.set(k, counts.get(k) + 1);
                }
                for (int i = 0; i < Math.min(8, heads.size()); i++)
                    sb.append("  ").append(heads.get(i).toShortString()).append("  ").append(Places.rel(pl, heads.get(i)))
                      .append(counts.get(i) > 1 ? "  (" + counts.get(i) + " blocks around there)" : "").append('\n');
                return sb.toString();
            }));
            return j;
        }));
        agent.createContext("/sonar", ex -> handle(ex, () -> {
            JsonObject j = new JsonObject();
            j.addProperty("text", onClient(() -> Sonar.describe(Minecraft.getInstance())));
            return j;
        }));
        // actions one at a time (one body); looking (observe, status…) still answers while an action waits on the world —
        // with other agents in it, the world may stand still for a long time under my pending action
        // A new action supersedes one still waiting (its caller may be gone: a killed hc, a dropped connection), so a
        // stale wait can't block the next decision while the world stands still for someone else's thinking.
        agent.createContext("/act", ex -> handle(ex, () -> {
            JsonObject a = JsonParser.parseString(body(ex)).getAsJsonObject();
            for (Wait w : WAITS) if (w.latch.getCount() > 0) { w.woke = "superseded by a newer action"; w.latch.countDown(); }
            synchronized (ACTING) { return act(a); } }));
        agent.createContext("/status", ex -> handle(ex, () -> onClient(() -> {
            JsonObject j = Controller.status();
            var lvl = Minecraft.getInstance().level;
            j.addProperty("client_ticks", HonestClient.ticks);           // for measuring how fast this machine keeps up
            if (lvl != null) j.addProperty("game_time", lvl.getGameTime());
            if (lvl != null) { j.addProperty("tick_rate", lvl.tickRateManager().tickrate()); j.addProperty("frozen", !lvl.tickRateManager().runsNormally()); }
            j.addProperty("holding_world", Pause.paused == null ? "no" : Pause.pausedKind + ": " + Pause.paused);
            j.addProperty("acts_in_flight", Pause.actsInFlight.get());
            j.addProperty("clock", Pause.serverClock() ? "server" : "rcon");
            return j; })));
        // for the human watching, not for the agent (it tells nothing about the world): /window?show=1|0
        agent.createContext("/window", ex -> handle(ex, () -> onClient(() -> {
            String v = query(ex).get("show"); JsonObject j = new JsonObject();
            j.addProperty("result", v == null ? (HonestClient.shown ? "shown" : "hidden") : HonestClient.setShown("1".equals(v)));
            return j; })));
        agent.setExecutor(Executors.newSingleThreadExecutor());
        agent.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "honest-api"); t.setDaemon(true); return t; }));
        agent.start();

        // experimenter-only: ground truth for the tests. A contest run switches it off (HONEST_DEBUG=0 -> -Dhonest.debug=false)
        if ("false".equals(System.getProperty("honest.debug"))) { HonestClient.LOG.info("[honest] agent API on 127.0.0.1:" + PORT + " (experimenter API off)"); return; }
        HttpServer debug = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT + 1), 0);
        debug.createContext("/debug/raw", ex -> handle(ex, () -> onClient(Api::raw)));
        debug.createContext("/debug/known", ex -> handle(ex, () -> {
            Map<String, String> q = query(ex);
            BlockState s = Perception.MAP.get(Integer.parseInt(q.get("x")), Integer.parseInt(q.get("y")), Integer.parseInt(q.get("z")));
            JsonObject j = new JsonObject(); j.addProperty("known", s == null ? null : Perception.blockName(s)); return j;
        }));
        debug.createContext("/debug/render", ex -> handle(ex, () -> {
            HonestClient.headless = HonestClient.noScreen || "0".equals(query(ex).get("on"));
            JsonObject j = new JsonObject(); j.addProperty("headless", HonestClient.headless); return j;
        }));
        debug.createContext("/debug/input", ex -> handle(ex, () -> onClient(() -> {
            Minecraft mc = Minecraft.getInstance(); JsonObject j = new JsonObject();
            try { var f = Minecraft.class.getDeclaredField("missTime"); f.setAccessible(true); j.addProperty("missTime", f.getInt(mc)); } catch (Exception e) { j.addProperty("missTime", "?"); }
            j.addProperty("screen", String.valueOf(mc.gui.screen())); j.addProperty("overlay", String.valueOf(mc.gui.overlay()));
            j.addProperty("attackDown", mc.options.keyAttack.isDown()); j.addProperty("grabbed", mc.mouseHandler.isMouseGrabbed());
            j.addProperty("hit", String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType()));
            j.addProperty("destroying", mc.gameMode.isDestroying()); j.addProperty("usingItem", mc.player.isUsingItem());
            j.addProperty("windowActive", mc.isWindowActive()); j.addProperty("client_ticks", HonestClient.ticks);
            return j; })));
        debug.createContext("/debug/gamma", ex -> handle(ex, () -> onClient(() -> {
            double v = Double.parseDouble(query(ex).get("v"));
            Minecraft.getInstance().options.gamma().set(v);
            JsonObject j = new JsonObject(); j.addProperty("gamma", Minecraft.getInstance().options.gamma().get()); return j; })));
        debug.createContext("/debug/use", ex -> handle(ex, () -> onClient(() -> {
            Minecraft mc = Minecraft.getInstance(); JsonObject j = new JsonObject();
            j.addProperty("hit", String.valueOf(mc.hitResult));
            j.addProperty("main_hand", String.valueOf(mc.player.getMainHandItem()));
            j.addProperty("hands_busy", mc.player.isHandsBusy());
            j.addProperty("destroying", mc.gameMode.isDestroying());
            j.addProperty("game_mode", String.valueOf(mc.gameMode.getPlayerMode()));
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh)
                j.addProperty("useItemOn", String.valueOf(mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, bh)));
            return j; })));
        debug.createContext("/debug/baritone", ex -> handle(ex, () -> {
            Map<String, String> q = query(ex); JsonObject j = new JsonObject();
            if (!BaritoneBridge.present()) { j.addProperty("present", false); return j; }
            if (q.containsKey("break")) BaritoneBridge.setAllowBreak("1".equals(q.get("break")));
            if (q.containsKey("place")) BaritoneBridge.setAllowPlace("1".equals(q.get("place")));
            j.addProperty("ok", true); return j;
        }));
        debug.createContext("/debug/screenshot", ex -> handle(ex, () -> onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "honest_" + System.currentTimeMillis() + ".png",
                    mc.gameRenderer.mainRenderTarget(), 1, msg -> HonestClient.LOG.info("[honest] " + msg.getString()));
            JsonObject j = new JsonObject(); j.addProperty("ok", true); return j; })));
        debug.createContext("/debug/aim", ex -> handle(ex, () -> onClient(() -> Controller.aimReport(Minecraft.getInstance(), query(ex).containsKey("reset")))));
        debug.createContext("/debug/arrows", ex -> handle(ex, () -> onClient(() -> ArrowLog.report(query(ex).containsKey("reset")))));
        debug.createContext("/debug/save", ex -> handle(ex, () -> { // called by run_client.sh before it kills the client
            Perception.MAP.save(java.nio.file.Path.of("honest_memory.bin")); return new JsonObject(); }));
        debug.createContext("/debug/forget", ex -> handle(ex, () -> {
            var q = query(ex);
            if (q.containsKey("box")) { // x1,y1,z1,x2,y2,z2: forget just this region (test fixtures)
                int[] b = java.util.Arrays.stream(q.get("box").split(",")).mapToInt(Integer::parseInt).toArray();
                for (BlockPos p : BlockPos.betweenClosed(b[0], b[1], b[2], b[3], b[4], b[5])) Perception.MAP.forget(p);
            } else Perception.MAP.clear();
            return new JsonObject();
        }));
        debug.setExecutor(Executors.newSingleThreadExecutor());
        debug.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "honest-debug"); t.setDaemon(true); return t; }));
        debug.start();
        HonestClient.LOG.info("[honest] agent API on 127.0.0.1:" + PORT + ", experimenter API on 127.0.0.1:" + (PORT + 1));
    }

    // ------------------------------------------------------------------
    static JsonObject observe(Map<String, String> q) {
        long cursor = Long.parseLong(q.getOrDefault("cursor", "0"));
        JsonObject o = onClient(() -> Perception.observe(Minecraft.getInstance()));
        o.add("events", Events.since(cursor));
        o.addProperty("cursor", Events.cursor());
        o.add("control", onClient(Controller::status));
        return o;
    }

    static JsonObject act(JsonObject a) {
        Pause.actsInFlight.incrementAndGet();   // the world runs while an action is in flight, and freezes once none is
        JsonObject res = null;
        try { return res = act0(a); }
        finally {
            Pause.lastActEndMs = System.currentTimeMillis(); Pause.actsInFlight.decrementAndGet();
            JsonObject l = new JsonObject(); l.add("req", a); if (res != null) l.add("res", res); FlightLog.write("act", l);
        }
    }

    static JsonObject act0(JsonObject a) {
        String type = a.get("type").getAsString();
        if (Review.ON) { JsonObject r = new JsonObject(); r.addProperty("result", "refused: review mode (this client is watching a recording)"); return r; }
        if (type.equals("step")) return step(a);
        Minecraft mc = Minecraft.getInstance();
        JsonObject res = new JsonObject();
        String msg = onClient(() -> {
            LocalPlayer pl = mc.player;
            if (pl == null) return "not in world";
            // any decision from the brain = un-pause (like closing the ESC menu); "pause" itself and pure policy changes don't
            boolean keep = a.has("keep_paused") && a.get("keep_paused").getAsBoolean();   // frame-by-frame play
            if (Pause.paused != null && !keep && !type.equals("pause") && !type.equals("pause_policy") && !type.equals("note")
                    && !(type.equals("say") && !Talk.takesTime)) {   // (free chat: talk while the world stands still)
                // only a deliberate decision un-pauses; a script blindly carrying on with its plan must not
                // (it happened: a wood-gathering script kept clicking through the pause while a skeleton shot me dead)
                // a thinking pause is lifted by any action at all; an ALARM pause only by a deliberate one
                if (!"idle".equals(Pause.pausedKind) && !(a.has("resume") && a.get("resume").getAsBoolean()))
                    return "refused: the game is PAUSED (" + Pause.paused + ") — look, decide, and send an action with resume=true";
                Pause.resume("brain decided: " + type);
            }
            switch (type) {
                case "note" -> {   // the brain's commentary: how it's going, what's next (log, watchers, replay)
                    String t = a.get("text").getAsString();
                    Events.note("note", t);
                    try { dev.honest.pause.Note.send(t); } catch (Throwable ignored) {}
                }
                case "pause" -> Pause.pause(a.has("why") ? a.get("why").getAsString() : "I want to think", "manual");
                case "pause_policy" -> {
                    if (a.has("allow")) Pause.allow = a.get("allow").getAsBoolean();
                    if (a.has("idle")) Pause.idle = a.get("idle").getAsBoolean();
                    if (!Pause.allow || !Pause.idle && "idle".equals(Pause.pausedKind)) Pause.resume("pausing disabled");
                }
                case "look" -> Controller.lookAngles(a.get("yaw").getAsFloat(), a.get("pitch").getAsFloat());
                case "turn" -> Controller.lookAngles(pl.getYRot() + f(a, "dyaw", 0), pl.getXRot() + f(a, "dpitch", 0));
                case "track" -> { if (a.has("human")) Controller.humanAim = a.get("human").getAsBoolean(); Controller.trackId = a.has("id") ? a.get("id").getAsInt() : 0; Controller.trackLost = 0; }
                case "look_at_entity" -> { // put the crosshair on something I can see — like a player does, by eye, not by coordinates
                    var e = mc.level.getEntity(a.get("id").getAsInt());
                    if (e == null || !Perception.anyPointVisible(mc.level, pl.getEyePosition(), e.getBoundingBox())) return "I can't see that";
                    Controller.lookAt(pl, e.getBoundingBox().getCenter());
                }
                case "look_at" -> Controller.lookAt(pl, vec(a));
                case "hold" -> { int t = a.has("ticks") ? a.get("ticks").getAsInt() : 20;
                    for (JsonElement k : a.getAsJsonArray("keys")) Controller.hold(k.getAsString(), t); }
                case "release" -> { if (a.has("keys")) for (JsonElement k : a.getAsJsonArray("keys")) Controller.release(k.getAsString()); else Controller.releaseAll(); }
                case "click" -> { if (!Controller.click(mc, a.get("key").getAsString())) return "rate limited (max " + Controller.MAX_CPS + "/s)"; }
                case "hotbar" -> { // press the number key, exactly like a player (vanilla syncs it to the server)
                    int i = Math.max(0, Math.min(8, a.get("slot").getAsInt()));
                    Controller.press(mc.options.keyHotbarSlots[i]);
                }
                case "drop" -> { // = open inventory, hover the slot, ctrl+Q (whole stack)
                    if (pl.containerMenu != pl.inventoryMenu) return "close the open screen first";
                    // the open-inventory ctrl+Q click stopped working on this server; do what a player does without the
                    // inventory open: put the stack in hand and tap Q once per item
                    Controller.dropQueue.clear();
                    for (JsonElement e : a.getAsJsonArray("slots")) Controller.dropQueue.add(e.getAsInt());
                }
                case "to_hotbar" -> { // = hover an inventory slot and press a number key
                    int from = a.get("slot").getAsInt(), hb = a.get("hotbar").getAsInt();
                    if (pl.containerMenu != pl.inventoryMenu) return "close the open screen first";
                    if (from < 9 || from > 35) return "slot must be a main-inventory slot (9-35)";
                    mc.gameMode.handleContainerInput(pl.inventoryMenu.containerId, from, hb,
                            net.minecraft.world.inventory.ContainerInput.SWAP, pl);
                }
                case "dig" -> { var d = new Controller.Dig(bpos(a)); d.force = a.has("force") && a.get("force").getAsBoolean(); Controller.start(mc, d); }
                case "goto" -> { // a place / frontier / mark from the last `places` listing
                    String id = a.get("id").getAsString();
                    BlockPos t = Places.LAST.get(id);
                    if (t == null) t = Places.MARKS.get(id);
                    if (t == null) return "unknown id '" + id + "' — list places first";
                    Controller.start(mc, new BaritoneBridge.Go(t));
                }
                case "mark" -> {
                    String name = a.get("name").getAsString();
                    if (a.has("remove") && a.get("remove").getAsBoolean()) { Places.MARKS.remove(name); Places.saveMarks(); return "forgot mark '" + name + "'"; }
                    Places.MARKS.put(name, pl.blockPosition());
                    Places.saveMarks();
                    return "marked '" + name + "' at " + pl.blockPosition().toShortString();
                }
                case "mine" -> {
                    java.util.Set<String> u = new java.util.HashSet<>();
                    if (a.has("until")) for (JsonElement e : a.getAsJsonArray("until")) u.add(e.getAsString());
                    Mine m = new Mine(a.get("dir").getAsString(), a.has("steps") ? a.get("steps").getAsInt() : 16,
                            !a.has("down") || a.get("down").getAsBoolean(), u);
                    if (a.has("up") && a.get("up").getAsBoolean()) m = m.up();
                    if (a.has("spiral")) m.spiral = a.get("spiral").getAsInt();
                    m.torchEvery = a.has("torch_every") ? a.get("torch_every").getAsInt() : 8; // 0 = don't light the way
                    if (a.has("grab")) { java.util.Set<String> g = new java.util.HashSet<>(); for (JsonElement e : a.getAsJsonArray("grab")) g.add(e.getAsString()); m.grab = g; }
                    Controller.start(mc, m);
                }
                case "fight" -> { Fight ft = new Fight(a.get("id").getAsInt(),
                        !a.has("crit") || a.get("crit").getAsBoolean(), a.has("kite") && a.get("kite").getAsBoolean(), // backing off measured worse vs 2 zombies
                        a.has("weapon") ? a.get("weapon").getAsString() : "auto", a.has("stop_hp") ? a.get("stop_hp").getAsFloat() : 6f);
                    if (a.has("strafe")) ft.strafe = a.get("strafe").getAsBoolean();
                    if (a.has("wall")) ft.wall = a.get("wall").getAsBoolean();
                    Controller.start(mc, ft); }
                case "hit" -> Controller.start(mc, new Controller.Hit(a.get("id").getAsInt(), a.has("swings") ? a.get("swings").getAsInt() : 1));
                case "place" -> {
                    String item = a.has("item") ? a.get("item").getAsString() : null;
                    if (a.has("x")) Controller.start(mc, new Controller.Place(bpos(a), item));
                    else { // no coordinates: "put it down somewhere next to me" — try the likely spots in turn
                        var spots = nearbySpots(mc, pl);
                        if (spots.isEmpty()) return "no free spot with something solid to stand it on within 2 blocks";
                        Controller.start(mc, new Controller.PlaceAny(spots, item));
                    }
                }
                case "scoop" -> Controller.start(mc, new Controller.Scoop(bpos(a)));
                case "interact" -> Controller.start(mc, new Controller.Interact(bpos(a)));
                case "use_at" -> Controller.start(mc, new Controller.UseAt(vec(a)));
                case "walk" -> Controller.start(mc, new Controller.Walk(bpos(a), a.has("risk") ? a.get("risk").getAsDouble() : 2.0));
                case "go" -> {
                    if (!BaritoneBridge.present()) return "baritone not installed (use walk)";
                    BaritoneBridge.Go go = a.has("y_level") ? new BaritoneBridge.Go(a.get("y_level").getAsInt())
                            : !a.has("y") ? new BaritoneBridge.Go(a.get("x").getAsInt(), a.get("z").getAsInt(), true)
                            : new BaritoneBridge.Go(bpos(a), a.has("near") && a.get("near").getAsBoolean());
                    if (a.has("until")) { java.util.Set<String> u = new java.util.HashSet<>(); for (JsonElement e : a.getAsJsonArray("until")) u.add(e.getAsString()); go.until(u); }
                    Controller.start(mc, go);
                }
                case "look_around" -> Controller.start(mc, new Controller.LookAround(pl, f(a, "pitch", 10)));
                case "craft" -> { return Crafting.craft(mc, a.get("item").getAsString(), a.has("count") ? a.get("count").getAsInt() : 1); }
                case "slot_click" -> { // a mouse click on a slot of the open screen
                    String mode = a.has("mode") ? a.get("mode").getAsString() : "pickup";
                    int button = a.has("button") ? a.get("button").getAsInt() : 0;
                    mc.gameMode.handleContainerInput(pl.containerMenu.containerId, a.get("slot").getAsInt(), button,
                            net.minecraft.world.inventory.ContainerInput.valueOf(mode.toUpperCase(java.util.Locale.ROOT)), pl);
                }
                case "close_screen" -> { if (mc.gui.screen() != null) pl.closeContainer(); }
                case "stop" -> Controller.stop(mc);
                case "chat" -> { String t = a.get("text").getAsString();   // "/x" is a command, as when typed in the chat box
                    if (t.startsWith("/")) pl.connection.sendCommand(t.substring(1)); else pl.connection.sendChat(t); }
                case "say" -> { return Talk.say(a.has("channel") ? a.get("channel").getAsString() : "public",
                                                 a.has("to") ? a.get("to").getAsString() : null, a.get("text").getAsString()); }
                case "respawn" -> { pl.respawn(); mc.gui.setScreen(null); }
                case "wait" -> {}
                case "reflex" -> { if (a.has("anti_drown")) Controller.antiDrown = a.get("anti_drown").getAsBoolean();
                                   if (a.has("anti_suffocate")) Controller.antiSuffocate = a.get("anti_suffocate").getAsBoolean(); }
                default -> { return "unknown action " + type; }
            }
            return "ok";
        });
        res.addProperty("result", msg);
        // optionally block until the task finishes (or N ticks pass), then report what you now see
        final int waitTicks = a.has("wait") ? a.get("wait").getAsInt()
                : type.equals("say") && Talk.takesTime ? Talk.stillSpeakingTicks() + 1 : 0;   // (saying it takes that long)
        if (waitTicks > 0) {
            // counted in game ticks, not wall time: under `/tick rate 60` a 400-tick wait is 400 ticks, not 1200
            boolean waitForTask = (!type.equals("wait") && !type.equals("hold") && !type.equals("say")) || a.has("until_idle");
            // checked inside the game tick itself (Api.tickWaits), so the brain is woken on the very tick something
            // happens, at any game speed — not on a wall-clock poll that gets coarser the faster the game runs
            Wait w = onClient(() -> {
                var p = Minecraft.getInstance().player;
                Wait x = new Wait(HonestClient.worldTicks + Math.min(waitTicks, 1200), waitForTask,
                        p == null ? 0 : p.getHealth(), p == null ? 0 : p.getAirSupply());
                WAITS.add(x); return x;
            });
            // no wall-clock limit: the world may stand still for a long time while another agent thinks. Give up only
            // if the client itself stops ticking (stalled) for 10 s.
            try {
                int seen = HonestClient.ticks; long stillSince = System.currentTimeMillis();
                while (!w.latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (HonestClient.ticks != seen) { seen = HonestClient.ticks; stillSince = System.currentTimeMillis(); }
                    else if (System.currentTimeMillis() - stillSince > 10_000) break;
                }
            } catch (InterruptedException ignored) {}
            WAITS.remove(w);
            if (w.woke != null) res.addProperty("woke", w.woke);
            res.add("observation", observe(Map.of("cursor", a.has("cursor") ? a.get("cursor").getAsString() : "0")));
        }
        return res;
    }

    /** a request waiting for its action: resolved by the game tick (task done, N ticks passed, or an alarm) */
    static final class Wait {
        final int untilTick; final boolean forTask; final float hp0; final int air0; final long heard0 = Talk.heardSeq;
        volatile String woke; final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Wait(int u, boolean t, float hp, int air) { untilTick = u; forTask = t; hp0 = hp; air0 = air; }
    }
    static final java.util.List<Wait> WAITS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** called at the end of every client tick */
    static void tickWaits(Minecraft mc) {
        if (WAITS.isEmpty()) return;
        var p = mc.player;
        for (Wait w : WAITS) {
            if (w.latch.getCount() == 0) continue;
            String alarm = null;
            if (p != null && p.getHealth() < w.hp0) alarm = String.format("hurt (%.0f -> %.0f HP)", w.hp0, p.getHealth()) + hurtCause(p);
            else if (p != null && p.getAirSupply() < Math.min(w.air0, p.getMaxAirSupply()) - 60) alarm = "running out of air";
            else if (Pause.paused != null && !"idle".equals(Pause.pausedKind)) alarm = "PAUSED: " + Pause.paused;
            else if (Talk.heardSeq > w.heard0) alarm = "a message: " + Talk.lastHeard;   // (someone spoke to me: I may want to answer)
            if (alarm != null) w.woke = alarm;
            if (alarm != null || HonestClient.worldTicks >= w.untilTick || (w.forTask && Controller.task == null)) w.latch.countDown();
        }
    }

    static JsonObject raw() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        LocalPlayer pl = mc.player;
        if (pl == null) return o;
        o.add("all_entities_client_knows", Perception.entities(mc, pl, mc.level, false));
        Map<String, Integer> ores = new TreeMap<>(), knownOres = new TreeMap<>();
        BlockPos c = pl.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-32, -32, -32), c.offset(32, 32, 32))) {
            BlockState s = mc.level.getBlockState(p);
            String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if (n.endsWith("_ore") || n.equals("ancient_debris") || n.equals("chest") || n.equals("spawner")) {
                ores.merge(n, 1, Integer::sum);
                BlockState k = Perception.MAP.get(p.getX(), p.getY(), p.getZ());
                if (k != null && k.getBlock() == s.getBlock()) knownOres.merge(n, 1, Integer::sum);
            }
        }
        o.add("valuables_in_loaded_chunks_r32", GSON.toJsonTree(ores));
        o.add("valuables_agent_has_actually_seen", GSON.toJsonTree(knownOres));
        o.addProperty("known_map_size", Perception.MAP.size());
        return o;
    }

    // ------------------------------------------------------------------ plumbing
    static <T> T onClient(Supplier<T> s) {
        try { return Minecraft.getInstance().submit(s).get(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    interface Body { JsonElement get() throws Exception; }

    static void handle(HttpExchange ex, Body b) throws IOException {
        byte[] out; int code = 200;
        try { out = GSON.toJson(b.get()).getBytes(StandardCharsets.UTF_8); }
        catch (Throwable t) {
            code = 500;
            JsonObject e = new JsonObject(); e.addProperty("error", String.valueOf(t.getCause() != null ? t.getCause() : t));
            out = GSON.toJson(e).getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    /** what a player would notice about WHY it hurts: the screen texture, the flames, the bubbles, who's next to you */
    /** an empty cell next to me with a known solid block under it (so a table/furnace sits on the ground), nearest first */
    /** free cells next to me with solid ground under them, best first: at my feet level, not in water, not under my
     *  nose (a top face seen straight down from above is often hidden by my own block edge) */
    static java.util.List<BlockPos> nearbySpots(Minecraft mc, LocalPlayer pl) {
        BlockPos f = pl.blockPosition(); java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(f.offset(-2, -1, -2), f.offset(2, 1, 2))) {
            var st = mc.level.getBlockState(c);
            if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) continue;
            if (pl.getBoundingBox().inflate(0.05).intersects(new net.minecraft.world.phys.AABB(c))) continue;
            var below = Perception.MAP.get(c.getX(), c.getY() - 1, c.getZ());
            if (below == null || below.canBeReplaced() || !below.getFluidState().isEmpty()) continue;
            if (pl.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 4.2) continue;
            out.add(c.immutable());
        }
        out.sort(java.util.Comparator.comparingDouble(c -> Math.abs(c.getY() - f.getY()) * 2 + Math.abs(Math.hypot(c.getX() + 0.5 - pl.getX(), c.getZ() + 0.5 - pl.getZ()) - 1.5)));
        return out.size() > 6 ? out.subList(0, 6) : out;
    }

    /** frame advance: the world stays frozen; run exactly N ticks with whatever keys are held, freeze again, look.
     *  Like stepping a fighting game one frame at a time — so a slow brain can play fast fights by hand. */
    static JsonObject step(JsonObject a) {
        int n = Math.max(1, Math.min(200, a.has("ticks") ? a.get("ticks").getAsInt() : 5));
        JsonObject res = new JsonObject();
        onClient(() -> { if (Pause.paused == null) Pause.pause("frame-by-frame play", "manual"); return null; });
        Pause.rcon("tick freeze");
        int before = onClient(() -> HonestClient.ticks);
        String r = Pause.rcon("tick step " + n);
        res.addProperty("result", r == null ? "couldn't reach RCON" : "stepped " + n + " ticks");
        long until = System.currentTimeMillis() + n * 50L + 3000;
        while (System.currentTimeMillis() < until && onClient(() -> HonestClient.ticks) - before < n) sleep(10);
        sleep(60);
        res.add("observation", observe(Map.of("cursor", a.has("cursor") ? a.get("cursor").getAsString() : "0")));
        return res;
    }

    static String hurtCause(net.minecraft.client.player.LocalPlayer p) {
        java.util.List<String> c = new java.util.ArrayList<>();
        String what = Hurt.cause(p);
        if (what != null) c.add(what);
        var v = p.getDeltaMovement();
        if (!c.isEmpty() && p.hurtTime > 0 && Math.hypot(v.x, v.z) > 0.05) c.add("knocked toward the " + Places.compass(v.x, v.z));
        return c.isEmpty() ? " — cause not obvious" : " — " + String.join(", ", c);
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        URI u = ex.getRequestURI();
        if (u.getRawQuery() != null) for (String kv : u.getRawQuery().split("&")) {
            String[] p = kv.split("=", 2); m.put(p[0], p.length > 1 ? p[1] : "");
        }
        return m;
    }

    static float f(JsonObject a, String k, float d) { return a.has(k) ? a.get(k).getAsFloat() : d; }
    static Vec3 vec(JsonObject a) { return new Vec3(a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble()); }
    static BlockPos bpos(JsonObject a) { return BlockPos.containing(a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble()); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
