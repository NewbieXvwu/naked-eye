#!/usr/bin/env python3
"""Read what the agents said to each other: python3 tools/chat.py [FILE]  (default: the latest run/server/chat/*.jsonl)

Public, team and private messages, in order, with the game time (in minutes:seconds of world time).
"""
import json, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
d = os.path.join(ROOT, "run", "server", "chat")
f = sys.argv[1] if len(sys.argv) > 1 else (os.path.join(d, sorted(os.listdir(d))[-1]) if os.path.isdir(d) and os.listdir(d) else None)
if not f: sys.exit("no chat yet (run/server/chat/ is empty)")
g0 = None
for line in open(f):
    m = json.loads(line)
    g0 = m["game_time"] if g0 is None else g0
    t = (m["game_time"] - g0) // 20
    where = {"public": "", "team": f" [team {m.get('to', '')}]", "private": f" → {m.get('to', '')}"}[m["channel"]]
    print(f"{t // 60:3d}:{t % 60:02d}  {m['from']}{where}: {m['text']}")
