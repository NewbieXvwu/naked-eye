#!/usr/bin/env python3
"""Get back to a mark using only the place graph — the loop a person runs in a cave they half know:
   'is the way back known?  yes -> go.  no -> walk to the nearest unexplored opening, look around, ask again.'
Usage: find_way.py MARK [max_rounds]"""
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

def main(mark, rounds=8):
    tried = set()
    for i in range(rounds):
        txt = get("/places")["text"]
        line = next((l for l in txt.splitlines() if l.startswith(f"MARK {mark}:")), "")
        print(f"round {i}: {line}")
        if " — reachable" in line:
            r = act({"type": "goto", "id": mark})
            res = r["observation"]["control"]["last_result"]
            print("  goto", mark, "->", res)
            if "arrived" in res: return True
        frs = re.findall(r"^\s+(F\d+): (.*)$", txt, re.M)
        # nearest opening I haven't already walked to (list order is nearest place first)
        pick = next(((f, d) for f, d in frs if d.split(", ")[-2:] and d not in tried), None)
        if not pick: print("  no unexplored openings left"); return False
        tried.add(pick[1])
        print(f"  exploring {pick[0]}: {pick[1]}")
        act({"type": "goto", "id": pick[0]})
        act({"type": "look_around", "pitch": 10, "wait": 160})
    return False

if __name__ == "__main__":
    ok = main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8)
    sys.exit(0 if ok else 1)
