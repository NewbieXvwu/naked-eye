package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Melee the way a decent player does it, at tick speed (the brain only picks the target and the style):
 *  - spacing: you reach 3 m, a zombie ~2 m. Step in when the swing is charged, hit, back off while it recharges.
 *  - crits: jump, and swing on the way DOWN (falling, not sprinting, not in water) for x1.5 damage.
 *  - weapon: sword (fast, sweeps) by default; axe if asked or no sword (slow, big single hits).
 *  - bail out: hand control back when my HP drops to `stopHp`, or I lose sight of it.
 * Only uses what a player perceives: the target's position/box (seen), my own cooldown bar, my HP.
 */
public final class Fight implements Controller.Task {
    final int id; final boolean crit, kite; final String weapon; final float stopHp;
    public boolean wall = false; Controller.Place wallPlace; // ArrowLog: 0 arrows ever stopped by it — the hits come point-blank, not on the approach
    int wallCd = 0, walls = 0;
    // for the experimenter's ArrowLog
    static final java.util.List<BlockPos> COVER = new java.util.ArrayList<>();
    static volatile boolean placingNow = false; static volatile int lastCoverTick = -1;
    public boolean strafe = false; int strafeDir = 1, strafeT = 0, drawT = 0, dodgeT = 0; // sidestep: measured no gain vs 2 skeletons (13.4 vs 12.8 HP, n=8), opt-in
    int t = 0, unseen = 0, swings = 0, crits = 0; boolean jumped = false; float hp0 = -1;

    public Fight(int id, boolean crit, boolean kite, String weapon, float stopHp) {
        this.id = id; this.crit = crit; this.kite = kite; this.weapon = weapon; this.stopHp = stopHp;
    }
    public String describe() { return "fight entity " + id + (crit ? " +crits" : "") + (kite ? " +spacing" : "") + " (" + weapon + ")"; }

    static double reachDist(LocalPlayer pl, AABB box) {
        Vec3 e = pl.getEyePosition();
        double x = Math.clamp(e.x, box.minX, box.maxX), y = Math.clamp(e.y, box.minY, box.maxY), z = Math.clamp(e.z, box.minZ, box.maxZ);
        return e.distanceTo(new Vec3(x, y, z));
    }

    /** is there floor behind me (so backing off doesn't walk me off a ledge or into lava)? memory only */
    static boolean safeBehind(LocalPlayer pl) {
        Vec3 back = pl.getLookAngle().multiply(-1, 0, -1).normalize().scale(1.0);
        BlockPos b = BlockPos.containing(pl.position().add(back));
        var floor = Perception.MAP.get(b.getX(), b.getY() - 1, b.getZ());
        var feet = Perception.MAP.get(b.getX(), b.getY(), b.getZ());
        return floor != null && !Vis.isAirish(floor) && floor.getFluidState().isEmpty() && (feet == null || Vis.isAirish(feet));
    }

    static int tier(String n) { return n.startsWith("netherite") ? 50 : n.startsWith("diamond") ? 40 : n.startsWith("iron") ? 30 : n.startsWith("stone") ? 20 : n.startsWith("golden") ? 12 : n.startsWith("wooden") ? 10 : 0; }
    int weaponScore(String n) {
        int k = n.endsWith("_sword") ? (weapon.equals("axe") ? 2 : 3) : n.endsWith("_axe") ? (weapon.equals("axe") ? 4 : 1) : -1;
        return k < 0 ? -1 : k * 100 + tier(n);
    }
    void pickWeapon(Minecraft mc, LocalPlayer pl) {
        var inv = pl.getInventory();
        int best = -1; int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            int sc = weaponScore(Perception.itemName(inv.getItem(i)));
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best < 0) return;
        if (best >= 9) { // the good one is in the bag: open inventory, hover it, press the number of the slot in hand (a player does this)
            int hand = inv.getSelectedSlot();
            if (pl.containerMenu == pl.inventoryMenu)
                mc.gameMode.handleContainerInput(pl.inventoryMenu.containerId, best, hand, net.minecraft.world.inventory.ContainerInput.SWAP, pl);
            return;
        }
        if (best != inv.getSelectedSlot()) Controller.press(mc.options.keyHotbarSlots[best]);
    }

    /** Skeletons aim at where you ARE (no lead): close in on a zigzag, sideways harder while it draws its bow. */
    /** is there ground within a safe fall (3 blocks) just ahead of me in the direction I'm moving? */
    static boolean groundAhead(Minecraft mc, LocalPlayer pl) {
        Vec3 dir = Vec3.directionFromRotation(0, pl.getYRot());
        for (double f : new double[]{0.8}) {
            BlockPos c = BlockPos.containing(pl.position().add(dir.scale(f)));
            boolean ok = false;
            for (int k = 0; k <= 3 && !ok; k++) {
                var st = Perception.MAP.get(c.getX(), c.getY() - 1 - k, c.getZ());
                ok = st != null && !Vis.isAirish(st) || st != null && !st.getFluidState().isEmpty();
            }
            if (!ok) return false;
        }
        return true;
    }
    void approach(Minecraft mc, LocalPlayer pl, Entity e, double d) {
        // chasing a pig off a ledge cost 5 HP and then a life: never run off a drop I can't survive
        if (!groundAhead(mc, pl)) { if (!ledgeNoted) { Events.note("task", "won't chase it: a drop ahead"); ledgeNoted = true; } return; }
        Controller.hold("forward", 2);
        boolean ranged = e instanceof LivingEntity le && (le.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                || le.getMainHandItem().getItem() instanceof net.minecraft.world.item.CrossbowItem);
        if (d > 4.5) Controller.hold("sprint", 2);
        if (pl.horizontalCollision && pl.onGround()) Controller.hold("jump", 2);      // hop over my own cover block
        if (wall && ranged && d > 5 && wallCd == 0 && ((LivingEntity) e).isUsingItem()) startWall(mc, pl, e);
        if (!(strafe && ranged) || d < 4) return;
        // measured: zigzagging the whole way was WORSE (slower approach = more volleys). So run straight, and only
        // sidestep right when a shot is coming: the bow has been drawn for ~0.6 s (you can watch it pull back)
        boolean drawing = ((LivingEntity) e).isUsingItem();
        drawT = drawing ? drawT + 1 : 0;
        if (dodgeT > 0) { dodgeT--; Controller.hold(strafeDir > 0 ? "right" : "left", 2); return; }
        if (drawT >= 12) { dodgeT = 8; drawT = -40; strafeDir = pl.getRandom().nextBoolean() ? 1 : -1; }
    }

    int wallStart = 0; BlockPos wallCell;
    /** it's drawing its bow: drop a block at my feet on the side facing it — skeletons aim 1/3 up your body,
     *  so one block blocks most arrows, and one block is low enough to hop over and keep coming */
    void startWall(Minecraft mc, LocalPlayer pl, Entity e) {
        double dx = e.getX() - pl.getX(), dz = e.getZ() - pl.getZ();
        BlockPos cell = pl.blockPosition().offset(Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0, 0, Math.abs(dx) >= Math.abs(dz) ? 0 : (int) Math.signum(dz));
        if (!mc.level.getBlockState(cell).canBeReplaced()) { Events.note("task", "cover: cell taken"); wallCd = 30; return; }
        String filler = null;
        for (String f : Mine.FILLER) for (int i = 0; i < 9 && filler == null; i++) if (Perception.itemName(pl.getInventory().getItem(i)).equals(f)) filler = f;
        if (filler == null) { Events.note("task", "cover: no blocks in hotbar"); wallCd = 200; return; }
        wallPlace = new Controller.Place(cell, filler); wallCd = 30; wallStart = t; wallCell = cell; placingNow = true;
        Controller.release("forward"); Controller.release("sprint");
    }

    public boolean duel = true;
    int phase = 0, phaseT = 0; boolean wasDrawing = false;   // 0 = shield up, 1 = rush, 2 = strike
    String duelTick(Minecraft mc, LocalPlayer pl, LivingEntity e, double d, float charge, boolean onTarget) {
        phaseT++;
        boolean drawing = e.isUsingItem();
        boolean justShot = wasDrawing && !drawing;        // the bow came down: an arrow is in the air, the next is ~1 s away
        wasDrawing = drawing;
        switch (phase) {
            case 0 -> { // shield up, face it, creep closer only while it isn't drawing
                Controller.hold("use", 2);
                if (!drawing && d > 6) Controller.hold("forward", 2);          // walking with the shield up is slow but safe
                if (justShot || (phaseT > 30 && !drawing)) { phase = 1; phaseT = 0; }
            }
            case 1 -> { // the gap: drop the shield, sprint in
                Controller.release("use");
                if (d <= 2.8) { phase = 2; phaseT = 0; return null; }
                if (drawing && phaseT > 4 && d > 4) { phase = 0; phaseT = 0; return null; }  // it's drawing again and I'm not there: cover
                Controller.hold("forward", 2); Controller.hold("sprint", 2);
                if (phaseT > 22) { phase = 0; phaseT = 0; }
            }
            case 2 -> { // one heavy hit (crit if a jump fits), then straight back behind the shield
                Controller.release("use");
                if (d > 3.2) { phase = 1; phaseT = 0; return null; }
                if (charge >= 0.9f) {
                    if (crit && pl.onGround() && phaseT < 3) { Controller.hold("jump", 2); return null; }
                    boolean falling = !pl.onGround() && pl.getDeltaMovement().y < -0.03;
                    if (crit && !pl.onGround() && !falling && phaseT < 12) return null;
                    if (onTarget && Controller.click(mc, "attack")) { swings++; if (falling) crits++; phase = 0; phaseT = 0; }
                }
                if (phaseT > 20 || drawing && charge < 0.9f) { phase = 0; phaseT = 0; }
            }
        }
        return null;
    }

    /** Creepers: never trade hits. Charged -> sprint in, one knockback hit, then straight back out of reach.
     *  The moment it swells (visible: it puffs up and flashes), get out: the fuse is 1.5 s and it gives up past 7 m.
     *  Learned the hard way: plain melee + "kite" ate a point-blank blast, 15 -> 7 HP. */
    String creeperTick(Minecraft mc, LocalPlayer pl, net.minecraft.world.entity.monster.Creeper cr, double d, float charge, boolean onTarget) {
        float sw = cr.getSwelling(1f);
        if (sw > 0.01 && d < 7) {
            if (charge >= 0.9f && d < 2.9 && onTarget && Controller.click(mc, "attack")) swings++; // a hit shoves it away too
            if (safeBehind(pl)) Controller.hold("back", 2); else { Controller.hold("left", 2); Controller.hold("back", 2); }
            if (!fleeNoted) { Events.note("task", "creeper is swelling — backing off"); fleeNoted = true; }
            return null;
        }
        fleeNoted = false;
        if (charge < 0.9f) {                       // recharging: stay out of its reach (it lights up within 3)
            if (d < 4.0 && safeBehind(pl)) Controller.hold("back", 2);
            return null;
        }
        if (d > 2.7) { Controller.hold("sprint", 2); approach(mc, pl, cr, d); return null; }
        if (onTarget && Controller.click(mc, "attack")) { swings++; Controller.hold("back", 6); }
        return null;
    }
    boolean fleeNoted; boolean retreat; int retreatT; boolean ledgeNoted;

    String end(String why) {
        for (String k : new String[]{"forward", "back", "left", "right", "jump", "sprint", "attack", "use"}) Controller.release(k);
        return why + String.format(" (%d swings, %d crits, %d cover blocks, HP %.0f -> %.0f)", swings, crits, walls, hp0, Minecraft.getInstance().player.getHealth());
    }

    public String tick(Minecraft mc, LocalPlayer pl) {
        if (++t == 1) { hp0 = pl.getHealth(); pickWeapon(mc, pl); }
        if (t > 20 * 60) return end("timeout");
        Entity e = mc.level.getEntity(id);
        if (e == null || !e.isAlive() || (e instanceof LivingEntity le && le.deathTime > 0)) return end("target down");
        // only bail when the fight itself took me below the line: starting low and cornered, walking away = dying
        // (it did exactly that at 1 HP with a spider on me — swung zero times and died)
        // low HP: don't just stop and stand there (it did: 9 -> 3 HP with 0 swings while "handing back"). Back off
        // facing it with the shield up until it's 6+ m away, then hand the decision back
        if (pl.getHealth() <= stopHp && hp0 > stopHp && !retreat) { retreat = true; retreatT = t; Events.note("task", "HP " + Math.round(pl.getHealth()) + " — backing off behind the shield"); }
        if (retreat) {
            for (String k : new String[]{"forward", "sprint", "jump", "attack"}) Controller.release(k);
            Controller.lookAt(pl, e.getBoundingBox().getCenter());
            if (Perception.itemName(pl.getOffhandItem()).equals("shield")) Controller.hold("use", 3);
            if (safeBehind(pl)) Controller.hold("back", 2); else Controller.hold(t % 40 < 20 ? "left" : "right", 2);
            if (reachDist(pl, e.getBoundingBox()) > 6 || t - retreatT > 200) return end("my HP is down to " + Math.round(pl.getHealth()) + " — backed off to " + Math.round(reachDist(pl, e.getBoundingBox())) + " m, your call");
            return null;
        }
        if (!Perception.anyPointVisible(mc.level, pl.getEyePosition(), e.getBoundingBox())) { if (++unseen > 40) return end("lost sight of it"); }
        else unseen = 0;

        if (wallCd > 0) wallCd--;
        if (wallPlace != null) {                                   // putting a block down: let that finish, then re-arm
            String r = wallPlace.tick(mc, pl);
            if (r == null && t - wallStart < 14) return null;
            if (r != null && r.startsWith("placed")) { walls++; COVER.add(wallCell); lastCoverTick = HonestClient.ticks; }
            placingNow = false;
            Events.note("task", "cover block: " + (r == null ? "too slow" : r));
            wallPlace = null; Controller.release("use"); pickWeapon(mc, pl);
        }
        Controller.lookAt(pl, e.getBoundingBox().getCenter());
        double d = reachDist(pl, e.getBoundingBox());
        float charge = pl.getAttackStrengthScale(0f);
        boolean onTarget = mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == e;
        Controller.release("forward"); Controller.release("back"); Controller.release("sprint"); Controller.release("left"); Controller.release("right");
        boolean canCrit = crit && !pl.isInWater() && !pl.onClimbable() && !pl.isPassenger();
        boolean airborne = !pl.onGround();

        // crit rhythm: jump while the swing is still recharging (~60%), so the charge completes on the way down
        if (canCrit && !airborne && charge >= 0.55f && charge < 0.9f && d < 3.6) { Controller.hold("jump", 2); }
        // DUEL vs a ranged mob with a shield: block -> it shoots -> rush in the reload gap -> one heavy hit -> block again
        boolean shieldOff = Perception.itemName(pl.getOffhandItem()).equals("shield");
        boolean rangedT = e instanceof LivingEntity lr && (lr.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                || lr.getMainHandItem().getItem() instanceof net.minecraft.world.item.CrossbowItem);
        if (duel && shieldOff && rangedT) return duelTick(mc, pl, (LivingEntity) e, d, charge, onTarget);
        if (e instanceof net.minecraft.world.entity.monster.Creeper cr) return creeperTick(mc, pl, cr, d, charge, onTarget);
        if (e instanceof net.minecraft.world.entity.monster.Blaze bz) return blazeTick(mc, pl, bz, d, charge, onTarget, shieldOff);
        boolean shield = shieldOff;
        boolean threat = e instanceof LivingEntity le2 && (le2.isUsingItem() || d < 3.5);
        if (shield && charge < 0.9f && threat) { Controller.hold("use", 3); return null; }   // raise the shield while the swing recharges
        if (shield) Controller.release("use");
        if (charge < 0.9f) {                                     // recharging: don't stand inside its arms
            if (kite && !airborne && charge < 0.5f && d < 2.0 && safeBehind(pl)) Controller.hold("back", 2);
            else if (d > 3.4) approach(mc, pl, e, d);
            return null;
        }
        if (d > 2.85) { approach(mc, pl, e, d); return null; }
        // charged and in reach. If a crit is close (falling now), take it; if on the ground, don't wait for one
        boolean falling = airborne && pl.getDeltaMovement().y < -0.03 && pl.fallDistance > 0;
        if (canCrit && airborne && !falling) return null;         // rising: a few ticks to the crit window
        if (onTarget && Controller.click(mc, "attack")) {
            swings++;
            if (falling && !pl.isSprinting()) crits++;
        }
        return null;
    }

    /** Blazes hover out of reach and shoot volleys of three fireballs. Shield up toward it whenever a fireball is on
     *  its way or it's charging (it glows: Blaze.isOnFire()); get under it; when it's in reach and the swing is charged,
     *  drop the shield for one tick and hit — jumping if it floats just above. */
    String blazeTick(Minecraft mc, LocalPlayer pl, net.minecraft.world.entity.monster.Blaze bz, double d, float charge, boolean onTarget, boolean shield) {
        boolean incoming = false;
        Vec3 me = pl.getBoundingBox().getCenter();
        for (Entity f : mc.level.getEntities(pl, pl.getBoundingBox().inflate(14), x -> x instanceof net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball)) {
            Vec3 v = f.position().subtract(f.xo, f.yo, f.zo), to = me.subtract(f.position());
            if (v.lengthSqr() > 1e-4 && v.normalize().dot(to.normalize()) > 0.85) { incoming = true; break; }
        }
        double hd = Math.hypot(bz.getX() - pl.getX(), bz.getZ() - pl.getZ()), up = bz.getY() - pl.getY();
        if (d <= 3.0 && charge >= 0.9f && onTarget && !incoming) {
            Controller.release("use");
            if (Controller.click(mc, "attack")) { swings++; if (!pl.onGround() && pl.getDeltaMovement().y < 0) crits++; }
            return null;
        }
        if (shield && (incoming || bz.isOnFire() || charge < 0.9f)) Controller.hold("use", 3); else Controller.release("use");
        // don't chase a blaze across a fortress: the last chase ended 39 m down. Only close in on one that's near
        if (hd > 1.2 && d > 2.6 && d < 6 && !incoming) approach(mc, pl, bz, d);
        else if (d >= 6 && t > 200) return end("it keeps its distance (" + Math.round(d) + " m)");
        else if (up > 1.2 && d > 2.9 && pl.onGround() && charge >= 0.9f) Controller.hold("jump", 2);   // it floats just above: jump into reach
        return null;
    }
}
