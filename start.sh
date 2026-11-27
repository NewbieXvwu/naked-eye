#!/bin/sh
# Start the server and the client in the background; return once the agent is standing in the world.
# Each copy of the repository is its own instance with its own ports (tools/ports.py): clone it into another
# folder and run ./start.sh there to play a second, independent game on the same machine.
#   HONEST_DEBUG=0   contest mode: no experimenter API on :7071
#   HONEST_WINDOW=1  (Linux) open a real window instead of headless; macOS always opens one
cd "$(dirname "$0")"; mkdir -p run/logs
API=$(python3 tools/ports.py assign >/dev/null && python3 tools/ports.py api); RCON=$(python3 tools/ports.py rcon)
[ "$(python3 tools/ports.py slot)" != 0 ] && echo "instance slot $(python3 tools/ports.py slot): game port $(python3 tools/ports.py game), agent API $API"
alive() { [ -f "run/$1.pid" ] && kill -0 "$(cat run/$1.pid)" 2>/dev/null; }
# up = the API answers AND I am standing in the world (not still on the loading screen)
up() { python3 -c "import urllib.request,json,sys; o=json.load(urllib.request.urlopen('http://127.0.0.1:$API/observe', timeout=5)); sys.exit(0 if 'self' in o and 'open_screen' not in o['self'] else 1)" 2>/dev/null; }
if ! alive server; then
  python3 tools/replay_repair.py --quiet 2>/dev/null   # recordings left half-saved by a server that was killed: pack them again
  nohup python3 launcher/mc.py server > run/logs/server.out 2>&1 & echo $! > run/server.pid
  echo "server starting (first start generates the world: a minute or two) ..."
  # ready = RCON answers (it opens after "Done"; an old latest.log from the last run would lie)
  i=0; until python3 -c "import socket; socket.create_connection(('127.0.0.1', $RCON), 2).close()" 2>/dev/null; do
    i=$((i+1)); sleep 2; alive server || { echo "server died:"; tail -20 run/logs/server.out; exit 1; }
    [ $i -gt 300 ] && { echo "server timed out"; exit 1; }; done
  # everyone spawns on the exact same spot of the same seed (vanilla scatters a new player within 10 blocks)
  python3 tools/rcon.py "gamerule respawn_radius 0" "gamerule spectators_generate_chunks false" >/dev/null   # watchers leave the world untouched
  echo "server up"
fi
if ! alive client; then
  nohup python3 launcher/mc.py client > run/logs/client.out 2>&1 & echo $! > run/client.pid
  echo "client starting ..."
  i=0; until up && sleep 3 && up; do   # twice, 3 s apart: there is a moment after joining before the loading screen opens
    i=$((i+1)); sleep 2; alive client || { echo "client died:"; tail -30 run/logs/client.out; exit 1; }
    [ $i -gt 200 ] && { echo "client timed out"; exit 1; }; done
  echo "client up, in the world: try  tools/hc obs"
fi
