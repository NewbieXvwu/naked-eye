#!/bin/sh
# Show or hide the game window. The agent keeps playing either way; hidden = nothing is drawn (saves power).
#   ./window.sh show | hide | (no argument: is it shown?)
# Closing the window (red button / Cmd+Q) also just hides it. To really stop the game: ./stop.sh
cd "$(dirname "$0")"
case "$1" in show) q="?show=1";; hide) q="?show=0";; "") q="";; *) echo "usage: $0 [show|hide]"; exit 2;; esac
exec python3 -c "import json,sys,urllib.request; print(json.load(urllib.request.urlopen('http://127.0.0.1:%s/window$q' % '$(python3 tools/ports.py api)', timeout=10))['result'])"
