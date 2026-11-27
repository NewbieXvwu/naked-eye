#!/usr/bin/env python3
"""A script the agent wrote for itself: collect N of an item by mining the nearest VISIBLE block of a
given type within reach, then walking over visible drops. Uses only the honest API.

  gather.py stone cobblestone 8
"""
import json, math, sys, urllib.request

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

def main(block, item, want, max_steps=30):
    unreachable = set()   # drops I already failed to walk to (stuck in leaves, on a ledge...): don't loop on them
    for step in range(max_steps):
        o = get("/observe")
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
            # look around a little to find more
            r = act({"type": "look_around", "pitch": 35, "wait": 160})
            o = r["observation"]
            targets = [b for b in o["blocks_in_view"] if block in b["block"] and b["dist"] < 4.3]
            if not targets:
                far = [b for b in o["blocks_in_view"] if block in b["block"] and b["dist"] < 24]
                if not far:
                    print(f"step {step}: no {block} in sight; have {have}"); return
                x, y, z = far[0]["nearest"]
                r = act({"type": "go", "near": True, "x": x, "y": y, "z": z, "wait": 600})
                print(f"step {step}: approach {block} at {far[0]['nearest']} -> {r['observation']['control']['last_result']}")
                continue
        x, y, z = targets[0]["nearest"]
        r = act({"type": "dig", "x": x, "y": y, "z": z, "wait": 300})
        print(f"step {step}: {r['observation']['control']['last_result']}")
    print("ran out of steps")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]))
