#!/bin/bash
# Build the two portable packages (run on Linux x86_64 after ./setup.sh and one ./start.sh, so the caches are full;
# commit first: the packages hold what git tracks):
#   dist/naked-eye-online.tar.gz          any macOS / Linux x86_64 with internet: ./setup.sh downloads the rest
#   dist/naked-eye-offline-linux-x64.tar.gz  no network needed: Java runtime, game files, server and a fallback Mesa inside
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=dist; rm -rf "$OUT"; mkdir -p "$OUT/on/naked-eye"
S="$OUT/on/naked-eye"

# ---- common part: exactly what git tracks (minus the Baritone submodule: its source is in the fork, and as a patch)
git ls-files -z | grep -zv '^src/baritone' | tar --null -T - -cf - | tar -C "$S" -xf -
tar -C "$OUT/on" -czf "$OUT/naked-eye-online.tar.gz" naked-eye

# ---- offline: + game files, server first-run files, a Java runtime, Mesa for headless GL
F="$OUT/off/naked-eye"; mkdir -p "$OUT/off"; cp -a "$S" "$F"
mkdir -p "$F/.cache"
cp -a .cache/mc .cache/mods .cache/server "$F/.cache/"
mkdir -p "$F/.cache/server/firstrun"
# (what Fabric's server launcher fetches on its first start; versions/ and .fabric/processedMods it rebuilds offline)
cp -a run/server/libraries "$F/.cache/server/firstrun/"
mkdir -p "$F/.cache/server/firstrun/.fabric/server"; cp run/server/.fabric/server/fabric-loader-server-*.jar "$F/.cache/server/firstrun/.fabric/server/"
# leave out music, records and non-English languages; mute those categories so the game never asks for them
TRIM="minecraft/sounds/music/ minecraft/sounds/records/ minecraft/lang/"
python3 - "$F/.cache/mc/assets" $TRIM <<'PY'
import json, os, sys
root, pre = sys.argv[1], tuple(sys.argv[2:])
objs = json.load(open(os.path.join(root, "indexes", "34.json")))["objects"]
keep = {o["hash"] for k, o in objs.items() if not k.startswith(pre)}
gone = {o["hash"] for k, o in objs.items() if k.startswith(pre)} - keep
for h in gone: os.remove(os.path.join(root, "objects", h[:2], h))
open(os.path.join(root, "TRIMMED"), "w").write("\n".join(pre) + "\n")
print(f"assets: left out {len(gone)} files")
PY
sed -i 's/^soundCategory_music:.*/soundCategory_music:0.0/; s/^soundCategory_record:.*/soundCategory_record:0.0/' "$F/config/client/options.txt"
touch "$F/.offline"

# Java: a trimmed runtime (no compiler / dev tools) made with jlink from the downloaded JDK
JDK=.cache/jdk
MODS=$("$JDK/bin/java" --list-modules | sed 's/@.*//' | grep -vE '^jdk\.(compiler|javadoc|jshell|jdeps|jlink|jpackage|hotspot\.agent|jdi|jdwp\.agent|jconsole|jstatd|jartool|editpad|internal\.(ed|jvmstat|le|opt)|incubator)' | paste -sd, -)
"$JDK/bin/jlink" --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output "$F/runtime/linux-x86_64"

# Mesa (EGL + llvmpipe) and its dependency closure, minus glibc itself — used only when the system has no libEGL
M="$F/runtime/mesa-linux-x86_64"; mkdir -p "$M/lib/dri"
L=/usr/lib/x86_64-linux-gnu
roots="$L/libEGL.so.1 $L/libEGL_mesa.so.0 $L/libGL.so.1 $L/libGLX.so.0 $L/libGLX_mesa.so.0 $L/libgbm.so.1 $(ls $L/libgallium-*.so)"
{ for r in $roots; do echo "$r"; ldd "$r" | awk '/=>/{print $3}'; done; } | sort -u \
  | grep -vE '/(libc|libm|libdl|libpthread|librt|libresolv|ld-linux[^/]*)\.so' | while read -r f; do
    n=$(basename "$f"); [ "$n" = libz.so.1 ] && f=$L/libz.so.1   # the stock zlib, not this box's zlib-ng
    cp -L "$f" "$M/lib/$n"; done
cp -L "$L/dri/libdril_dri.so" "$M/lib/dri/"; for d in swrast kms_swrast; do ln -sf libdril_dri.so "$M/lib/dri/${d}_dri.so"; done
printf '{ "file_format_version" : "1.0.0", "ICD" : { "library_path" : "libEGL_mesa.so.0" } }\n' > "$M/50_mesa.json"
echo "Mesa built against glibc $(ldd --version | head -1 | grep -oE '[0-9.]+$')" > "$M/README"

tar -C "$OUT/off" -czf "$OUT/naked-eye-offline-linux-x64.tar.gz" naked-eye
rm -rf "$OUT/on" "$OUT/off"
ls -la "$OUT"
