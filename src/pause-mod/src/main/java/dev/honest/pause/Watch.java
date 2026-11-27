package dev.honest.pause;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching the agents with an ordinary Minecraft client.
 *
 * Everyone who joins and is not an agent is a watcher: a real spectator on the server (invisible to the agents,
 * which are never even sent their entity, no collisions, no say in the world). On joining, a watcher is put inside
 * an agent's eyes ("possessed"): the camera is the agent's, and the HUD — hotbar, held item, hearts, food, XP,
 * armour, inventory screen — is the agent's, mirrored by packets. For that the watcher's client is told it is in
 * adventure mode (so it draws a survival HUD), while every action it sends is dropped.
 * Sneak = let go and fly freely as a plain spectator. Left-click an agent, or /watch [name], to get back in.
 * Any number of watchers, any agent each. Agents are listed in config/naked-eye/agents.txt (default: Agent).
 */
public final class Watch implements ModInitializer {
    static Set<String> agents = Set.of("Agent");
    /** watcher -> the agent it is inside right now (HUD mirrored) */
    static final Map<UUID, ServerPlayer> inside = new ConcurrentHashMap<>();
    /** watcher -> the agent name it wants to be inside ("*" = any): kept across the agent's death and respawn */
    static final Map<UUID, String> wants = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> lastSig = new HashMap<>();

    public static boolean isAgent(Player p) { return agents.contains(p.getGameProfile().name()); }
    public static boolean isWatcher(Player p) { return !isAgent(p); }
    /** possessed watchers may not act at all */
    public static boolean locked(ServerPlayer p) { return inside.containsKey(p.getUUID()); }

    @Override
    public void onInitialize() {
        loadAgents();

        // the agents' commentary channel (registered on both sides: this mod is on the client too)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Note.TYPE, Note.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Note.TYPE, (n, ctx) -> Note.onServer(ctx.player(), n.text()));

        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            ServerPlayer p = h.player;
            if (isAgent(p)) return;
            p.setGameMode(GameType.SPECTATOR);
            wants.put(p.getUUID(), "*");
            p.sendSystemMessage(Component.literal("Watching. Sneak to fly freely; left-click an agent or /watch [name] to get back in."));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> { inside.remove(h.player.getUUID()); wants.remove(h.player.getUUID()); lastSig.remove(h.player.getUUID()); });
        // the agents must not learn that anyone is watching: no "X joined / left the game" for watchers
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, msg, overlay) -> {
            if (msg.getContents() instanceof TranslatableContents tc
                    && (tc.getKey().equals("multiplayer.player.joined") || tc.getKey().equals("multiplayer.player.left")) && tc.getArgs().length > 0) {
                String name = tc.getArgs()[0] instanceof Component c ? c.getString() : String.valueOf(tc.getArgs()[0]);
                return agents.contains(name);
            }
            return true;
        });
        ServerTickEvents.END_SERVER_TICK.register(Watch::tick);
        ServerTickEvents.END_SERVER_TICK.register(RecordingClock::tick);
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("watch")
                .executes(c -> watch(c.getSource().getPlayerOrException(), null))
                .then(Commands.argument("agent", StringArgumentType.word())
                        .executes(c -> watch(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "agent"))))));
    }

    static long agentsStamp = -1;
    /** the agents' names (config/naked-eye/agents.txt), re-read whenever the file changes (./add_agent.sh adds one) */
    static void loadAgents() {
        try {
            Path f = Path.of("config", "naked-eye", "agents.txt");
            if (!Files.exists(f)) return;
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp == agentsStamp) return;
            agentsStamp = stamp;
            Set<String> s = new LinkedHashSet<>();
            for (String l : Files.readAllLines(f)) { l = l.strip(); if (!l.isEmpty() && !l.startsWith("#")) s.add(l); }
            if (!s.isEmpty()) agents = s;
        } catch (Exception ignored) {}
    }

    /** /watch = the next agent, /watch NAME = that one, /watch free = let go */
    static int watch(ServerPlayer p, String name) {
        if (isAgent(p)) return 0;
        if ("free".equals(name)) { wants.remove(p.getUUID()); p.setCamera(p); return 1; }
        List<ServerPlayer> live = liveAgents(p.level().getServer());
        if (live.isEmpty()) { p.sendSystemMessage(Component.literal("No agent is online.")); return 0; }
        ServerPlayer t;
        if (name != null) t = live.stream().filter(a -> a.getGameProfile().name().equalsIgnoreCase(name)).findFirst().orElse(null);
        else { ServerPlayer cur = inside.get(p.getUUID()); t = live.get(cur == null ? 0 : (live.indexOf(cur) + 1) % live.size()); }
        if (t == null) { p.sendSystemMessage(Component.literal("No such agent. Online: " + live.stream().map(a -> a.getGameProfile().name()).toList())); return 0; }
        wants.put(p.getUUID(), t.getGameProfile().name());
        p.setCamera(t);
        return 1;
    }

    static List<ServerPlayer> liveAgents(MinecraftServer s) {
        return s.getPlayerList().getPlayers().stream().filter(a -> isAgent(a) && a.isAlive()).toList();
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) loadAgents();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAgent(p)) continue;
            if (p.gameMode() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            UUID id = p.getUUID();
            ServerPlayer was = inside.get(id);
            boolean onAgent = p.getCamera() instanceof ServerPlayer a && a != p && isAgent(a) && a.isAlive();
            if (onAgent) {
                ServerPlayer a = (ServerPlayer) p.getCamera();
                if (was != a) { begin(p, a); wants.put(id, a.getGameProfile().name()); }
                mirror(p, a);
                continue;
            }
            if (was != null) {
                end(p);
                // sneaked out while the agent lives on = wants to fly freely; the agent died or respawned = follow it back
                if (!was.isRemoved() && was.isAlive()) wants.remove(id);
            }
            String want = wants.get(id);
            if (want != null && p.getCamera() == p) {
                liveAgents(server).stream().filter(a -> want.equals("*") || a.getGameProfile().name().equals(want)).findFirst().ifPresent(p::setCamera);
            }
        }
    }

    /** step inside: the client thinks it is in adventure mode (survival HUD) and hovering, unable to move */
    static void begin(ServerPlayer p, ServerPlayer a) {
        inside.put(p.getUUID(), a); lastSig.remove(p.getUUID());
        p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, GameType.ADVENTURE.getId()));
        Abilities ab = new Abilities();
        ab.invulnerable = true; ab.flying = true; ab.mayfly = true; ab.instabuild = false; ab.mayBuild = false;
        ab.setFlyingSpeed(0f);
        p.connection.send(new ClientboundPlayerAbilitiesPacket(ab));
    }

    /** the agent's HUD, sent only when something on it changed */
    static void mirror(ServerPlayer p, ServerPlayer a) {
        List<ItemStack> items = new ArrayList<>(a.inventoryMenu.getItems());
        int sig = Objects.hash(items.stream().map(s -> s.isEmpty() ? 0 : Objects.hash(s.getItem(), s.getCount(), s.getDamageValue())).toList(),
                a.getInventory().getSelectedSlot(), a.getHealth(), a.getFoodData().getFoodLevel(), a.getFoodData().getSaturationLevel(),
                a.experienceLevel, a.experienceProgress, a.getArmorValue(), a.getMaxHealth());
        Integer last = lastSig.put(p.getUUID(), sig);
        if (last != null && last == sig && p.tickCount % 100 != 0) return;   // (and every 5 s anyway)
        p.connection.send(new ClientboundContainerSetContentPacket(0, a.inventoryMenu.getStateId(), items, ItemStack.EMPTY));
        p.connection.send(new ClientboundSetHeldSlotPacket(a.getInventory().getSelectedSlot()));
        // never 0: the client would open a death screen with a respawn button
        p.connection.send(new ClientboundSetHealthPacket(Math.max(0.5f, a.getHealth()), a.getFoodData().getFoodLevel(), a.getFoodData().getSaturationLevel()));
        p.connection.send(new ClientboundSetExperiencePacket(a.experienceProgress, 0, a.experienceLevel));
        List<AttributeInstance> attrs = new ArrayList<>();
        for (var at : List.of(Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.MAX_HEALTH)) {
            AttributeInstance i = a.getAttribute(at); if (i != null) attrs.add(i);
        }
        p.connection.send(new ClientboundUpdateAttributesPacket(p.getId(), attrs));
    }

    /** step out: a plain spectator again, with its own (empty) HUD */
    static void end(ServerPlayer p) {
        inside.remove(p.getUUID()); lastSig.remove(p.getUUID());
        p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, GameType.SPECTATOR.getId()));
        p.onUpdateAbilities();
        p.inventoryMenu.sendAllDataToRemote();
        p.connection.send(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
        List<AttributeInstance> attrs = new ArrayList<>();
        for (var at : List.of(Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.MAX_HEALTH)) {
            AttributeInstance i = p.getAttribute(at); if (i != null) attrs.add(i);
        }
        p.connection.send(new ClientboundUpdateAttributesPacket(p.getId(), attrs));
        p.resetSentInfo();
    }
}
