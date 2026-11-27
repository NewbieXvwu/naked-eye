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
    f = os.path.join(ROOT, "run", "server", "config", "naked-eye", "rules.properties")
    open(f, "w").write("think_limit_seconds=2\n")
    time.sleep(5); g6 = gametime(); time.sleep(2); g7 = gametime()
    told = any("the limit here" in (e.get("text") or "") for e in
               json.load(urllib.request.urlopen(f"http://127.0.0.1:{B}/observe?cursor=0"))["events"])
    check("C6 with a 2 s limit, the world goes on without the agent thinking too long (and it's told)",
          g7 - g6 >= 20 and told, f"+{g7 - g6} ticks in 2 s; Tester2 told: {told}")
    os.remove(f)
    # release both (Tester2's thinking ran out; give the world back to the rules)
    t = threading.Thread(target=post, args=(B, {"type": "wait", "wait": 3})); t.start(); post(A, {"type": "wait", "wait": 10}); t.join()

    # ---------------------------------------------------------------- talking (free chat: the default)
    cfg = os.path.join(ROOT, "run", "server", "config", "naked-eye")
    def heard(port, since):
        ev = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/observe?cursor={since}"))["events"]
        return [e for e in ev if e.get("kind") == "chat" and "channel" in e]
    def cur(port): return json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/observe"))["cursor"]
    open(os.path.join(cfg, "teams.txt"), "w").write("red: Agent, Tester2\n")
    time.sleep(2.5)
    c0 = cur(B); g0 = gametime()
    post(A, {"type": "say", "channel": "public", "text": "hello everyone"})
    post(A, {"type": "say", "channel": "team", "text": "red team, follow me"})
    post(A, {"type": "say", "channel": "private", "to": "Tester2", "text": "just between us"})
    time.sleep(1); h = heard(B, c0); g1 = gametime()
    got = {(e["channel"], e["from"], e["text"]) for e in h}
    check("C7 free chat: public, team and private all arrive, with who and which channel, and no world time passes",
          {("public", "Agent", "hello everyone"), ("team", "Agent", "red team, follow me"), ("private", "Agent", "just between us")} <= got and g1 == g0,
          f"Tester2 heard {sorted(got)}; +{g1 - g0} ticks")
    open(os.path.join(cfg, "teams.txt"), "w").write("red: Agent\nblue: Tester2\n")
    time.sleep(2.5); c0 = cur(B)
    post(A, {"type": "say", "channel": "team", "text": "secret red plan"}); time.sleep(1)
    check("C8 team chat stays in the team", not any("secret" in e["text"] for e in heard(B, c0)), f"Tester2 heard {heard(B, c0)}")
    out = {}
    t = threading.Thread(target=lambda: out.update(post(B, {"type": "wait", "wait": 400}, None))); t.start(); time.sleep(1)
    post(A, {"type": "say", "channel": "public", "text": "Tester2, are you there?"}); t.join(10)
    check("C9 a message wakes an agent waiting for its action", "a message" in out.get("woke", ""), f"Tester2's wait: woke={out.get('woke')!r}")
    logs = sorted(os.listdir(os.path.join(ROOT, "run", "server", "chat")))
    kept = open(os.path.join(ROOT, "run", "server", "chat", logs[-1])).read()
    check("C10 every message is kept in run/server/chat/", "just between us" in kept and "secret red plan" in kept,
          f"{logs[-1]}: {kept.count(chr(10))} lines")

    # ---------------------------------------------------------------- talking takes time (the switch)
    open(f, "w").write("chat_takes_time=true\nchars_per_second=15\n"); time.sleep(2.5)
    out = {}
    t = threading.Thread(target=lambda: out.update(post(B, {"type": "wait", "wait": 400}, None))); t.start(); time.sleep(1)
    g0 = gametime(); r = post(A, {"type": "say", "channel": "public", "text": "hello there friend"}, None); t.join(10); g1 = gametime()
    os.remove(f); time.sleep(2.5)
    check("C11 with chat_takes_time, saying 18 characters takes ~34 ticks of world time, then it arrives",
          30 <= g1 - g0 <= 50 and "hello there friend" in out.get("woke", ""), f"+{g1 - g0} ticks; {r.get('result')!r}; Tester2 woke={out.get('woke')!r}")

    # ---------------------------------------------------------------- seeing other players
    rcon(["fill 109 200 103 111 203 108 stone", "tp Agent 105.5 200 105.5 facing 115.5 201 105.5", "tp Tester2 115.5 200 105.5"])
    t = threading.Thread(target=post, args=(B, {"type": "wait", "wait": 10})); t.start(); post(A, {"type": "wait", "wait": 10}); t.join()
    o = json.load(urllib.request.urlopen(f"http://127.0.0.1:{A}/observe"))
    tags = [e for e in o["entities"] if e.get("name") == "Tester2"]
    check("C12 another player's name tag shows through a wall (as in the game), with its team",
          tags and tags[0].get("detail") == "only_name_tag_visible" and tags[0].get("team") == "blue"
          and any(p["name"] == "Tester2" for p in o.get("players_online", [])), f"{tags} | online: {o.get('players_online')}")
    t = threading.Thread(target=post, args=(B, {"type": "hold", "keys": ["sneak"], "ticks": 80, "wait": 82}, None)); t.start()
    post(A, {"type": "wait", "wait": 15})
    o = json.load(urllib.request.urlopen(f"http://127.0.0.1:{A}/observe"))
    check("C13 ... but not while they sneak", not [e for e in o["entities"] if e.get("name") == "Tester2"], f"{[e for e in o['entities'] if e.get('type') == 'player']}")
    threading.Thread(target=post, args=(A, {"type": "wait", "wait": 200}, None), daemon=True).start(); t.join(15)   # (my next action ends this wait)
    rcon(["fill 109 200 103 111 203 108 air"])

    # ---------------------------------------------------------------- a dead agent is thinking too
    rcon(["kill Tester2"]); time.sleep(1)
    done = threading.Event()
    threading.Thread(target=lambda: (post(A, {"type": "wait", "wait": 20}, None), done.set()), daemon=True).start()
    time.sleep(2); g0 = gametime(); time.sleep(2); g1 = gametime()
    post(B, {"type": "respawn"}); post(B, {"type": "wait", "wait": 10}); done.wait(10)   # (shorter than the Agent's wait: it must end first)
    check("C14 on the death screen the world waits for the dead agent's decision", g1 == g0 and done.is_set(), f"+{g1 - g0} ticks while Tester2 lay dead; Agent's wait finished after the respawn: {done.is_set()}")
finally:
    for n in ("teams.txt", "rules.properties"):
        p = os.path.join(ROOT, "run", "server", "config", "naked-eye", n)
        if os.path.exists(p): os.remove(p)
    rcon(["team remove red", "team remove blue"])
    pid = os.path.join(ROOT, "run", "client-Tester2.pid")
    if os.path.exists(pid):
        try: os.kill(int(open(pid).read()), 15)
        except Exception: pass
        os.remove(pid)
    for _ in range(30):   # (gone from the server before it leaves the list, or it'd be turned into a watcher on the way out)
        if "Tester2" not in "".join(rcon(["list"])): break
        time.sleep(1)
    rcon(["forceload remove 100 100 120 120"])
    a = os.path.join(ROOT, "run", "server", "config", "naked-eye", "agents.txt")   # (Tester2 was only visiting)
    keep = [l for l in open(a) if l.strip() != "Tester2"]
    open(a, "w").writelines(keep)
print(f"\n{sum(R)}/{len(R)} passed")
