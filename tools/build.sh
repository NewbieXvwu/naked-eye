#!/bin/sh
# Rebuild the two mods from src/ (needs Java 25 and internet for Gradle) and put the jars into mods/.
set -e
cd "$(dirname "$0")/.."
# the server mod first: the client compiles against its jar in mods/ (the clock and note payloads)
(cd src/pause-mod && ./gradlew build --no-daemon -q) && cp src/pause-mod/build/libs/honest-pause-*.jar mods/
(cd src/mod && ./gradlew build --no-daemon -q) && cp src/mod/build/libs/honest-client-*.jar mods/
ls -la mods/
