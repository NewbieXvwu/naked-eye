#!/usr/bin/env python3
"""A script the agent wrote for itself: collect N of an item by mining the nearest VISIBLE block of a
given type within reach, then walking over visible drops. Uses only the honest API.

  gather.py stone cobblestone 8
"""
import json, math, os, sys, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from safety import stop_reason, LOW_HP  # noqa: E402  the rules every macro shares (see agent/safety.py)

API = "http://127.0.0.1:7070"
def get(p):
    with urllib.request.urlopen(API + p, timeout=60) as r: return json.load(r)
def act(o):
    req = urllib.request.Request(API + "/act", data=json.dumps(o).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=200) as r: res = json.load(r)
    if str(res.get("result", "")).startswith("refused"):   # the game paused on an alarm: stop the script, hand back to the brain
        print("⏸ script stopped:", res["result"]); sys.exit(3)
    return res

def count(obs, item):
    return sum(i["count"] for i in obs["self"]["inventory"] if item in i["item"])

def main(block, item, want, max_steps=30, floor=LOW_HP):
    unreachable = set()   # drops I already failed to walk to (stuck in leaves, on a ledge...): don't loop on them
    start_hp = get("/observe")["self"].get("health")
    for step in range(max_steps):
        o = get("/observe")
        # Death #3: this loop kept chopping while a zombie hit the player from 18 HP to 8. Chopping is
        # never worth being hit for: hand back to the brain, which can fight, flee or dig in.
        why = stop_reason(o, start_hp=start_hp, min_hp=floor)
        if why:
            print(f"⏸ stopped: {why}")
            return 3
        have = count(o, item)
        if have >= want:
            print(f"have {have} {item}"); return
        # pick up anything that dropped and is visible
        drops = [e for e in o["entities"] if e["type"] == "item" and e["dist"] < 6 and tuple(e["approx_pos"]) not in unreachable]
        if drops:
            x, y, z = drops[0]["approx_pos"]
            r = act({"type": "go", "x": x, "y": y, "z": z, "wait": 300})
            res = r['observation']['control']['last_result']
            if "arrived" not in res: unreachable.add((x, y, z))
            print(f"step {step}: pick up {drops[0].get('item')} -> {res}")
            continue
        targets = [b for b in o["blocks_in_view"] if block in b["block"] and b["dist"] < 4.3]
        if not targets:
            # look around a little to find more (the sweep ends looking up 40°, so level off before searching —
            # otherwise tree trunks on the horizon are all out of view and this says "nothing in sight")
            act({"type": "look_around", "pitch": 35, "wait": 160})
            o = get("/observe")
            act({"type": "look", "yaw": o["self"]["yaw"], "pitch": 0, "wait": 8})
            o = get("/observe")
            targets = [b for b in o["blocks_in_view"] if block in b["block"] and b["dist"] < 4.3]
            if not targets:
                # a trunk I already failed to reach (Baritone "no known route") must not be chosen again: it ate
                # minutes of wall-clock time in a forest where two trees grew into each other
                far = [b for b in o["blocks_in_view"] if block in b["block"] and b["dist"] < 24
                       and tuple(b["nearest"]) not in unreachable]
                if not far:
                    print(f"step {step}: no reachable {block} in sight; have {have}"); return
                x, y, z = far[0]["nearest"]
                r = act({"type": "go", "near": True, "x": x, "y": y, "z": z, "wait": 600})
                res = r['observation']['control']['last_result']
                print(f"step {step}: approach {block} at {far[0]['nearest']} -> {res}")
                if "stopped" in res or "no known route" in res:
                    unreachable.add((x, y, z))
                continue
        x, y, z = targets[0]["nearest"]
        r = act({"type": "dig", "x": x, "y": y, "z": z, "wait": 300})
        print(f"step {step}: {r['observation']['control']['last_result']}")
    print("ran out of steps")

def _floor(argv):
    """--min-hp N: the HP floor. With no food in the world, a hurt human still chops wood."""
    if "--min-hp" in argv: return int(argv[argv.index("--min-hp") + 1])
    return LOW_HP


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]), floor=_floor(sys.argv))
