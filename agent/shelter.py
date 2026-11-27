#!/usr/bin/env python3
"""Emergency shelter: dig three blocks straight down, get into the hole, cap the top with the dirt.

Why this replaces claude-scripts/hole.sh (death, 2026-11-27 05:51): that script dug the column and then
asked Baritone to "walk" to the bottom. Baritone walked on the surface instead, the player stayed at the
rim, the three dirt drops stayed at the bottom of the hole, `place` had nothing to put down, and a
skeleton at 6 m shot the player from 16 HP to 0 while it tried to step in. Every step here checks the
player's own y afterwards, walks the drops up by hand if needed, and refuses to start with mobs close.

usage: agent/shelter.py [--force]   (exit 0 = capped, 2 = refused/aborted, 1 = failed)
"""
import json, math, sys, urllib.request

API = "http://127.0.0.1:7070"
HOSTILES = {"skeleton", "zombie", "husk", "drowned", "spider", "cave_spider", "creeper", "witch",
            "enderman", "stray", "pillager", "ravager", "zoglin", "piglin", "hoglin", "phantom"}
CAPS = ("dirt", "coarse_dirt", "rooted_dirt", "cobblestone", "cobbled_deepslate", "stone", "andesite",
        "gravel", "sand", "oak_planks", "birch_planks", "jungle_planks", "jungle_log", "oak_log", "birch_log")


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


def nearby_hostiles(o, r):
    return [e for e in o.get("entities", []) if e.get("type") in HOSTILES and e.get("dist", 999) < r]


def step_in(x, y, z):
    """look straight down into the shaft and nudge forward until the player is in it"""
    act({"type": "look_at", "x": x + 0.5, "y": y + 0.5, "z": z + 0.5, "wait": 4})
    for _ in range(4):
        act({"type": "hold", "keys": ["forward"], "ticks": 4})
        if feet(obs())[1] < y:
            return True
        act({"type": "look_at", "x": x + 0.5, "y": y + 0.5, "z": z + 0.5, "wait": 2})
    return False


def cap_cell(fx, fy, fz):
    """The cell to cap is the one straight above the player's own head, not the one above the column
    we started digging from. The player drifts a block or two while stepping into the shaft: at the
    trench shelter (2026-11-27 06:26) it started at (80,112,-35), ended standing at (80,110,-35.41),
    and the old code tried to cap (80,111,-36) — the player's own cell — so the hole stayed open."""
    return fx, fy + 2, fz


def cap_item(o):
    for want in CAPS:
        for i in o["self"].get("inventory", []):
            if i["item"] == want:
                return want
    return None


def main(force):
    o = obs()
    if o["self"].get("dead"):
        print("dead; respawn first")
        return 1
    close = nearby_hostiles(o, 14)
    if close and not force:
        print("refused: " + ", ".join(f"{e['type']} {e['dist']:.1f} m" for e in close)
              + " — run until nothing is in sight, then dig")
        return 2
    x, y, z = feet(o)
    for d in (1, 2, 3):
        act({"type": "dig", "x": x, "y": y - d, "z": z, "wait": 300})
        o = obs()
        if o["self"].get("dead"):
            print("died while digging")
            return 1
        if feet(o)[1] > y - d and not step_in(x, y - d, z):
            print(f"dug {d}, still not in the hole (at y={feet(o)[1]}, want {y - d})")
    o = obs()
    if feet(o)[1] != y - 3:
        # last resort: walk down with the plain A* (it will accept a 1-block drop)
        act({"type": "walk", "x": x, "y": y - 3, "z": z, "risk": 9, "wait": 120})
        o = obs()
    print(f"in the hole at y={feet(o)[1]} (started at {y}); picking up what I dug")
    for _ in range(3):
        drops = [e for e in o.get("entities", []) if e.get("type") == "item" and e.get("dist", 9) < 3]
        if not drops:
            break
        act({"type": "go", "x": drops[0]["approx_pos"][0], "y": drops[0]["approx_pos"][1],
             "z": drops[0]["approx_pos"][2], "wait": 60})
        o = obs()
    item = cap_item(o)
    if not item:
        print("no block to cap with; staying in the hole (mobs can't get in, arrows can)")
        return 1
    fx, fy, fz = feet(obs())
    cx, cy, cz = cap_cell(fx, fy, fz)
    r = act({"type": "place", "x": cx, "y": cy, "z": cz, "item": item, "wait": 60})
    msg = r.get("observation", {}).get("control", {}).get("last_result") or r.get("result") or ""
    print(f"cap at {cx},{cy},{cz}:", msg)
    o = obs()
    above = call("/sonar")["text"].splitlines()[-1].strip()
    print(f"above me: {above}")
    if "ceiling" in above or "isn't empty" in str(msg):
        act({"type": "note", "text": f"shelter: 3 blocks down at {x},{y},{z}, capped with {item}"})
        return 0
    print("cap does not read as a ceiling; still in the hole")
    return 1


if __name__ == "__main__":
    sys.exit(main("--force" in sys.argv))
