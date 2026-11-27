#!/usr/bin/env python3
"""Experimenter's test: digging into lava in the Nether, where lava flows three times as fast as above ground
(a block every 10 ticks, not 30) and there's no water to put yourself out. Run with the world up:
    python3 tools/verify_nether.py
"""
import json, os, sys, time, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import ports
from rcon import run as _rcon

A = ports.get()["api"]; D = f"http://127.0.0.1:{ports.get()['debug']}"
def post(obj, timeout=120):
    req = urllib.request.Request(f"http://127.0.0.1:{A}/act", data=json.dumps(obj).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.load(r)
def obs(): return json.load(urllib.request.urlopen(f"http://127.0.0.1:{A}/observe?cursor=999999999"))
def get(url): return json.load(urllib.request.urlopen(url, timeout=30))
def rcon(cmds): return _rcon(["execute in minecraft:the_nether run " + c if c.split()[0] in ("fill", "setblock", "tp", "forceload") else c for c in cmds])
R = []
def check(name, ok, detail): R.append(ok); print(("PASS " if ok else "FAIL ") + name + " — " + detail)

X, Y, Z = 0, 100, 0          # a sealed netherrack box in the Nether: rows of test tunnels along z
def recall(b): return json.load(urllib.request.urlopen(f"http://127.0.0.1:{A}/recall?block={b}"))["text"]
post({"type": "pause_policy", "allow": False})
_rcon(["execute in minecraft:overworld run tp Agent 80.5 141 -15.5"]); post({"type": "wait", "wait": 40})
over_before = recall("grass_block")
rcon(["forceload add 0 0 30 40", "tp Agent 15.5 120 15.5"]); time.sleep(4)   # (into the Nether; let its chunks arrive)
post({"type": "wait", "wait": 20})
nether = recall("grass_block")

def case(name, lava, steps=6):
    """a fresh box, a corridor 2 long to start in, `lava` = cells (dx along the tunnel, dy from the floor, dz) of lava"""
    get(f"{D}/debug/forget?box=-1,98,-1,31,112,41")
    rcon([f"fill {X} {Y-1} {Z} {X+30} {Y+6} {Z+12} netherrack", f"fill {X+2} {Y} {Z+6} {X+3} {Y+1} {Z+6} air"]
         + [f"setblock {X+4+dx} {Y+dy} {Z+6+dz} lava" for dx, dy, dz in lava]
         + ["item replace entity Agent hotbar.0 with diamond_pickaxe", "item replace entity Agent hotbar.1 with netherrack 32",
            "effect clear Agent", "effect give Agent instant_health 1 10 true",
            f"tp Agent {X+2.5} {Y} {Z+6.5} facing {X+10.5} {Y+1.6} {Z+6.5}"])
    post({"type": "hotbar", "slot": 0}); post({"type": "wait", "wait": 20})
    if obs()["self"].get("on_fire"):   # (still burning from before: put it out first, or the case measures that)
        rcon(["effect give Agent fire_resistance 20 0 true"]); post({"type": "wait", "wait": 320})
        rcon(["effect clear Agent", "effect give Agent instant_health 1 10 true"]); post({"type": "wait", "wait": 10})
    hp0 = obs()["self"]["health"]
    r = post({"type": "mine", "dir": "east", "steps": steps, "down": False, "wait": 600})
    if "refused" in str(r.get("result")) or "not" in str(r.get("result"))[:4]: print("   (mine said:", r.get("result"), ")")
    post({"type": "wait", "wait": 60})   # (afterwards: is lava still coming, am I burning?)
    o = obs(); s = o["self"]
    check(f"{name}", s["health"] >= hp0 - 1 and not s.get("on_fire") and not s.get("dead"),
          f"HP {hp0} -> {s['health']}{' ON FIRE' if s.get('on_fire') else ''}{' DEAD' if s.get('dead') else ''}; task: {o['control']['last_result']}")
    if s.get("dead"): post({"type": "respawn"}); time.sleep(3); rcon(["tp Agent 15.5 120 15.5"]); time.sleep(3)
    if obs()["self"].get("on_fire"):   # (burn it off safely before the next case: no water in the Nether)
        rcon(["effect give Agent fire_resistance 20 0 true"]); post({"type": "wait", "wait": 320})

try:
    case("N1 lava at foot level two blocks in", [(2, 0, 0)])
    case("N2 lava at head level three blocks in", [(3, 1, 0)])
    case("N3 lava above the head of the next step (it falls in)", [(2, 2, 0)])
    case("N4 lava beside the next step", [(2, 0, 1)])
    case("N5 lava right behind the first block", [(1, 0, 0)])
    _rcon(["execute in minecraft:overworld run tp Agent 80.5 141 -15.5"]); post({"type": "wait", "wait": 40})
    over_after = recall("grass_block")
    check("N6 memory per dimension: no overworld grass remembered in the Nether, and it's all there again back home",
          "don't remember" not in over_before and "don't remember" in nether and "don't remember" not in over_after,
          f"overworld: {over_before.splitlines()[0]!r} | Nether: {nether!r} | back: {over_after.splitlines()[0]!r}")
finally:
    rcon(["forceload remove 0 0 30 40"])
    post({"type": "pause_policy", "allow": True})
print(f"\n{sum(R)}/{len(R)} passed")
