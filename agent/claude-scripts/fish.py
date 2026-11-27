# fish by ear: cast, wait for the "Fishing Bobber splashes" subtitle (a bite), reel in, repeat
import json, time, urllib.request, sys
API = "http://127.0.0.1:7070"
def get(p):
    with urllib.request.urlopen(API + p, timeout=30) as r: return json.load(r)
def act(o):
    req = urllib.request.Request(API + "/act", data=json.dumps(o).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=60) as r: res = json.load(r)
    if str(res.get("result", "")).startswith("refused"): print("⏸ stopped:", res["result"]); sys.exit(3)
    return res
def fishcount(o):
    return sum(i["count"] for i in o["self"]["inventory"] if i["item"] in ("cod", "salmon", "tropical_fish", "pufferfish"))
want = int(sys.argv[1]) if len(sys.argv) > 1 else 8
o = get("/observe"); seen = {(e.get("tick"), e.get("subtitle")) for e in o.get("events", [])}
for cast in range(40):
    if fishcount(o) >= want: break
    act({"type": "hold", "keys": ["use"], "ticks": 1}); time.sleep(1.0)
    t0 = time.time(); bit = False
    while time.time() - t0 < 45:
        o = get("/observe")
        if o["self"].get("paused"): print("paused:", o["self"]["paused"]); sys.exit(3)
        for e in o.get("events", []):
            key = (e.get("tick"), e.get("subtitle"))
            if key in seen: continue
            seen.add(key)
            if "Bobber splashes" in str(e.get("subtitle")): bit = True
        if bit: break
        time.sleep(0.15)
    act({"type": "hold", "keys": ["use"], "ticks": 1}); time.sleep(1.2)
    o = get("/observe")
    print(f"cast {cast}: {'bite' if bit else 'no bite'} -> fish {fishcount(o)}, HP {o['self']['health']}, food {o['self']['food']}")
