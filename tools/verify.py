#!/usr/bin/env python3
"""Experimenter-side verification: builds scenes over RCON, then checks that the honest API
reveals exactly what a human could perceive — while the raw client state knows more."""
import os, json, re, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
from rcon import run as rcon
import ports as _ports
_P = _ports.get(); A = f"http://127.0.0.1:{_P['api']}"; D = f"http://127.0.0.1:{_P['debug']}"

def get(url):
    with urllib.request.urlopen(url, timeout=30) as r: return json.load(r)
def post(obj):
    req = urllib.request.Request(A + "/act", data=json.dumps(obj).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=60) as r: return json.load(r)
def settle(ticks=12): post({"type": "wait", "wait": ticks})
def honest(): return get(A + "/observe?cursor=999999999")
def honest_events(c): return get(f"{A}/observe?cursor={c}")["events"]
def raw(): return get(D + "/debug/raw")
def seen(o, t): return [e for e in o["entities"] if e["type"] == t]
def truth(r, t): return [e for e in r["all_entities_client_knows"] if e["type"] == t]

R = []  # (test, passed, detail)
def check(name, cond, detail): R.append((name, bool(cond), detail)); print(("PASS " if cond else "FAIL ") + name + " — " + detail)

def arena():
    # forceload first: if the agent starts far away the arena chunks are unloaded and every `fill` silently
    # fails — T1a then "saw through" a wall that was never built (that was the T1a flake)
    # a really buried ore needs rock all around it (the arena floats in the sky: the old T3 "buried" diamond was
    # visible from below, and an agent that once fell past it remembered it), and the agent's memory of the
    # arena from earlier runs must go
    get(D + "/debug/forget?box=-1,194,-1,31,216,35")
    # lift the agent out of the way before pouring stone (it once suffocated inside a freshly filled hall), top up HP
    rcon(["forceload add 0 0 30 34", "effect give Agent resistance 6 255 true", "effect give Agent slow_falling 6 0 true", "tp Agent 15.5 225 17.5", "effect give Agent instant_health 1 10 true", "kill @e[type=!player]", "fill 0 194 0 30 198 34 stone", "fill 0 199 0 30 199 34 stone", "fill 0 200 0 30 215 34 air",
          "time set day", "gamerule advance_time false", "gamerule spawn_mobs false", "weather clear"])

def look(x, y, z): post({"type": "look_at", "x": x, "y": y, "z": z, "wait": 15})

# the tests teleport the agent and borrow two hotbar slots; remember where it was and what it held, and put it back
if get(A + "/observe")["self"].get("dead"):   # a dead agent fails every test for the wrong reason
    post({"type": "respawn"}); time.sleep(3)
_start = get(A + "/observe")["self"]
post({"type": "pause_policy", "allow": False})   # the tests hurt/scare the agent on purpose; don't freeze the world
_held = {i["slot"]: i for i in _start["inventory"] if i["slot"] in (0, 1)}

# ---------------------------------------------------------------- T1 occlusion
arena()
rcon(["fill 10 200 12 20 203 12 stone", "summon zombie 15 200 16 {NoAI:1b,PersistenceRequired:1b,Silent:1b}",
      "tp Agent 15.5 200 4.5 facing 15.5 201 20"])
settle(20)
o, r = honest(), raw()
check("T1a wall hides zombie", not seen(o, "zombie") and truth(r, "zombie"),
      f"client knows zombie at {truth(r,'zombie')[0]['approx_pos'] if truth(r,'zombie') else '?'} "
      f"(true hp {truth(r,'zombie')[0].get('TRUE_health') if truth(r,'zombie') else '?'}); honest view lists {len(seen(o,'zombie'))}")
rcon(["fill 10 200 12 20 203 12 air"]); settle(10)
o = honest()
check("T1b remove wall -> zombie visible", seen(o, "zombie"), json.dumps(seen(o, "zombie")[:1], ensure_ascii=False))

# ---------------------------------------------------------------- T2 crackiness
rcon(["kill @e[type=zombie]", "summon iron_golem 15 200 14 {NoAI:1b,PersistenceRequired:1b}"]); settle(10)
looks = []
for hp in (100, 70, 45, 20):
    rcon([f"data merge entity @e[type=iron_golem,limit=1] {{Health:{hp}f}}"]); settle(6)
    g = seen(honest(), "iron_golem")
    looks.append((hp, g[0].get("looks", {}) if g else None))
txt = "; ".join(f"true HP {hp} -> {l}" for hp, l in looks)
leak = any(l and any("health" in k.lower() for k in l) for _, l in looks)
check("T2 golem: cracks visible, health never leaks", all(l is not None for _, l in looks) and not leak
      and len({json.dumps(l) for _, l in looks}) >= 3, txt)

# ---------------------------------------------------------------- T3 buried ore
rcon(["kill @e[type=iron_golem]", "setblock 22 197 20 diamond_ore", "setblock 6 199 20 diamond_ore"])
look(6.5, 199.5, 20.5); settle(40)
r = raw()
kb = get(D + "/debug/known?x=22&y=197&z=20").get("known")
ke = get(D + "/debug/known?x=6&y=199&z=20").get("known")
check("T3 buried diamond never enters memory, exposed one does", kb is None and ke == "diamond_ore",
      f"memory at buried spot: {kb}; at exposed spot: {ke}; all valuables in loaded chunks: {r['valuables_in_loaded_chunks_r32']}, agent has seen: {r['valuables_agent_has_actually_seen']}")

# ---------------------------------------------------------------- T4 darkness (tinted glass passes sight, blocks light)
rcon(["fill 11 200 20 19 205 28 stone", "fill 12 201 21 18 204 27 air", "fill 12 201 20 18 204 20 tinted_glass",
      "summon zombie 15 201 25 {NoAI:1b,PersistenceRequired:1b,Silent:1b}", "tp Agent 15.5 200 6.5 facing 15.5 202 25"])
settle(30)
o, r = honest(), raw()
dark = not seen(o, "zombie") and truth(r, "zombie")
rcon(["setblock 13 201 26 torch", "setblock 17 201 26 torch"]); settle(30)
o2 = honest()
check("T4 zombie in a pitch-dark room is invisible until a torch is lit", dark and seen(o2, "zombie"),
      f"dark: honest sees {len(seen(o,'zombie'))} (client knows {len(truth(r,'zombie'))}); lit: honest sees {len(seen(o2,'zombie'))}")

# ---------------------------------------------------------------- T5 hearing what you can't see
rcon(["kill @e[type=zombie]", "fill 11 200 20 19 205 28 air", "fill 10 200 12 20 203 12 stone",
      "summon zombie 15 200 16 {NoAI:1b,PersistenceRequired:1b,Silent:1b}", "tp Agent 15.5 200 4.5 facing 15.5 201 20"])
settle(15); c0 = get(A + "/observe")["cursor"]
rcon(["playsound minecraft:entity.zombie.ambient hostile Agent 15 200 16 1"]); settle(6)
evs = [e for e in honest_events(c0) if e["kind"] == "sound"]
check("T5 hear the zombie behind the wall (subtitle + direction) without seeing it",
      evs and not seen(honest(), "zombie"), json.dumps(evs[:2], ensure_ascii=False))

# ---------------------------------------------------------------- T6 field of view
rcon(["kill @e[type=zombie]", "fill 10 200 12 20 203 12 air", "summon cow 15 200 0 {NoAI:1b,PersistenceRequired:1b}"])
look(15.5, 201, 20); settle(10)
behind = seen(honest(), "cow")
look(15.5, 200.5, 0); settle(15)
front = seen(honest(), "cow")
check("T6 cow behind you is unseen until you turn around", not behind and front, f"behind: {len(behind)}, after turning: {len(front)}")

# ---------------------------------------------------------------- T7 distance detail
rcon(["kill @e[type=cow]", "fill 0 199 35 30 199 90 stone", "fill 0 200 35 30 205 90 air",
      "summon sheep 15 200 60 {NoAI:1b,PersistenceRequired:1b,Color:14}", "summon sheep 13 200 12 {NoAI:1b,PersistenceRequired:1b,Color:14}"])
look(15.5, 201, 40); settle(15)
sh = seen(honest(), "sheep")
far = [s for s in sh if s["dist"] > 40]; near = [s for s in sh if s["dist"] < 15]
check("T7 a far sheep is just 'a sheep over there'; a near one shows its (red) wool",
      far and far[0].get("detail") == "too_far_to_make_out" and near and "looks" in near[0],
      f"far: {json.dumps(far[:1], ensure_ascii=False)} | near looks: {near[0].get('looks') if near else None}")


passed = sum(1 for _, p, _ in R if p)
print(f"\n{passed}/{len(R)} passed")
json.dump([{"test": n, "passed": p, "detail": d} for n, p, d in R], open(os.path.join(ROOT, "run", "verify_report.json"), "w"), ensure_ascii=False, indent=1)

# ======================================================================= 26.3 additions
def gamma(v): return get(f"{D}/debug/gamma?v={v}")
def act_go(x, y, z, wait=900):
    r = post({"type": "go", "x": x, "y": y, "z": z, "wait": wait})
    return r["observation"]["control"]["last_result"], r["observation"]["self"]["pos"]

# ---------------------------------------------------------------- T8 seeing in the dark depends on distance and Brightness
arena()
rcon(["tp Agent 15.5 225 17.5", "fill 0 199 0 30 206 34 stone", "fill 1 200 1 29 205 33 air",        # sealed hall: light level 0 inside
      "tp Agent 15.5 200 2.5 facing 15.5 201 30"])
post({"type": "stop"}); post({"type": "look", "yaw": 0, "pitch": -2, "wait": 12})   # cancel whatever the last test left turning
spots = [(4, 11), (8, 19), (12, 12), (16, 18), (24, 15)]                    # (distance ahead, x)
for d, x in spots:
    rcon([f"summon zombie {x} 200 {2 + d} {{NoAI:1b,PersistenceRequired:1b,Silent:1b}}"])
settle(30)
res = {}
for g in (0.0, 0.5, 1.0):
    gamma(g); settle(12)
    z = [e for e in honest()["entities"] if e["type"] == "zombie"]
    res[g] = sorted(e["dist"] for e in z)
gamma(0.5)
truth_n = len(truth(raw(), "zombie"))
far_ok = max(res[0.0] or [0]) < max(res[0.5] or [0]) < max(res[1.0] or [99]) or (len(res[0.0]) < len(res[0.5]) <= len(res[1.0]))
check("T8 unlit hall: you see nearer zombies only, and farther with higher Brightness",
      res[0.5] and len(res[0.5]) < truth_n and len(res[0.0]) <= len(res[0.5]) <= len(res[1.0]) and len(res[0.0]) < len(res[1.0]),
      f"client knows {truth_n} at 4/8/12/16/24 m; Moody sees {res[0.0]}, Normal sees {res[0.5]}, Bright sees {res[1.0]}")

# ---------------------------------------------------------------- T9 / T10 glowing eyes and self-lit mobs
rcon(["kill @e[type=zombie]", "summon spider 12 200 22 {NoAI:1b,PersistenceRequired:1b,Silent:1b}",
      "summon blaze 19 200 22 {NoAI:1b,PersistenceRequired:1b,Silent:1b}"])
settle(20)
o = honest()
sp, bz = seen(o, "spider"), seen(o, "blaze")
check("T9 spider in the dark: only its glowing eyes are visible", sp and sp[0].get("detail") == "only_glowing_eyes_visible",
      json.dumps(sp[:1], ensure_ascii=False))
check("T10 blaze in the dark is fully visible (renderer lights it full-bright)", bz and bz[0].get("detail") is None,
      json.dumps(bz[:1], ensure_ascii=False))

# ---------------------------------------------------------------- T11 honest Baritone cannot use a staircase it hasn't seen
arena()
curl_forget = get(D + "/debug/forget")
rcon(["fill 0 200 20 30 203 20 stone",                       # a wall across the arena
      "fill 12 200 24 18 203 30 stone",                       # a raised platform behind it (top at y=204)
      "fill 13 204 25 17 204 29 gold_block",
      "fill 14 200 21 16 200 21 stone", "fill 14 201 22 16 201 22 stone", "fill 14 202 23 16 202 23 stone",
      "fill 14 203 24 16 203 24 stone",                       # staircase hidden behind the wall
      "fill 14 201 21 16 205 21 air", "fill 14 202 22 16 205 22 air", "fill 14 203 23 16 205 23 air",
      "tp Agent 15.5 200 10.5 facing 15.5 204 27"])
get(D + "/debug/baritone?break=0&place=0")   # walking only: test route knowledge, not building
settle(30)
post({"type": "look_around", "pitch": 5, "wait": 160})
r1, p1 = act_go(15, 205, 27, 600)
rcon(["fill 13 200 20 17 203 20 air"])                        # open a doorway: the stairs become visible
post({"type": "look_at", "x": 15.5, "y": 202, "z": 22, "wait": 40}); settle(20)
r2, p2 = act_go(15, 205, 27, 900)
get(D + "/debug/baritone?break=1&place=1")
check("T11 honest Baritone won't route via unseen stairs; once seen, it climbs them",
      "arrived" not in r1 and "arrived" in r2,
      f"before seeing stairs: '{r1}' at {p1}; after: '{r2}' at {p2}")

# T12: tunnelling into lava. The 1.5 s it takes lava to flow in is shorter than a brain round trip,
# so the mining task itself must plug the hole the instant the eyes see lava next to it.
rcon(["fill 0 199 0 30 206 34 stone", "fill 15 200 2 15 201 3 air", "setblock 15 201 8 lava",
      "item replace entity Agent hotbar.0 with stone_pickaxe", "item replace entity Agent hotbar.1 with cobblestone 16",
      "effect clear Agent", "tp Agent 15.5 200 2.5 facing 15.5 201 10"])
post({"type": "hotbar", "slot": 0}); settle(20)
hp0 = honest()["self"]["health"]
r = post({"type": "mine", "dir": "south", "steps": 10, "down": False, "wait": 1200})
o = honest(); res = o["control"]["last_result"]
check("T12 tunnelling into lava: the task plugs the hole before lava reaches me",
      "plug: placed" in res and o["self"]["health"] >= hp0 and not o["self"].get("on_fire"),
      f"result '{res}', HP {hp0}->{o['self']['health']}")
rcon(["fill 0 199 0 30 206 34 air", "fill 0 199 0 30 199 34 stone"])

# T13: find the way home in a 3-level cave from the place graph alone. The tempting way back (the ledge
# you dropped from) is 5 m up and the graph must say so; the real way home is a stair tunnel you haven't seen yet.
import subprocess, testcave
X0, Y0, Z0 = testcave.X0, testcave.Y0, testcave.Z0
testcave.build()
get(f"{D}/debug/forget?box={X0-3},{Y0-3},{Z0-3},{X0+43},{Y0+23},{Z0+35}")
get(D + "/debug/baritone?break=0&place=0")
rcon([f"tp Agent {X0+4.5} {Y0+14} {Z0+4.5} facing {X0+15} {Y0+14.5} {Z0+4}", "effect give Agent regeneration 60 4 true"]); settle(100)   # (3000 blocks away: let the chunks arrive)
post({"type": "look_around", "pitch": 10, "wait": 160})
post({"type": "mark", "name": "t13_entrance"})
post({"type": "go", "x": X0+21, "y": Y0+14, "z": Z0+3, "wait": 1200})
post({"type": "look_at", "x": X0+26.5, "y": Y0+9, "z": Z0+5.5, "wait": 30}); post({"type": "look_around", "pitch": 10, "wait": 160})
ledge_txt = get(A + "/places")["text"]
one_way = bool(re.search(r"drop ~?5 m to P\d+ — ONE WAY", ledge_txt)) and "NO WAY BACK" in ledge_txt
# now wake up in the deepest room, knowing only what was seen from up there
rcon([f"tp Agent {X0+4.5} {Y0+2} {Z0+26.5} facing {X0+4.5} {Y0+3} {Z0+22}"]); settle(20)
post({"type": "look_around", "pitch": 10, "wait": 160})
fw = subprocess.run([sys.executable, os.path.join(ROOT, "agent", "find_way.py"), "t13_entrance", "12"], capture_output=True, text=True, timeout=1500)
pos = honest()["self"]["pos"]
home = abs(pos[0] - (X0+4)) < 2.5 and abs(pos[2] - (Z0+4)) < 2.5 and abs(pos[1] - (Y0+14)) < 1
rounds = fw.stdout.count("round ")
get(D + "/debug/baritone?break=1&place=1")
post({"type": "mark", "name": "t13_entrance", "remove": True})
rcon([f"forceload remove {X0-16} {Z0-16} {X0+60} {Z0+50}"])
check("T13 cave: graph flags the ledge drop as one-way; from the deepest room I find the way home by exploring openings",
      one_way and home and fw.returncode == 0,
      f"ledge drop flagged one-way: {one_way}; got home: {home} at {pos} in {rounds} rounds; last lines: {fw.stdout.strip().splitlines()[-2:]}")

# T14: object permanence — when a mob drops out of sight I know which way it went: turned away from = off that
# side of the screen; a wall dropped in front of it = "went behind something"
arena()
rcon(["summon cow 15.5 200 9.5 {NoAI:1b,PersistenceRequired:1b}", "tp Agent 15.5 200 2.5 facing 15.5 201 10"]); settle(20)
post({"type": "turn", "dyaw": -100, "dpitch": 0, "wait": 20}); settle(10)
lost1 = [l for l in honest()["recently_lost_sight_of"] if l["type"] == "cow"]
post({"type": "turn", "dyaw": 100, "dpitch": 0, "wait": 20}); settle(10)
rcon(["fill 12 200 6 20 202 6 stone"]); settle(12)
lost2 = [l for l in honest()["recently_lost_sight_of"] if l["type"] == "cow"]
check("T14 lost sight: 'went off the RIGHT side' when I turn left; 'went behind something' when a wall appears",
      lost1 and "RIGHT" in lost1[0]["how"] and lost2 and "behind" in lost2[0]["how"],
      f"turning: {lost1[0]['how'] if lost1 else None} | wall: {lost2[0]['how'] if lost2 else None}")

# T15: the water-bucket reflex — dropped into a lava pit with a water bucket in the hotbar, it pours it at its feet
# by itself (the slow brain burned to death with the bucket in hand, death #10)
arena()
rcon(["fill 13 199 15 17 202 19 stone", "fill 14 200 16 16 202 18 air", "setblock 15 200 17 lava",
      "item replace entity Agent hotbar.3 with water_bucket", "tp Agent 21.5 200 17.5"])
time.sleep(1.5)   # land on the floor first: arena() drops it from y225 under slow_falling, clearing that mid-air killed it
rcon(["effect clear Agent", "effect give Agent instant_health 1 10 true"])
time.sleep(1)
rcon(["tp Agent 15.5 200 17.5 facing 15.5 200 20"])
time.sleep(4)
s = get(A + "/observe")["self"]
watered = "passed" in "".join(rcon(["execute if block 15 200 17 water"]))
check("T15 lava reflex: dropped in lava with a water bucket, it pours it and lives",
      s["health"] >= 14 and watered, f"HP {s['health']} after 4 s, water at my feet: {watered}")
rcon(["item replace entity Agent hotbar.3 with air"])

# T16: ESC while thinking — with pausing allowed, the world stands still once my action is done, runs while I act
def gametime(): return int(re.findall(r"\d+", "".join(rcon(["time query gametime"])))[-1])
rcon(["gamerule advance_time true"])
post({"type": "pause_policy", "allow": True, "idle": True})
post({"type": "turn", "dyaw": 30, "dpitch": 0, "wait": 20}); time.sleep(1)
g0 = gametime(); time.sleep(2); g1 = gametime()
post({"type": "hold", "keys": ["sneak"], "ticks": 30, "wait": 35}); g2 = gametime(); time.sleep(1.5); g3 = gametime()
post({"type": "pause_policy", "allow": False})
check("T16 idle pause: the world waits while I think (no ticks pass), and runs while I act",
      g1 == g0 and g2 - g1 >= 30 and g3 - g2 <= 6, f"thinking 2 s: +{g1-g0} ticks; acting: +{g2-g1}; after: +{g3-g2}")

# leave the world as we found it: agent back where it was with its hotbar, gamerules restored
rcon([f"item replace entity Agent hotbar.{k} with " + (f"{_held[k]['item']} {_held[k]['count']}" if k in _held else "air") for k in (0, 1)])
_p = _start["pos"]
rcon([f"tp Agent {_p[0]} {_p[1]} {_p[2]}"])
post({"type": "pause_policy", "allow": True})
rcon(["forceload remove 0 0 30 34", "tick rate 20", "gamerule advance_time true", "gamerule spawn_mobs true", "save-all"])

passed = sum(1 for _, p, _ in R if p)
print(f"\n{passed}/{len(R)} passed (with 26.3 additions)")
json.dump([{"test": n, "passed": p, "detail": d} for n, p, d in R], open(os.path.join(ROOT, "run", "verify_report.json"), "w"), ensure_ascii=False, indent=1)
