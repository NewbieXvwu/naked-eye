#!/bin/sh
# Freeze and save the world (like pressing ESC before walking away), then stop the client and the server.
cd "$(dirname "$0")"
python3 tools/rcon.py "tick freeze" "save-all" >/dev/null 2>&1
for f in run/client-*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f" && echo "$(basename "$f" .pid | sed 's/client-//') stopped"; done
# Every agent that leaves has its recording packed (its .tmp folder turns into the .mcpr) — on a background thread
# that dies with the server. So once the agents are gone, wait for that to finish before stopping the server.
wait_recordings() {
  i=0; while [ -n "$(find run/server/recordings -name '*.tmp' -maxdepth 3 2>/dev/null | head -1)" ] && [ $i -lt 180 ]; do
    [ $i = 0 ] && echo "saving the recordings ..."; sleep 1; i=$((i+1)); done
}
for p in client server; do
  [ $p = server ] && [ -f run/server.pid ] && { sleep 1; wait_recordings; }
  if [ -f run/$p.pid ]; then
    if [ $p = server ] && python3 tools/rcon.py stop >/dev/null 2>&1; then
      # let it finish by itself (saving the world). tools/replay_repair.py mends any recording left half-saved.
      i=0; while kill -0 "$(cat run/$p.pid)" 2>/dev/null && [ $i -lt 180 ]; do sleep 1; i=$((i+1)); done
    fi
    kill "$(cat run/$p.pid)" 2>/dev/null; rm -f run/$p.pid; echo "$p stopped"
  fi
done
