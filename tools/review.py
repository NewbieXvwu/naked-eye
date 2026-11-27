#!/usr/bin/env python3
"""Review a played session: rebuild it from its recording and look at any moment through two pairs of eyes.

    tools/review.py open [REPLAY.mcpr]   rebuild the newest (or that) recording: a replay server + a review client
    tools/review.py status               where the review is (game time, playing or paused, where the agent stands)
    tools/review.py goto GAMETIME        run forward to that game time and pause there (forward only; reopen to go back)
    tools/review.py agent                what the agent could perceive at this moment (its own /observe, recomputed)
    tools/review.py god                  what was really around it (everything its client had been sent)
    tools/review.py log [FROM] [TO]      the flight log in that game-time range: its notes, actions, what it heard
    tools/review.py deaths               every death in the session, with its game time
    tools/review.py report [SECONDS]     for each death: the last SECONDS (default 60), agent's view vs the truth
    tools/review.py close

The recording (ServerReplay) holds every packet the agent's client received; the flight log
(run/client/flightlog/) holds what never left the client: its exact pose, every observation and act, its notes.
The review client is fed the packets, stands exactly where the agent stood and looks where it looked, and runs the
same perception code — so "what could it see" is recomputed, not remembered.
"""
import glob, json, os, shutil, subprocess, sys, threading, time, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import ports as _ports
_P = _ports.get(); CTRL, API, DEBUG, RPORT = _P["review_ctrl"], _P["review_api"], _P["review_debug"], _P["review_game"]
STATE = os.path.join(ROOT, "run", "review.json")


def http(port, path, data=None, timeout=60):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.load(r)


def recordings():
    return sorted(glob.glob(os.path.join(ROOT, "run", "server", "recordings", "players", "*", "*.mcpr")), key=os.path.getmtime)


def flightlog_for(replay):
    """the flight log whose session started closest to the recording's start"""
    with zipfile.ZipFile(replay) as z:
        start = json.loads(z.read("arcade_replay_meta.json")).get("epoch_time_ms", 0)
    best = None
    for f in glob.glob(os.path.join(ROOT, "run", "client", "flightlog", "*.jsonl")):
        try: w = json.loads(open(f).readline())["w"]
        except Exception: continue
        if best is None or abs(w - start) < best[0]: best = (abs(w - start), f)
    return best[1] if best and best[0] < 120_000 else None


def read_log(f):
    return [json.loads(l) for l in open(f)] if f else []


# ------------------------------------------------------------------ the daemon (one per review)
def daemon(replay, flog):
    import http.server, replay_server
    meta, frames = replay_server.load(replay)
    player, srv = replay_server.serve_once(frames, RPORT, speed=1.0)
    player.paused = False
    gd = os.path.join(ROOT, "run", "client-Review"); os.makedirs(gd, exist_ok=True)
    mem = flog[:-len(".jsonl")] + ".memory.bin" if flog else None
    if mem and os.path.exists(mem): shutil.copy(mem, os.path.join(gd, "honest_memory.bin"))
    elif os.path.exists(os.path.join(gd, "honest_memory.bin")): os.remove(os.path.join(gd, "honest_memory.bin"))
    env = dict(os.environ, HONEST_NAME="Review", HONEST_PORT=str(API), HONEST_REVIEW="1", HONEST_SERVER=f"127.0.0.1:{RPORT}",
               HONEST_REVIEW_FLIGHTLOG=flog or "")
    client = subprocess.Popen([sys.executable, os.path.join(ROOT, "launcher", "mc.py"), "client"], env=env,
                              stdout=open(os.path.join(ROOT, "run", "logs", "review.out"), "w"), stderr=subprocess.STDOUT)

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            q = dict(p.split("=", 1) for p in self.path.split("?", 1)[1].split("&")) if "?" in self.path else {}
            cmd = self.path.split("?")[0].strip("/")
            r = {}
            if cmd == "pause": player.paused = True
            elif cmd == "play": player.speed = float(q.get("speed", 1)); player.paused = False
            elif cmd == "close":
                client.terminate(); srv.close(); r["closed"] = True
                threading.Timer(0.5, lambda: os._exit(0)).start()
            r.update(paused=player.paused, speed=player.speed, sent=player.pos, of=len(frames), finished=player.done.is_set())
            b = json.dumps(r).encode(); self.send_response(200); self.end_headers(); self.wfile.write(b)
    http.server.ThreadingHTTPServer(("127.0.0.1", CTRL), H).serve_forever()


# ------------------------------------------------------------------ commands
def status():
    s = http(CTRL, "/status")
    try:
        c = http(API, "/status"); o = http(API, "/observe")
        s.update(game_time=c.get("game_time"), pos=o.get("self", {}).get("pos"), health=o.get("self", {}).get("health"))
    except Exception as e: s["client"] = f"not ready ({e.__class__.__name__})"
    return s


def wait_ready(timeout=240):
    t = time.time()
    while time.time() - t < timeout:
        try:
            if "self" in http(API, "/observe", timeout=5) and http(API, "/status").get("game_time") is not None: return True
        except Exception: pass
        time.sleep(2)
    return False


def goto(g):
    """run forward until the client's game time reaches g: fast while far, then at normal speed"""
    while True:
        st = http(CTRL, "/status")
        now = http(API, "/status").get("game_time", 0)
        if now >= g: http(CTRL, "/pause"); return now
        if st["finished"]: return now
        http(CTRL, f"/play?speed={8 if g - now > 200 else 1}")
        time.sleep(0.05)


def fmt_agent(o):
    """the agent's view, the way tools/hc prints it"""
    import importlib.util
    from importlib.machinery import SourceFileLoader
    loader = SourceFileLoader("hc", os.path.join(ROOT, "tools", "hc"))
    spec = importlib.util.spec_from_loader("hc", loader); hc = importlib.util.module_from_spec(spec)
    sys.argv = ["hc"]; spec.loader.exec_module(hc)
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf): hc.fmt(o)
    return buf.getvalue()


def god_summary(n=15):
    """everything the agent's client had been sent about its surroundings, nearest first, flagged when it could see it"""
    r = http(DEBUG, "/debug/raw")
    seen = {e.get("id") for e in http(API, "/observe").get("entities", [])}
    ents = sorted(r.get("all_entities_client_knows", []), key=lambda e: e.get("dist", 1e9))
    lines = [f"{'SEEN  ' if e.get('id') in seen else 'unseen'} {e['type']}#{e.get('id')}: {e.get('dist')} m, bearing {e.get('bearing')}°, "
             f"elevation {e.get('elevation')}°, at {e.get('approx_pos')}, HP {e.get('TRUE_health')}" for e in ents[:n]]
    more = f"\n  … and {len(ents) - n} more further away" if len(ents) > n else ""
    return "really around me (everything my client had been sent), nearest first:\n  " + ("\n  ".join(lines) if lines else "(nothing)") + more


def deaths(flog):
    out = []
    for r in read_log(flog):
        if r["t"] == "event" and r.get("kind") == "game_message" and r.get("text", "").startswith(("Agent ", "Review ")):
            if any(w in r["text"] for w in (" was ", " died", " fell", " drowned", " burned", " blew", " starved", " suffocated", " hit the ground", " went up", " tried to swim", " experienced kinetic")):
                out.append((r["g"], r["text"]))
    return out


def log_range(flog, a, b):
    lines = []
    for r in read_log(flog):
        if not (a <= r["g"] <= b): continue
        if r["t"] == "event":
            k = r.get("kind")
            if k == "note": lines.append(f"  g{r['g']}  » {r['text']}")
            elif k == "sound": lines.append(f"  g{r['g']}  heard: {r.get('subtitle')} ({r.get('direction', '?')}, {r.get('loudness', '')})")
            else: lines.append(f"  g{r['g']}  [{k}] {r.get('text', '')}")
        elif r["t"] == "act":
            q = {k: v for k, v in r["req"].items() if k not in ("cursor", "resume", "wait")}
            res = r.get("res", {})
            lines.append(f"  g{r['g']}  did: {json.dumps(q)} -> {res.get('result')}" + (f"  (woke: {res['woke']})" if res.get("woke") else ""))
    return "\n".join(lines) or "  (nothing logged)"


def main(a):
    cmd = a[0] if a else "help"
    st = json.load(open(STATE)) if os.path.exists(STATE) else {}
    if cmd == "open":
        replay = a[1] if len(a) > 1 else (recordings() or [None])[-1]
        if not replay: sys.exit("no recordings yet")
        flog = flightlog_for(replay)
        json.dump({"replay": replay, "flightlog": flog}, open(STATE, "w"))
        subprocess.Popen([sys.executable, __file__, "_daemon", replay, flog or ""], start_new_session=True,
                         stdout=open(os.path.join(ROOT, "run", "logs", "review-daemon.out"), "w"), stderr=subprocess.STDOUT)
        print(f"replay: {os.path.relpath(replay, ROOT)}\nflight log: {os.path.relpath(flog, ROOT) if flog else '(none found: viewing from the agent body in the replay)'}")
        print("starting the review client ..." , flush=True)
        print("ready" if wait_ready() else "the review client didn't come up: see run/logs/review.out")
        http(CTRL, "/pause"); print(json.dumps(status()))
    elif cmd == "_daemon": daemon(a[1], a[2] or None)
    elif cmd == "status": print(json.dumps(status()))
    elif cmd == "goto": print("now at game time", goto(int(a[1])))
    elif cmd == "agent": print(fmt_agent(http(API, "/observe")))
    elif cmd == "god": print(god_summary())
    elif cmd == "log": print(log_range(st.get("flightlog"), int(a[1]) if len(a) > 1 else 0, int(a[2]) if len(a) > 2 else 1 << 62))
    elif cmd == "deaths":
        for g, t in deaths(st.get("flightlog")): print(f"g{g}  {t}")
    elif cmd == "report":
        secs = int(a[1]) if len(a) > 1 else 60
        for g, t in deaths(st.get("flightlog")):
            print(f"\n## {t}  (game time {g})\n\n### what it did, said and heard in the last {secs} s\n{log_range(st.get('flightlog'), g - secs * 20, g)}")
            at = max(g - 40, http(API, "/status").get("game_time", 0))
            if at < g:
                goto(at)
                print(f"\n### 2 s before: what it could perceive\n{fmt_agent(http(API, '/observe'))}\n### and what was really there\n{god_summary()}")
    elif cmd == "close":
        try: http(CTRL, "/close")
        except Exception: pass
        print("closed")
    else: print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
