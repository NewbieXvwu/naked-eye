#!/usr/bin/env python3
"""Harvest kelp by diving to the lowest visible block of a column.

Each kelp_plant above a broken block breaks too, so digging the bottom of the
column yields the whole column at once. Drops float up; we swim up after each
dig to catch them. Air is checked every step: at 3 bubbles we surface.
"""
import sys, json, time, urllib.request

API = "http://127.0.0.1:7002"
def call(p, o=None):
    d = json.dumps(o).encode() if o is not None else None
    r = urllib.request.urlopen(API + p, data=d, timeout=600)
    return json.loads(r.read())
def obs(): return call("/observe")
def act(o): return call("/act", o)
def me(o): return o["self"]
def pos(o): return tuple(me(o)["pos"])

def kelps(o, reach=4.4):
    out = []
    for b in o.get("blocks_in_view", []):
        if b["block"] in ("kelp", "kelp_plant") and b.get("dist", 99) <= reach:
            x, y, z = b["nearest"]
            out.append((y, x, z))
    return sorted(out)   # lowest first

def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 else 12
    start = None
    digs = 0
    while digs < 200:
        o = obs()
        inv = me(o).get("inventory", [])
        got = sum(i["count"] for i in inv if i["item"] == "kelp")
        if start is None: start = got
        if got - start >= want:
            print(f"kelp {got} (+{got-start}) after {digs} digs"); return 0
        if me(o).get("air_bubbles", 99) <= 4:
            act({"type": "hold", "keys": ["jump"], "ticks": 20, "wait": 22})
            act({"type": "go", "x": pos(o)[0], "y": pos(o)[1] + 1.2, "z": pos(o)[2], "wait": 30})
            continue
        ks = kelps(o)
        if not ks:
            # swim a little toward deeper water and look again
            x, y, z = pos(o)
            act({"type": "go", "x": x, "y": y, "z": z - 3, "wait": 60})
            continue
        y, x, z = ks[0]
        r = act({"type": "dig", "x": x, "y": y, "z": z, "wait": 60})
        digs += 1
        txt = str((r.get("observation", {}).get("control", {}) or {}).get("last_result", ""))
        if "drowned" in txt.lower():
            return 2
    print("out of digs"); return 1

sys.exit(main())
