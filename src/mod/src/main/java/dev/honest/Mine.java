package dev.honest;
import net.minecraft.world.level.block.Blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * The human way to go underground: a 1-wide staircase (or a flat 1x2 tunnel) in a direction YOU choose,
 * one step at a time, looking at every block you open up. Runs in-client so a step costs ticks, not HTTP round trips.
 *
 * It stops — and hands control back to the brain — the moment it sees something worth a decision:
 * liquid next to the hole, no floor under the next step, a block from the `until` list, getting hurt.
 * It only ever reads the KnownMap (what the eyes saw); it never peeks at the world.
 */
public final class Mine implements Controller.Task {
    int dx, dz; final int dy; final int steps; String dirName; final Set<String> until;
    public int spiral = 0; // >0: turn right every `spiral` steps — a spiral staircase that stays in one place
    public Set<String> grab = Set.of(); final java.util.Set<BlockPos> tried = new java.util.HashSet<>(); Controller.Dig grabDig; int grabbed = 0;
    int done = 0, phase = 0, wait = 0, t = 0; float startHp = -1; net.minecraft.world.item.Item startTool;
    BlockPos origin, target; Controller.Dig dig; int digIdx = 0;
    Controller.Place plug; String plugWhy;
    public boolean bridge = true; BlockPos bridged; Controller.Place bridgePlace; String bridgeFiller;
    final java.util.ArrayDeque<BlockPos> bridgeQ = new java.util.ArrayDeque<>();
    public int torchEvery = 0; int lastTorch = -99; Controller.Place torchPlace; // light the way: dark tunnels spawn mobs and hide ores
    static final String[] FILLER = {"cobblestone", "cobbled_deepslate", "dirt", "netherrack", "andesite", "diorite", "granite", "tuff", "stone", "deepslate"};

    public Mine(String dir, int steps, boolean down, Set<String> until) {
        Direction d = Direction.byName(dir);
        if (d == null || d.getAxis().isVertical()) throw new IllegalArgumentException("direction must be north/south/east/west");
        dirName = d.getName(); dx = d.getStepX(); dz = d.getStepZ(); dy = down ? -1 : 0; this.steps = steps; this.until = until;
    }

    public Mine up() { Mine m = new Mine(dirName, steps, false, until); m.dyUp = true; return m; }
    boolean dyUp = false; // staircase UP: out of a hole without walking through the caves (enclosed the whole way)
    public String describe() { return (dyUp ? "staircase up " : dy < 0 ? "staircase down " : "tunnel ") + dirName + " x" + steps; }

    /** cells that must be open for this step: head-room above the step, then the step itself (feet, head) */
    BlockPos[] cells() {
        BlockPos f = origin.offset(dx, 0, dz);
        if (dyUp) return new BlockPos[]{origin.above(2), f.above(2), f.above()}; // jump room over me, then the step (head, feet)
        return dy < 0 ? new BlockPos[]{f.above(), f, f.below()} : new BlockPos[]{f.above(), f};
    }

    public String tick(Minecraft mc, LocalPlayer pl) {
        net.minecraft.world.item.ItemStack held = pl.getMainHandItem();
        if (plug != null) { // reflex in progress: finish plugging before anything else
            String r = plug.tick(mc, pl);
            if (r == null) return null;
            plug = null; Controller.release("use");
            if (!r.equals("placed")) return retreat(plugWhy + " -> plug: " + r);
            plugged.add(plugWhy.replaceAll(" after .*", ""));
            // more of it coming through another cell? look again before going on (a pocket often touches two)
        }
        if (startHp < 0) { startHp = pl.getHealth(); startTool = held.getItem(); }
        if (origin != null && phase >= 1 && phase <= 2) {
            // Did the hole we just opened touch lava? Look the moment a block gives way, not at the next sweep: lava
            // lights itself, so the eyes see it the instant it's exposed — and in the Nether it flows a block every
            // 10 ticks (overworld: 30), so the four ticks to the next sweep plus a slow plug were the death of me.
            lookAtOpening(mc, pl);
            BlockPos best = null; int bestRank = 99; String bestWhy = null;
            for (BlockPos c : cells()) {
                if (!(Vis.isAirish(mc.level.getBlockState(c)) || isFluid(c))) continue;
                BlockState cs = Perception.MAP.get(c.getX(), c.getY(), c.getZ());
                boolean lavaIn = isFluid(c) && danger(cs);
                BlockPos from = null;
                for (Direction d : Direction.values()) {
                    BlockPos n = c.relative(d);
                    if (n.equals(origin) || n.equals(origin.above())) continue;
                    if (danger(Perception.MAP.get(n.getX(), n.getY(), n.getZ()))) { from = n; break; }
                }
                if (!lavaIn && from == null) continue;
                // which cell to plug first: one lava is already in, then the one at my feet' level (lava runs along
                // the floor straight into where I stand), then the rest
                int rank = (lavaIn ? 0 : 2) + (c.getY() == origin.getY() ? 0 : 1);
                if (rank < bestRank) { bestRank = rank; best = c; bestWhy = "opened into lava at " + (lavaIn ? c : from).toShortString(); }
            }
            if (best == null && !plugged.isEmpty()) return stop(String.join("; ", plugged) + " after " + done + " steps -> plug: placed (" + plugged.size() + ")");
            if (best != null) {
                if (plugged.size() >= 4) return retreat(bestWhy + " — still coming after 4 plugs");
                String filler = null;
                for (String f : FILLER) { for (int i = 0; i < 36 && filler == null; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals(f)) filler = f; if (filler != null) break; }
                Controller.release("attack"); Controller.release("forward"); dig = null;
                plugWhy = bestWhy + " after " + done + " steps";
                if (filler == null) return retreat(plugWhy + " — and I have nothing to plug it with!");
                plug = new Controller.Place(best, filler); plug.quick = true;
                if (best.getY() == origin.getY()) Controller.hold("back", 3);   // (off the edge of the hole: it pours in along the floor)
                Events.note("reflex", plugWhy + " -> plugging " + best.toShortString() + " with " + filler);
                return null;
            }
        }
        if (held.isDamageableItem() && held.getMaxDamage() - held.getDamageValue() <= Math.max(3, held.getMaxDamage() / 30)) {
            // a spare of the same tool? swap it in (open inventory, hover the spare, press the hand's number key) and carry on
            var inv = pl.getInventory(); int hand = inv.getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                var st = inv.getItem(i);
                if (i != hand && st.getItem() == held.getItem() && st.getMaxDamage() - st.getDamageValue() > st.getMaxDamage() / 4) {
                    Controller.press(mc.options.keyHotbarSlots[i]);
                    Events.note("task", "switched to the fresh " + Perception.itemName(st) + " in hotbar slot " + i);
                    return null;
                }
            }
            for (int i = 9; i < 36; i++) {
                var st = inv.getItem(i);
                if (st.getItem() == held.getItem() && st.getMaxDamage() - st.getDamageValue() > st.getMaxDamage() / 4 && pl.containerMenu == pl.inventoryMenu) {
                    mc.gameMode.handleContainerInput(pl.inventoryMenu.containerId, i, hand, net.minecraft.world.inventory.ContainerInput.SWAP, pl);
                    Events.note("task", "swapped in a fresh " + Perception.itemName(st) + " from inventory slot " + i);
                    return null;
                }
            }
            return stop("tool nearly broken (" + (held.getMaxDamage() - held.getDamageValue()) + " uses left by the bar, no spare) after " + done + " steps");
        }
        if (++t > 20 * 900) return "gave up after 15 minutes, " + done + " steps";
        if (pl.getHealth() < startHp - 3) return stop(String.format("took damage (%.0f -> %.0f HP) after %d steps", startHp, pl.getHealth(), done));
        for (var e : Perception.lastViewBlocks.entrySet())
            for (String u : until) if (e.getKey().contains(u))
                return stop("saw " + e.getKey() + " at " + ((BlockPos) e.getValue()[1]).toShortString() + " after " + done + " steps");
        if (wait > 0) { wait--; return null; }

        switch (phase) {
            case 0 -> { // plan the step — but first take any ore we can see within reach (a miner's habit)
                if (grabDig != null) {
                    String r = grabDig.tick(mc, pl);
                    if (r == null) return null;
                    Controller.letGoOfAttack(mc); grabDig = null; if (r.startsWith("done")) grabbed++; wait = 8; return null;
                }
                if (!grab.isEmpty()) {
                    BlockPos eyeB = BlockPos.containing(pl.getEyePosition()); BlockPos best = null; double bd = 99;
                    for (BlockPos q : BlockPos.betweenClosed(eyeB.offset(-4, -4, -4), eyeB.offset(4, 4, 4))) {
                        BlockState qs = Perception.MAP.get(q.getX(), q.getY(), q.getZ());
                        if (qs == null || tried.contains(q)) continue;
                        String nm = Perception.itemName(new net.minecraft.world.item.ItemStack(qs.getBlock()));
                        boolean want = false; for (String g : grab) if (nm.contains(g)) want = true;
                        if (!want) continue;
                        boolean harvest = !qs.requiresCorrectToolForDrops();
                        for (int i = 0; i < 9 && !harvest; i++) harvest = pl.getInventory().getItem(i).isCorrectToolForDrops(qs);
                        if (!harvest) continue; // don't smash a diamond with a stone pickaxe
                        double dd = pl.getEyePosition().distanceTo(Vec3.atCenterOf(q));
                        if (dd < 4.3 && dd < bd) { bd = dd; best = q.immutable(); }
                    }
                    if (best != null) { tried.add(best); grabDig = new Controller.Dig(best); Events.note("task", "mining " + Perception.itemName(new net.minecraft.world.item.ItemStack(mc.level.getBlockState(best).getBlock())) + " at " + best.toShortString()); return null; }
                }
                if (torchPlace != null) {
                    String r = torchPlace.tick(mc, pl);
                    if (r == null) return null;
                    torchPlace = null; lastTorch = done; Controller.release("use");
                    if (!r.equals("placed")) Events.note("task", "couldn't put a torch down: " + r);
                    Controller.pickTool(mc, pl, Blocks.STONE.defaultBlockState()); wait = 4; return null;
                }
                if (torchEvery > 0 && done - lastTorch >= torchEvery && mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pl.blockPosition()) < 6) {
                    boolean have = false; for (int i = 0; i < 36 && !have; i++) have = Perception.itemName(pl.getInventory().getItem(i)).equals("torch");
                    if (have) { torchPlace = new Controller.Place(pl.blockPosition().offset(-dx, dyUp ? -1 : -dy, -dz), "torch"); lastTorch = done; return null; }
                }
                if (done >= steps) return stop("done, " + done + " steps" + (grabbed > 0 ? ", mined " + grabbed + " ore block(s) on the way" : ""));
                origin = pl.blockPosition(); target = origin.offset(dx, dyUp ? 1 : dy, dz); digIdx = 0; dig = null; phase = 1;
            }
            case 1 -> { // open the cells, top to bottom
                BlockPos[] cs = cells();
                while (digIdx < cs.length && Vis.isAirish(mc.level.getBlockState(cs[digIdx])) && !isFluid(cs[digIdx])) {
                    // the block I was digging just gave way: let go of the button NOW. (This skip used to step past the
                    // Dig before it could say "done", so the button stayed held through the step — breaking whatever the
                    // crosshair swept over while I walked, the next column's floor-level block first: in the Nether that
                    // opened lava pockets I never saw, at my feet.)
                    if (dig != null) { dig = null; Controller.letGoOfAttack(mc); }
                    digIdx++;
                }
                if (digIdx >= cs.length) { phase = 2; wait = 6; Controller.letGoOfAttack(mc); return null; } // let the eyes take in the new hole
                if (isFluid(cs[digIdx])) return stop("liquid (" + name(cs[digIdx]) + ") where the next step goes, at " + cs[digIdx].toShortString());
                if (dig == null) {
                    BlockState bs = mc.level.getBlockState(cs[digIdx]);
                    boolean canHarvest = !bs.requiresCorrectToolForDrops();
                    for (int i = 0; i < 9 && !canHarvest; i++) canHarvest = pl.getInventory().getItem(i).isCorrectToolForDrops(bs);
                    if (!canHarvest) return stop("nothing in my hotbar can mine " + bs.getBlock().getName().getString().toLowerCase() + " — needs a better tool (or mine broke) — after " + done + " steps");
                    dig = new Controller.Dig(cs[digIdx]);
                }
                String r = dig.tick(mc, pl);
                if (r != null) {
                    Controller.letGoOfAttack(mc); dig = null;
                    if (!r.startsWith("done")) return stop("couldn't dig " + cs[digIdx].toShortString() + ": " + r);
                    wait = 3; // gravel/sand may fall in; phase 1 re-checks
                }
            }
            case 2 -> { // look before you step: what did opening the hole reveal?
                if (bridgePlace == null && !bridgeQ.isEmpty()) bridgePlace = new Controller.Place(bridgeQ.pollFirst(), bridgeFiller);
                if (bridgePlace != null) {
                    String r = bridgePlace.tick(mc, pl);
                    if (r == null) return null;
                    bridgePlace = null; Controller.release("use");
                    if (!r.equals("placed")) { bridgeQ.clear(); return stop("no floor under the next step and couldn't fill it: " + r); }
                    if (bridgeQ.isEmpty()) Controller.pickTool(mc, pl, Blocks.STONE.defaultBlockState());
                    wait = 3; return null;
                }
                for (BlockPos c : cells()) for (Direction d : Direction.values()) {
                    BlockPos n = c.relative(d);
                    BlockState s = Perception.MAP.get(n.getX(), n.getY(), n.getZ());
                    if (danger(s))
                        return stop(s.getBlock().getName().getString().toLowerCase() + " right next to the hole at " + n.toShortString() + " (" + done + " steps done) — block it off or turn");
                }
                BlockPos floor = target.below();
                BlockState fs = Perception.MAP.get(floor.getX(), floor.getY(), floor.getZ());
                if (fs == null) { wait = 4; if (t % 40 == 0) Controller.lookAt(pl, Vec3.atCenterOf(floor)); if (t % 200 == 0) return stop("can't see the floor of the next step at " + floor.toShortString()); return null; }
                if (Vis.isAirish(fs)) {
                    // a hole where my next step goes: put a block in it and keep going (bridging, a miner's habit).
                    // Only once per step; if that fails, it really is a drop to look at.
                    String filler = null;
                    for (String f : FILLER) { for (int i = 0; i < 36 && filler == null; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals(f)) filler = f; if (filler != null) break; }
                    if (bridge && filler != null && !floor.equals(bridged)) {
                        // fill the hole from its bottom up (up to 3 deep); deeper than that is a real drop
                        bridgeQ.clear();
                        BlockPos c = floor; int depth = 0;
                        while (depth < 4) {
                            BlockState cs = Perception.MAP.get(c.getX(), c.getY(), c.getZ());
                            if (cs != null && !Vis.isAirish(cs) && cs.getFluidState().isEmpty()) break;
                            bridgeQ.addFirst(c); c = c.below(); depth++;
                        }
                        if (depth < 4) {
                            bridged = floor; bridgeFiller = filler;
                            Events.note("task", "hole " + depth + " deep at " + floor.toShortString() + " -> filling it with " + filler);
                            return null;
                        }
                    }
                    return stop("no floor under the next step (" + floor.toShortString() + " is " + fs.getBlock().getName().getString().toLowerCase() + ") — a drop or a cave");
                }
                phase = 3;
            }
            case 3 -> { // step in
                Vec3 c = Vec3.atBottomCenterOf(target);
                Controller.lookAt(pl, new Vec3(c.x, pl.getEyeY() + dy * 0.6, c.z));
                Vec3 p = pl.position();
                double h = Math.hypot(p.x - c.x, p.z - c.z);
                if (h < 0.25 && pl.onGround() && pl.blockPosition().getY() == target.getY()) {
                    Controller.release("forward"); Controller.release("sneak"); done++; phase = 0;
                    if (spiral > 0 && done % spiral == 0) { // turn right: (dx,dz) -> (-dz,dx) in MC's x/z
                        int ndx = -dz, ndz = dx; dx = ndx; dz = ndz;
                        dirName = Direction.getApproximateNearest(dx, 0, dz).getName();
                    }
                    return null;
                }
                Controller.hold("forward", 2);
                // once I'm down on the step, creep (sneak) the rest of the way: momentum carried me off a cliff edge past
                // the step at 6 HP (death #14). Sneaking can't walk off an edge.
                if (pl.blockPosition().getY() == target.getY() && !dyUp) Controller.hold("sneak", 2);
                if (dyUp && pl.blockPosition().getY() < target.getY() && pl.onGround() && h < 1.2) Controller.hold("jump", 2);
                if (t % 100 == 0 && h > 1.6) return stop("couldn't step to " + target.toShortString());
            }
        }
        return null;
    }

    /** lava of any kind, or a water SOURCE (an aquifer floods you); a thin trickle of flowing water is harmless */
    static boolean danger(BlockState s) {
        if (s == null || s.getFluidState().isEmpty()) return false;
        return s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);   // water is just swimming; only lava gets plugged
    }

    boolean isFluid(BlockPos p) { return !Minecraft.getInstance().level.getFluidState(p).isEmpty(); }
    static String name(BlockPos p) { return Minecraft.getInstance().level.getBlockState(p).getBlock().getName().getString().toLowerCase(); }
    String stop(String why) { Controller.release("forward"); Controller.release("attack"); return why; }

    final java.util.List<String> plugged = new java.util.ArrayList<>();

    /** couldn't stop it: run back the way I came — lava crawls a block per 10 ticks even in the Nether, I run 4x that */
    String retreat(String why) {
        stop(why);
        Controller.release("sneak"); Controller.hold("back", 30); Controller.hold("sprint", 30);
        Events.note("reflex", why + " -> backing away from it");
        return why + " -> backing away";
    }

    /** the eyes on the hole I just opened: any lava now in view next to it (it lights itself) */
    void lookAtOpening(Minecraft mc, LocalPlayer pl) {
        Vec3 eye = pl.getEyePosition();
        for (BlockPos c : cells()) {
            for (int k = -1; k < 6; k++) {
                BlockPos n = k < 0 ? c : c.relative(Direction.values()[k]);
                BlockState s = mc.level.getBlockState(n);
                if (!s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                BlockState known = Perception.MAP.get(n.getX(), n.getY(), n.getZ());
                if (known != null && danger(known)) continue;
                Vec3 at = Vec3.atCenterOf(n), toward = eye.subtract(at);
                Vec3 face = at.add(toward.normalize().scale(0.45));   // the side of it that faces me
                if (Vis.clear(mc.level, eye, face) || Vis.clear(mc.level, eye, at)) Perception.MAP.see(n, s);
            }
        }
    }
}
