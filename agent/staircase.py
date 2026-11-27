#!/usr/bin/env python3
"""A script *the agent* wrote for itself: dig a staircase down in the facing direction.
Uses only the honest API (same as the hc CLI). Stops when it sees danger or finds the target block."""
import json, math, sys, urllib.request

from hcapi import API  # which client? HC_AGENT (see hcapi.py)
def get(p):
    with urllib.request.urlopen(API + p, timeout=60) as r: return json.load(r)
def act(o):
    req = urllib.request.Request(API + "/act", data=json.dumps(o).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=120) as r: res = json.load(r)
    if str(res.get("result", "")).startswith("refused"):   # the game paused on an alarm: stop the script, hand back to the brain
        print("⏸ script stopped:", res["result"]); sys.exit(3)
    return res

WANT = 3
def main(direction="east", steps=8, want="stone"):
    dx, dz = {"east": (1, 0), "west": (-1, 0), "south": (0, 1), "north": (0, -1)}[direction]
    for i in range(steps):
        o = get("/observe")
        x, y, z = (math.floor(v) for v in o["self"]["pos"])
        # what will we break? look first
        targets = [(x + dx, y + 1, z + dz), (x + dx, y, z + dz), (x + dx, y - 1, z + dz)]
        for t in targets:
            r = act({"type": "dig", "x": t[0], "y": t[1], "z": t[2], "wait": 300})
            obs = r["observation"]
            last = obs["control"]["last_result"]
            seen = {b["block"] for b in obs["blocks_in_view"]}
            if seen & {"lava", "water"}:
                print(f"step {i}: I can see {seen & {'lava','water'}} — stopping to think"); return
            print(f"step {i}: {last}")
        r = act({"type": "walk", "x": x + dx, "y": y - 1, "z": z + dz, "risk": 1.0, "wait": 200})
        s = r["observation"]["self"]
        inv = {it["item"]: it["count"] for it in s["inventory"]}
        print(f"  -> now at {s['pos']}, inventory {inv}")
        if inv.get("cobblestone", 0) >= WANT and want == "stone":
            print(f"have {WANT}+ cobblestone"); return

if __name__ == "__main__":
    a = sys.argv[1:]
    WANT = int(a[2]) if len(a) > 2 else 3
    main(a[0] if a else "east", int(a[1]) if len(a) > 1 else 8)
