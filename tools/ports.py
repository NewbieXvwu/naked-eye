#!/usr/bin/env python3
"""Ports (and the shared download cache) for this copy of Naked Eye.

Every copy of the repository is its own instance — its own world, agent, recordings and flight logs — so several
can run side by side on one machine: clone it again into another folder and ./start.sh there. Each copy takes a
"slot" the first time it starts; slot N uses the default ports + 100·N (slot 0: game 25565, RCON 25575, agent API
7070…). The slot is kept in run/ports.json; the registry of slots in use lives in the shared cache.

    ports.py               print this copy's ports (as JSON)
    ports.py api           print one of them
    ports.py assign        pick a slot now if this copy has none (start.sh does this)
    ports.py agent NAME    the API port of that agent in this world (./add_agent.sh)
"""
import json, os, socket, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = {"game": 25565, "rcon": 25575, "api": 7070, "debug": 7071,
        "review_game": 25599, "review_api": 7090, "review_debug": 7091, "review_ctrl": 7095}


def cache_dir():
    """downloaded game files, shared by every copy on this machine (an offline package carries its own .cache)"""
    if os.environ.get("HC_CACHE"): return os.environ["HC_CACHE"]
    own = os.path.join(ROOT, ".cache")
    if os.path.isdir(os.path.join(own, "mc")): return own   # (only if it really holds the game, not an empty leftover)
    return os.path.join(os.path.expanduser("~"), ".cache", "naked-eye")


def of(slot): return dict({k: v + 100 * slot for k, v in BASE.items()}, slot=slot)


def busy(p):
    s = socket.socket()
    try: s.bind(("127.0.0.1", p)); return False
    except OSError: return True
    finally: s.close()


def get(assign=False):
    f = os.path.join(ROOT, "run", "ports.json")
    if os.path.exists(f):
        with open(f) as fh: return json.load(fh)
    if not assign: return of(0)
    reg_f = os.path.join(cache_dir(), "slots.json")
    reg = json.load(open(reg_f)) if os.path.exists(reg_f) else {}
    me = os.path.realpath(ROOT)
    taken = {int(k) for k, v in reg.items() if v != me and os.path.isdir(v)}   # (a deleted copy frees its slot)
    slot = next(n for n in range(50) if n not in taken and not any(busy(p) for p in list(of(n).values())[:-1]))
    reg[str(slot)] = me
    os.makedirs(os.path.dirname(reg_f), exist_ok=True); json.dump(reg, open(reg_f, "w"), indent=1)
    os.makedirs(os.path.dirname(f), exist_ok=True); json.dump(of(slot), open(f, "w"), indent=1)
    return of(slot)


def agent_api(name):
    """the API port of an agent in this copy's world: "Agent" is the first; others joined with ./add_agent.sh
    get 7002, 7004, … (+100 per slot), remembered in run/agents.json"""
    p = get()
    if name == "Agent": return p["api"]
    f = os.path.join(ROOT, "run", "agents.json")
    reg = json.load(open(f)) if os.path.exists(f) else {}
    if name not in reg:
        reg[name] = 7000 + 100 * p["slot"] + 2 * (len(reg) + 1)
        os.makedirs(os.path.dirname(f), exist_ok=True); json.dump(reg, open(f, "w"), indent=1)
    return reg[name]


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["agent"]: print(agent_api(a[1])); sys.exit()
    p = get(assign=bool(a) and a[0] == "assign")
    print(p[a[0]] if a and a[0] in p else json.dumps(p))
