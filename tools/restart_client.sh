#!/bin/sh
# Rebuild the client mod and restart only the client (the server and the frozen world stay up): for quick fixes mid-game.
set -e; cd "$(dirname "$0")/.."
[ -x "$HOME/.cache/naked-eye/jdk/bin/java" ] && export JAVA_HOME="$HOME/.cache/naked-eye/jdk"   # (Loom needs Java 25: a system JAVA_HOME may be older)
build() { (cd src/mod && ./gradlew build --no-daemon -q > /tmp/naked-eye-build.log 2>&1); }
# (the Fabric maven sometimes fails to resolve Loom: try again)
if ! { build || build || build; }; then
  grep -v "Picked up" /tmp/naked-eye-build.log | grep -A5 "error:\|wrong" | head -30; echo "BUILD FAILED: client not restarted"; exit 1; fi
cp src/mod/build/libs/honest-client-*.jar mods/
[ -f run/client.pid ] && kill "$(cat run/client.pid)" 2>/dev/null; sleep 3; rm -f run/client.pid
./start.sh | tail -1
python3 tools/rcon.py "tick query" | grep -o "Target tick rate: [0-9.]*"
