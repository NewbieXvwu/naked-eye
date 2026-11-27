#!/bin/sh
# Freeze and save the world (like pressing ESC before walking away), then stop the client and the server.
cd "$(dirname "$0")"
python3 tools/rcon.py "tick freeze" "save-all" >/dev/null 2>&1
for f in run/client-*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f" && echo "$(basename "$f" .pid | sed 's/client-//') stopped"; done
for p in client server; do
  if [ -f run/$p.pid ]; then
    [ $p = server ] && python3 tools/rcon.py stop >/dev/null 2>&1 && sleep 5
    kill "$(cat run/$p.pid)" 2>/dev/null; rm -f run/$p.pid; echo "$p stopped"
  fi
done
