#!/usr/bin/env python3
"""Explore a cave until I see something (e.g. lava): walk to the nearest unexplored opening, look around, repeat.
Stops — and hands control back to the brain — on anything alarming (the game auto-pauses; we don't resume it).
Usage: explore.py TARGET_SUBSTRING [max_rounds]"""
import json, re, sys, urllib.request
from hcapi import API  # which client? HC_AGENT (see hcapi.py)
def get(p):
    with urllib.request.urlopen(API + p, timeout=120) as r: return json.load(r)
def act(o):
    o.setdefault("wait", 1200)
    req = urllib.request.Request(API + "/act", data=json.dumps(o).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=180) as r: res = json.load(r)
    if str(res.get("result", "")).startswith("refused"):   # the game paused on an alarm: stop the script, hand back to the brain
        print("⏸ script stopped:", res["result"]); sys.exit(3)
    return res

def alarm(r):
    s = r.get("observation", {}).get("self", {})
    return r.get("woke") or s.get("paused")

def main(target, rounds=12):
    tried = set()
    for i in range(rounds):
        seen = [b for b in get("/observe")["blocks_in_view"] if target in b["block"]]
        if seen: print(f"round {i}: SEE {seen[0]['block']} at {seen[0]['nearest']} ~{seen[0]['dist']} m"); return True
        txt = get("/places")["text"]
        frs = re.findall(r"^\s+(F\d+): (.*)$", txt, re.M)
        pick = next(((f, d) for f, d in frs if d.split(" side")[0] + d.split("), ")[-1] not in tried), None)
        if not pick: print(f"round {i}: no unexplored openings left"); return False
        tried.add(pick[1].split(" side")[0] + pick[1].split("), ")[-1])
        print(f"round {i}: exploring {pick[0]}: {pick[1]}")
        for o in ({"type": "goto", "id": pick[0]}, {"type": "look_around", "pitch": 10, "wait": 160}):
            r = act(o)
            if alarm(r): print("  ⚠ stopping, alarm:", alarm(r)); return False
    return False

if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 12) else 1)
