#!/usr/bin/env python3
"""Look into the hole before you step into it.

Death, 2026-11-27 07:02: the spawn hill is the roof of a big open cavern (mobs at y88-119 below the
surface). `hc stairs north 7` dug the first block, walked forward, and there was nothing under it:
y135 -> y100, dead on arrival ("took damage (20 -> 0 HP) after 0 steps"). A human standing at the top
of that ledge would have dug one block, looked down the hole, seen darkness and stopped.

This probes exactly that way: dig the block at our feet level in a direction, then look down into the
hole and read the crosshair. "crosshair: stone@[x, y, z]" = there is a floor; "crosshair: nothing" =
the hole is deeper than arm's length -> do not step in.

usage: python3 agent/probe.py [--dir north|south|east|west]   (exit 0 = floor, 2 = void, 1 = error)
"""
import json
import math
import sys
import urllib.request

API = "http://127.0.0.1:7070"
STEPS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def call(path, data=None, timeout=180):
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


def crosshair(o):
    """The text of the `crosshair:` line, e.g. "stone@[73, 133, -4]" or "nothing"."""
    for line in (o.get("text") or "").splitlines():
        line = line.strip()
        if line.startswith("crosshair:"):
            return line.split(":", 1)[1].strip()
    return ""


def floor_in_hole(o):
    """True when the crosshair points at a block (a floor under the hole we just opened)."""
    c = crosshair(o)
    return bool(c) and c != "nothing"


def main(argv):
    direction = "north"
    for i, a in enumerate(argv):
        if a == "--dir" and i + 1 < len(argv):
            direction = argv[i + 1]
    if direction not in STEPS:
        print(f"unknown direction {direction}")
        return 1
    o = obs()
    fx, fy, fz = feet(o)
    dx, dz = STEPS[direction]
    cell = (fx + dx, fy, fz + dz)
    # Standing on the surface, dig the block at our feet level in front: a 1-deep step down.
    act({"type": "dig", "pos": list(cell), "wait": 20})
    act({"type": "look", "yaw": 0 if dx else (90 if dz > 0 else -90), "pitch": -55, "wait": 5})
    o = obs()
    c = crosshair(o)
    fx2, fy2, fz2 = feet(o)
    if floor_in_hole(o):
        print(f"probe {cell}: floor below ({c}) - safe to stair down")
        return 0
    print(f"probe {cell}: nothing within reach below ({c or 'no crosshair'}) - VOID, do not step in")
    # Put a block back so nobody falls in the hole we opened, if we can.
    for block in ("cobblestone", "dirt"):
        r = act({"type": "place", "pos": list(cell), "item": block, "wait": 20})
        if "placed" in json.dumps(r):
            print(f"plugged the probe hole with {block}")
            break
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
