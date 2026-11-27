#!/usr/bin/env python3
"""Dig a spiral staircase down to a target Y, lit as it goes, grabbing ores on the way.

Why: the raw `mine` action stops at every ore it sees, so descending to the diamond layer by hand means
dozens of round trips. This script hands the mod a `grab` list (mine the ore and keep going) and keeps
re-issuing the staircase until the target depth, stopping early for anything a human would stop for:
lava/water next to the hole, a drop, a hit, a missing floor, or running out of torches.

usage: agent/descend.py TARGET_Y [--steps N] [--torch-every K] [--dir west] [--no-grab]
       exit 0 = reached the target, 2 = stopped for a decision (read the last line), 1 = failed
"""
import json
import math
import re
import sys
import urllib.request

sys.path.insert(0, __file__.rsplit('/', 1)[0])

API = "http://127.0.0.1:7070"
ORES = ["coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore",
        "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore", "redstone_ore",
        "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore", "diamond_ore",
        "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore"]


def call(path, data=None, timeout=600):
    req = urllib.request.Request(API + path, data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def obs():
    return call("/observe")


def act(q):
    return call("/act", q)


def feet(o):
    p = o["self"]["pos"]
    return math.floor(p[0]), math.floor(p[1]), math.floor(p[2])


def count(o, item):
    return sum(i["count"] for i in o["self"].get("inventory", []) if i["item"] == item)


# The staircase's rules live in agent/safety.py, shared by every macro script (gather, shelter, mine).
from safety import (HOSTILE, LOW_HP, HOSTILE_METRES, hostiles, stop_reason,  # noqa: E402,F401
                    in_spawn_cavern, near_water, hold_tool, close_screen)


def main(argv):
    # a target below zero is still a target: "-54" is a number, "--steps" is a flag
    args = [a for a in argv if not a.startswith("--") and not (a.startswith("-") and not a.lstrip("-").isdigit())]
    if not args:
        print(__doc__)
        return 2
    target = int(args[0])
    steps = 12
    if "--steps" in argv:
        steps = int(argv[argv.index("--steps") + 1])
    torch_every = 3
    if "--torch-every" in argv:
        torch_every = int(argv[argv.index("--torch-every") + 1])
    direction = "west"
    if "--dir" in argv:
        direction = argv[argv.index("--dir") + 1]
    grab = [] if "--no-grab" in argv else ORES

    o = obs()
    if o["self"].get("dead"):
        print("dead")
        return 1
    y0 = feet(o)[1]
    fx, _, fz = feet(o)
    if in_spawn_cavern(fx, fz) and "--force" not in argv:
        # Deaths #4, #5 and #6: this hill is the roof of an open cavern; every staircase down here has
        # broken through it. Walk out of the box first (150+ m), then dig.
        print(f"refusing to dig down at {fx}, {fz}: this is the roof of the spawn cavern "
              f"(x {58}..{112}, z {-48}..{30}). Walk out of it first, or pass --force.")
        return 2
    if near_water(o) and "--force" not in argv:
        # Death #7: a tunnel at y16 under the stony shore cut into the sea's aquifer and drowned us 45 m
        # below the waves. Water above is not something to dig under; walk inland first.
        print(f"refusing to dig down at {fx}, {fz}: sea or river is right there "
              f"({o['self'].get('biome')}, water in view) — a shaft here can open the aquifer. "
              f"Walk inland first, or pass --force.")
        return 2
    start_hp = o["self"].get("health")
    floor = LOW_HP
    if "--min-hp" in argv:
        # No food in the whole world: a human at 14 HP still digs. The floor is a promise, not a law.
        floor = int(argv[argv.index("--min-hp") + 1])
    plugs = 0
    res = ""
    print(f"descending from y={y0} to y={target}")
    for i in range(60):
        o = obs()
        why = stop_reason(o, res, start_hp, min_hp=floor)
        if why:
            print("stopping:", why)
            return 2
        x, y, z = feet(o)
        torches = count(o, "torch")
        print(f"  [{i}] at y={y} light={o['self'].get('light')} torches={torches} "
              f"coal={count(o, 'coal') + count(o, 'charcoal')} iron={count(o, 'raw_iron')}", flush=True)
        if y <= target:
            print(f"reached y={y} (target {target})")
            return 0
        if torches == 0 and torch_every:
            print("out of torches — going on unlit would spawn mobs behind me; stopping")
            return 2
        # A pickaxe in the bag mines nothing: the staircase would say "nothing in my hotbar can
        # mine stone" for every step. Put the best one in the hand before asking it to dig.
        close_screen(act, o)   # a screen left open swallows the dig (it answers "timeout")
        if hold_tool(act, o) is None:
            print("no pickaxe in the inventory — nothing to dig with")
            return 2
        r = act({"type": "mine", "dir": direction, "steps": steps, "spiral": 2,
                 "torch_every": torch_every, "grab": grab, "wait": 1200, "resume": True})
        o2 = r.get("observation", {})
        ctl = o2.get("control", {})
        res = " ".join(str(x) for x in (ctl.get("last_result"), r.get("woke")) if x)
        if r.get("woke"):
            print("  woke:", r["woke"], flush=True)
        # Hand the whole response to the shared rules: the alarm text lives in "woke", not "result".
        # (Death #5: a "FALLING — LETHAL" wake was invisible here, so the script resumed and died.)
        why = stop_reason(o2, r, start_hp, min_hp=floor)
        if why:
            print("stopping:", why)
            return 1 if why == "dead" else 2
        # The mod refuses to step into a hole it cannot fill ("no floor under the next step (x, y, z is air)").
        # A player just drops a block in the hole: plug it and carry on (this area is riddled with cave mouths).
        gap = re.search(r"no floor under the next step \((\d+), (\d+), (-?\d+) is air\)", res)
        if gap and plugs < 12:
            gx, gy, gz = (int(g) for g in gap.groups())
            filler = next((i["item"] for i in o["self"].get("inventory", [])
                           if i["item"] in ("dirt", "cobblestone", "stone", "andesite", "gravel", "oak_planks", "jungle_planks", "birch_planks", "spruce_planks", "dark_oak_planks", "acacia_planks", "mangrove_planks", "cherry_planks", "pale_oak_planks", "oak_log", "jungle_log")), None)
            if filler:
                pr = act({"type": "place", "x": gx, "y": gy, "z": gz, "item": filler, "wait": 60, "resume": True})
                print(f"  plugged the hole at {gx},{gy},{gz} with {filler}:",
                      (pr.get("observation", {}).get("control", {}).get("last_result") or ""), flush=True)
                plugs += 1
                continue
            print("no filler block left to plug holes with; stopping")
            return 2
        if "no floor" in res:
            # The mod could not plug it either: the shaft is hanging over a void and the next step is a fall.
            print("stopping:", res)
            return 2
        print("mine said:", res)
    print("ran out of rounds")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
