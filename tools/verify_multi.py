#!/usr/bin/env python3
"""Experimenter's test of the shared clock (several agents, one world): the world runs only while every agent acts.

Starts a second agent "Tester2" (./add_agent.sh), checks the rules, then stops it again. Uses RCON (cheating for an
agent, fine for the experimenter). Run with the world up: python3 tools/verify_multi.py
"""
import json, os, subprocess, sys, threading, time, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import ports
from rcon import run as rcon

A, B = ports.agent_api("Agent"), ports.agent_api("Tester2")
def post(port, obj, timeout=60):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/act", data=json.dumps(obj).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.load(r)
def gametime(): return int(__import__("re").findall(r"\d+", "".join(rcon(["time query gametime"])))[-1])
def z(name): return float(__import__("re").findall(r"(-?[\d.]+)d", "".join(rcon([f"data get entity {name} Pos[2]"])))[-1])
R = []
def check(name, ok, detail): R.append(ok); print(("PASS " if ok else "FAIL ") + name + " — " + detail)

subprocess.run([os.path.join(ROOT, "add_agent.sh"), "Tester2"], check=True)
try:
    rcon(["forceload add 100 100 120 120", "fill 100 199 100 120 199 120 stone", "fill 100 200 100 120 203 120 air",
          "tp Agent 105.5 200 110.5", "tp Tester2 115.5 200 100.5 0 0", "time set day"])
    t = threading.Thread(target=post, args=(B, {"type": "wait", "wait": 10, "resume": True})); t.start()
    post(A, {"type": "wait", "wait": 10, "resume": True}); t.join(); time.sleep(1)

    # Tester2 walks 40 ticks; Agent thinks, acts 20, thinks, acts until Tester2 is done
    out = {}
    walk = threading.Thread(target=lambda: out.update(post(B, {"type": "hold", "keys": ["forward"], "ticks": 40, "wait": 42}, None)))
    g0, z0 = gametime(), z("Tester2"); walk.start(); time.sleep(3)
    g1, z1 = gametime(), z("Tester2")
    check("C1 one agent thinking holds the world: the other's walk waits", g1 == g0 and abs(z1 - z0) < 0.05,
          f"3 s of Agent thinking: +{g1 - g0} ticks, Tester2 moved {z1 - z0:.2f} m")
    post(A, {"type": "wait", "wait": 20}); time.sleep(0.5)
    g2, z2 = gametime(), z("Tester2")
    check("C2 both acting: the world runs, the walk goes on", 20 <= g2 - g1 <= 30 and z2 - z1 > 3,
          f"Agent acted 20 ticks: +{g2 - g1} ticks, Tester2 walked {z2 - z1:.1f} m")
    time.sleep(2); g3 = gametime()
    check("C3 Agent thinking again: frozen again", g3 == g2, f"+{g3 - g2} ticks in 2 s")
    threading.Thread(target=lambda: post(A, {"type": "wait", "wait": 40}, None), daemon=True).start()
    walk.join(30)
    check("C4 the walk ends after 40 ticks of world time, however long the thinking took",
          not walk.is_alive() and "woke" not in out, f"Tester2's hold returned: {out.get('result')!r}")
    time.sleep(2); g4 = gametime(); time.sleep(2); g5 = gametime()
    check("C5 Tester2 now thinks: Agent's pending action waits for it", g5 == g4, f"+{g5 - g4} ticks in 2 s")

    # a thinking time limit: past it, the world goes on without the slow one
    f = os.path.join(ROOT, "run", "server", "config", "naked-eye", "clock.properties")
    open(f, "w").write("think_limit_seconds=2\n")
    time.sleep(5); g6 = gametime(); time.sleep(2); g7 = gametime()
    told = any("the limit here" in (e.get("text") or "") for e in
               json.load(urllib.request.urlopen(f"http://127.0.0.1:{B}/observe?cursor=0"))["events"])
    check("C6 with a 2 s limit, the world goes on without the agent thinking too long (and it's told)",
          g7 - g6 >= 20 and told, f"+{g7 - g6} ticks in 2 s; Tester2 told: {told}")
    os.remove(f)
finally:
    pid = os.path.join(ROOT, "run", "client-Tester2.pid")
    if os.path.exists(pid):
        try: os.kill(int(open(pid).read()), 15)
        except Exception: pass
        os.remove(pid)
    rcon(["forceload remove 100 100 120 120"])
    a = os.path.join(ROOT, "run", "server", "config", "naked-eye", "agents.txt")   # (Tester2 was only visiting)
    keep = [l for l in open(a) if l.strip() != "Tester2"]
    open(a, "w").writelines(keep)
print(f"\n{sum(R)}/{len(R)} passed")
