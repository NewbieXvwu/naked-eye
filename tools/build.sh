#!/bin/sh
# Rebuild the two mods from src/ (needs Java 25 and internet for Gradle) and put the jars into mods/.
set -e
cd "$(dirname "$0")/.."
for m in mod pause-mod; do (cd src/$m && ./gradlew build --no-daemon -q); done
cp src/mod/build/libs/honest-client-*.jar src/pause-mod/build/libs/honest-pause-*.jar mods/
ls -la mods/
