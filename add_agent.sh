#!/bin/sh
# Put another agent into this world (several agents, one world): ./add_agent.sh NAME
# Then drive it with  HC_AGENT=NAME tools/hc …  (each agent gets its own client, API port, flight log and replay).
# One clock for all: the world runs only while every agent is acting, and stands still while any of them thinks.
# World rules (thinking limit, whether talking takes time): run/server/config/naked-eye/rules.properties. Teams: teams.txt.
cd "$(dirname "$0")"
NAME="$1"; [ -z "$NAME" ] && { echo "usage: $0 NAME"; exit 2; }
[ -f run/server.pid ] && kill -0 "$(cat run/server.pid)" 2>/dev/null || { echo "start the world first: ./start.sh"; exit 1; }
A=run/server/config/naked-eye/agents.txt
grep -qx "$NAME" "$A" 2>/dev/null || echo "$NAME" >> "$A"     # the server re-reads this list: NAME is an agent, not a watcher
PORT=$(python3 tools/ports.py agent "$NAME")
HONEST_NAME="$NAME" HONEST_PORT="$PORT" nohup python3 launcher/mc.py client > "run/logs/client-$NAME.out" 2>&1 &
echo $! > "run/client-$NAME.pid"
echo "$NAME starting (API on $PORT) ..."
i=0; until python3 -c "import urllib.request,json,sys; o=json.load(urllib.request.urlopen('http://127.0.0.1:$PORT/observe', timeout=5)); sys.exit(0 if 'self' in o and 'open_screen' not in o['self'] else 1)" 2>/dev/null; do
  i=$((i+1)); sleep 2; kill -0 "$(cat run/client-$NAME.pid)" 2>/dev/null || { echo "client died:"; tail -20 "run/logs/client-$NAME.out"; exit 1; }
  [ $i -gt 200 ] && { echo "timed out"; exit 1; }; done
python3 tools/rcon.py "replay start players $NAME" >/dev/null 2>&1   # (the recorder's list was fixed when the server started)
echo "$NAME is in the world: HC_AGENT=$NAME tools/hc obs"
