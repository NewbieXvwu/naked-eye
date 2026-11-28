#!/usr/bin/env python3
"""Cheat mode: stand by a blaze spawner and fight blazes until I hold N blaze rods.

Fights the nearest blaze within 14 m (exact positions: cheat mode), eats when HP < 12, stops for good below 8 HP,
on anything the reflexes can't handle (lava, falling), or when no blaze shows up for a while.
usage: agent/blaze_farm.py [RODS=7]
"""
import json, os, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hcapi import API

def get(p):
    with urllib.request.urlopen(API + p, timeout=None) as r: return json.load(r)

def act(o):
    o.setdefault("resume", True)   # I'm the brain here: an alarm is answered by the checks below
    req = urllib.request.Request(API + "/act", data=json.dumps(o).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=None) as r: return json.load(r)

def me():
    return get("/observe")["self"]

def count(s, item): return sum(i["count"] for i in s["inventory"] if i["item"] == item)

def eat(s):
    foods = [i for i in s["inventory"] if i["item"] in ("golden_carrot", "cooked_beef", "cooked_porkchop", "bread", "baked_potato", "golden_apple")]
    if not foods or s["food"] >= 20 and s["health"] >= 12: return False
    f = foods[0]
    if f["slot"] > 8: act({"type": "to_hotbar", "slot": f["slot"], "hotbar": 8, "wait": 2}); slot = 8
    else: slot = f["slot"]
    act({"type": "hotbar", "slot": slot, "wait": 2})
    act({"type": "eat", "wait": 60})
    return True

def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 else 7
    idle = 0
    while True:
        s = me()
        rods = count(s, "blaze_rod")
        if rods >= want: print(f"done: {rods} blaze rods, HP {s['health']}"); return 0
        if s.get("dead"): print("dead"); return 1
        if s["health"] < 12: print(f"stopping: HP {s['health']} (heal first, out of sight)"); return 1
        crowd = get("/ents?r=12&type=blaze&n=8")["entities"]
        if len(crowd) >= 3: print(f"stopping: {len(crowd)} blazes within 12 m — too many at once"); return 1
        if s.get("on_fire") and s["health"] < 9: print("on fire and hurt: stopping"); return 1
        if s["health"] < 12 and eat(s): continue
        ents = get("/ents?r=10&type=blaze&n=5")["entities"]
        ents = [e for e in ents if e.get("hp", 0) > 0 and e.get("visible")]
        if not ents:   # cheat mode: I know where the hidden ones are — walk up to the nearest (within 40 m)
            near = [e for e in get("/ents?r=40&type=blaze&n=8")["entities"] if e.get("hp", 0) > 0]
            import math
            # never walk into a crowd: a blaze with two others within 6 m of it is three fireball volleys at once
            # (death #7: the script walked me to "the nearest hidden blaze" — into five of them round the spawner)
            near = [e for e in near if sum(1 for o in near if o is not e and math.dist(o["pos"], e["pos"]) < 6) < 2]
            if near:
                b = near[0]; idle = 0
                act({"type": "baritone", "cmd": f"goto {int(b['pos'][0])} {int(b['pos'][1]) - 1} {int(b['pos'][2])}", "limit": 160, "stop_hp": 9, "wait": 160})
                if get("/status")["task"].startswith("baritone"): act({"type": "stop", "wait": 1})
                continue
        if not ents:
            idle += 1
            if idle > 60: print("no blaze for a while"); return 1
            # shield up toward the nearest blaze while waiting for one to come close
            far = get("/ents?r=24&type=blaze&n=1")["entities"]
            if far: act({"type": "look_at", "x": far[0]["pos"][0], "y": far[0]["pos"][1] + 1, "z": far[0]["pos"][2], "wait": 1}); act({"type": "hold", "keys": ["use"], "ticks": 40, "wait": 40})
            else: act({"type": "wait", "wait": 40})
            continue
        idle = 0
        b = ents[0]
        r = act({"type": "fight", "id": b["id"], "crit": True, "stop_hp": 8, "wait": 400})
        # a hurt alarm wakes me, but the fight goes on: keep it running unless HP got low
        while get("/status")["task"].startswith("fight") and me()["health"] >= 8:
            act({"type": "wait", "wait": 100, "until_idle": True})
        if get("/status")["task"].startswith("fight"): act({"type": "stop", "wait": 1})
        res = get("/status")["last_result"]
        print(f"blaze #{b['id']} at {b['dist']} m: {res.split('-> ')[-1]}  | HP {me()['health']}, rods {count(me(), 'blaze_rod')}")
        # pick up what fell (rods drop at the blaze's feet, maybe off the platform's edge: Baritone fetches)
        items = [e for e in get("/ents?r=12&type=item&n=10")["entities"] if e.get("item") == "blaze_rod"]
        for it in items:
            act({"type": "baritone", "cmd": f"goto {int(it['pos'][0])} {int(it['pos'][1])} {int(it['pos'][2])}", "wait": 200})

if __name__ == "__main__":
    sys.exit(main())
