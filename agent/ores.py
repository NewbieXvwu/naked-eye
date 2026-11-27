#!/usr/bin/env python3
"""Mine the ore I can actually see, and nothing else.

Why this exists: the staircase macros keep meeting caves and water pockets (three lives went to
falling into the spawn cavern), while a human in a cave simply looks at the ore on the wall and
picks it. This walks to each ore that is in view, digs it, walks onto the drop, and keeps the place
lit. It never digs a block it has not seen: if there is no ore in view it looks around, and if
there is still none it stops.

usage: python3 agent/ores.py [--want iron_ore,coal_ore] [--max 32] [--reach 4.4]
       exit 0 = did some work, 1 = could not move, 2 = refused (danger), 3 = nothing in sight
"""
import json
import math
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from safety import stop_reason, near_water, res_text, hold_tool, close_screen  # noqa: E402

from hcapi import API  # which client? HC_AGENT (see hcapi.py)
DEFAULT_WANT = ("iron_ore", "deepslate_iron_ore", "coal_ore", "deepslate_coal_ore",
                "copper_ore", "gold_ore", "redstone_ore", "lapis_ore")


def call(path, data=None, timeout=180):
    req = urllib.request.Request(API + path, data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def obs():
    return call("/observe")


def act(q, timeout=300):
    q.setdefault("resume", True)
    return call("/act", q, timeout)


def pos(o):
    p = o["self"]["pos"]
    return [round(p[0], 1), round(p[1], 1), round(p[2], 1)]


def look_at(o, cell, pitch=0.0):
    me = pos(o)
    dx, dz = cell[0] + 0.5 - me[0], cell[2] + 0.5 - me[2]
    yaw = -math.degrees(math.atan2(dx, dz)) % 360.0
    act({"type": "look", "yaw": yaw, "pitch": pitch, "wait": 3})


def ores_in_view(o, want):
    out = [b for b in o.get("blocks_in_view") or [] if b.get("block") in want]
    out.sort(key=lambda b: b.get("dist", 99))
    return out


def look_around(o):
    for yaw in (0, 45, 90, 135, 180, 225, 270, 315):
        for pitch in (-20, 10, 40):
            act({"type": "look", "yaw": yaw, "pitch": pitch, "wait": 2})
    return obs()


def main(argv):
    want, top, reach = DEFAULT_WANT, 32, 4.4
    for i, a in enumerate(argv):
        if a == "--want" and i + 1 < len(argv):
            want = tuple(x.strip() for x in argv[i + 1].split(","))
        if a == "--max" and i + 1 < len(argv):
            top = int(argv[i + 1])
        if a == "--reach" and i + 1 < len(argv):
            reach = float(argv[i + 1])
    o = obs()
    start_hp = o["self"]["health"]
    if near_water(o) and "--force" not in argv:
        # Death #7: under the sea, an ore block can be the wall of an aquifer. Mining here is how the
        # agent drowned 45 m below the waves; walk inland (or pass --force if the roof is solid rock).
        print(f"refusing to mine at {pos(o)}: sea or river is right there "
              f"({o['self'].get('biome')}, water in view) — walk inland first, or pass --force.")
        return 2
    done = 0
    blacklist = set()   # cells that would not come out: never dig the same block twice
    for step in range(top * 4):
        why = stop_reason(o, start_hp=start_hp)
        if why:
            print(f"⏸ stopped: {why}")
            return 2
        cands = [c for c in ores_in_view(o, want) if tuple(c["nearest"]) not in blacklist]
        if not cands:
            o = look_around(o)
            cands = [c for c in ores_in_view(o, want) if tuple(c["nearest"]) not in blacklist]
            if not cands:
                print(f"no ore in sight ({done} mined) at {pos(o)}")
                return 0 if done else 3
        b = cands[0]
        cell = tuple(b["nearest"])
        # A pickaxe in the bag mines nothing (the whole 2026-11-27 iron trip): hold it first.
        close_screen(act, o)
        if hold_tool(act, o) is None:
            print("no pickaxe in the inventory — nothing to mine with")
            return 2
        if b.get("dist", 99) > 24:
            print(f"nearest {b['block']} is {b.get('dist'):.0f} m away — walking to it")
        r = act({"type": "go", "near": True, "x": cell[0], "y": cell[1], "z": cell[2]}, timeout=600)
        res = str(r.get("result") or "")
        if "error" in r or res.startswith("refused"):
            print(f"can't get to {b['block']} at {cell}: {res or r.get('error')}")
            break
        if "arrived" not in res and "stopped" in res:
            print(f"approach: {res}")
        look_at(obs(), cell)
        r = act({"type": "dig", "x": cell[0], "y": cell[1], "z": cell[2]}, timeout=300)
        out = res_text(r)   # "result" is just the queued "ok"; the task's words are in last_result
        print(f"{b['block']} {cell}: {out}")
        if "error" in r or out.startswith("refused") or ("done" not in out and "cleared" not in out):
            print(f"giving up on that one: {out or r.get('error')}")
            blacklist.add(cell)
            continue
        # the drop stays on the floor: stand on it
        act({"type": "go", "x": cell[0], "y": cell[1], "z": cell[2]}, timeout=600)
        o = obs()
        if any(tuple(x["nearest"]) == cell and x["block"] == b["block"]
               for x in o.get("blocks_in_view", [])):
            print(f"  still there after the dig -> skipping {cell} from now on")
            blacklist.add(cell)
            continue
        done += 1
        o = obs()
        if o["self"]["light"] <= 0:
            act({"type": "place", "item": "torch"}, timeout=120)
            o = obs()
    print(f"mined {done} ore block(s), at {pos(o)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(1)
