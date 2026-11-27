#!/usr/bin/env python3
"""Experimenter bench: stone sword, 20 HP, no armour vs N zombies on a flat lit floor. Old `hit` vs new `fight`.
Prints HP left / time / whether I survived for each style. Usage: combat_test.py [zombies] [trials]"""
import os, json, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
from rcon import run as rcon
A = "http://127.0.0.1:7070"
def get(p): return json.load(urllib.request.urlopen(A + p, timeout=120))
def post(o):
    req = urllib.request.Request(A + "/act", data=json.dumps(o).encode(), method="POST")
    return json.load(urllib.request.urlopen(req, timeout=200))

MOB = "zombie"; SHIELD = [False]
def setup(n):
    if get("/observe")["self"].get("dead"): post({"type": "respawn"}); time.sleep(3)
    rcon(["forceload add 0 40 30 74", "kill @e[type=!player]", "fill 0 199 40 30 199 74 smooth_stone", "fill 0 200 40 30 210 74 air",
          "fill 0 200 40 30 200 74 light[level=15] replace air", "time set day", "gamerule spawn_mobs false",
          "clear Agent", "item replace entity Agent hotbar.0 with stone_sword", "item replace entity Agent hotbar.1 with cobblestone 64", "item replace entity Agent hotbar.2 with stone_axe", "item replace entity Agent weapon.offhand with " + ("shield" if SHIELD[0] else "air"), "effect clear Agent",
          "effect give Agent instant_health 1 10 true", "tp Agent 15.5 200 50.5 facing 15.5 201 60"])
    time.sleep(1)
    # zombies wearing nothing, no sun burning (light blocks are not sky), spread out in front
    # NB: /summon with an NBT tag skips the natural spawn setup, so a skeleton comes WITHOUT its bow and just punches.
    # (The first skeleton benches measured exactly that. Give it the bow explicitly.)
    gear = ',equipment:{mainhand:{id:"minecraft:bow",count:1}}' if MOB == "skeleton" else ""
    rcon([f"summon {MOB} {13 + 3*i}.5 200 {58.5 + (13 if MOB == 'skeleton' else 0)} {{PersistenceRequired:1b{gear}}}" for i in range(n)])
    post({"type": "hotbar", "slot": 0}); time.sleep(1)

STATS = []
ARROWS = {}
def fight(style, n):
    setup(n)
    t0 = time.time()
    while time.time() - t0 < 90:
        o = get("/observe")
        if o["self"].get("dead") or o["self"]["health"] <= 0: return ("died", round(time.time() - t0))
        zs = [e for e in o["entities"] if e["type"] == MOB]
        if not zs: return (o["self"]["health"], round(time.time() - t0))
        z = min(zs, key=lambda e: e["dist"])
        if style == "old": post({"type": "hit", "id": z["id"], "swings": 3, "wait": 200})
        elif z.get("looks", {}).get("deathTime"): time.sleep(0.2); continue
        else:
            r = post({"type": "fight", "id": z["id"], "stop_hp": 0, "crit": "crit" in style or style == "new", "kite": "kite" in style or style == "new", "strafe": "strafe" in style, "wall": "wall" in style, "weapon": "axe" if "axe" in style else "auto", "wait": 1200})
            last = r["observation"]["control"]["last_result"]
            if "crits" in last: STATS.append(last.split("(", 2)[-1].split(",")[:3])
    return ("timeout", 90)

if __name__ == "__main__":
    if len(sys.argv) > 4: MOB = sys.argv[4]
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    trials = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    post({"type": "pause_policy", "allow": False})
    urllib.request.urlopen("http://127.0.0.1:7071/debug/arrows?reset=1")
    for style in (sys.argv[3].split(",") if len(sys.argv) > 3 else ("old", "new")):
        SHIELD[0] = "shield" in style
        res = [fight(style, n) for _ in range(trials)]
        rep = get("http://127.0.0.1:7071/debug/arrows?reset=1".replace(A, "")) if False else json.load(urllib.request.urlopen("http://127.0.0.1:7071/debug/arrows?reset=1"))
        ARROWS[style] = rep
        hp = [r[0] if isinstance(r[0], (int, float)) else 0 for r in res]
        print(f"{style:>4}: mean HP left {sum(hp)/len(hp):.1f}  deaths {sum(1 for r in res if r[0]=='died')}  | " + " ".join(str(r[0]) for r in res))
        if STATS: print("      per-kill (swings, crits):", [" ".join(x) for x in STATS]); STATS.clear()
    rcon([f"kill @e[type={MOB}]", "kill Agent", "fill 0 199 40 30 210 74 air", "forceload remove 0 40 30 74", "gamerule spawn_mobs true", "save-all"])
    json.dump(ARROWS, open(os.path.join(ROOT, "run", "arrow_report.json"), "w"), indent=1)
    for st, rep in ARROWS.items(): print(f"{st:>10} arrows:", rep["outcomes"])
    time.sleep(2); post({"type": "respawn"}); post({"type": "pause_policy", "allow": True})
    print("(the bench clears the inventory; the agent was killed and respawned at the world spawn)")
