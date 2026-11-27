#!/bin/bash
export HC_SCRIPT=1  # my scripts must stop at a pause, not click through it
# Fresh life -> stone tools. The agent's own routine, written after dying five times; only uses hc commands.
# logs -> planks/sticks/table -> wooden pickaxe -> a few stair steps for cobble -> stone pickaxes + sword + furnace
set -u
cd "$(dirname "$0")/.."
say() { echo "[bootstrap] $*"; }
here() { hc obs | grep -oE "^at \[[^]]*" | tr -d 'at[,' ; }
place_near() { # item: try the 4 cells around my feet, then around my head
  read x y z <<< "$(here)"; x=${x%.*}; y=${y%.*}; z=${z%.*}
  for c in "$((x+1)) $y $z" "$((x-1)) $y $z" "$x $y $((z+1))" "$x $y $((z-1))" "$((x+1)) $((y+1)) $z" "$((x-1)) $((y+1)) $z"; do
    r=$(hc place $c $1 | grep -oE 'last: .*'); case "$r" in *placed*) echo "$c"; return 0;; esac
  done
  # boxed in (in a tunnel): let the client pick any free cell next to me
  r=$(hc place $1 | grep -oE 'last: .*'); case "$r" in *placed*) echo "$r" | grep -oE 'at -?[0-9]+, -?[0-9]+, -?[0-9]+' | tr -d 'at,' | xargs; return 0;; esac
  return 1; }

python3 agent/gather.py _log _log 6 >/dev/null 2>&1; say "$(hc obs | grep -oE '[a-z_]*_logx[0-9]+' | head -2 | tr '\n' ' ')"
# every kind of log I have -> planks (crafting more than I have cancels the whole craft)
for lc in $(hc obs | grep -oE '[a-z_]*_logx[0-9]+'); do hc craft ${lc%_logx*}_planks ${lc##*x} >/dev/null; done
hc craft crafting_table 1 >/dev/null; hc craft stick 3 >/dev/null
T=$(place_near crafting_table) || { say "couldn't place a table"; exit 1; }
hc open $T >/dev/null; hc craft wooden_pickaxe 1 >/dev/null; hc close >/dev/null; hc dig $T >/dev/null; sleep 1
s=$(curl -s localhost:7070/observe | python3 -c "import json,sys; print([i['slot'] for i in json.load(sys.stdin)['self']['inventory'] if i['item']=='wooden_pickaxe'][0])")
[ "$s" -ge 9 ] && hc tohotbar $s 2 >/dev/null
say "wooden pickaxe; digging stairs for stone"
for dir in south east north west; do
  hc slot 2 >/dev/null; hc stairs $dir 14 lava coal_ore,iron_ore >/dev/null; hc watch 300 >/dev/null
  n=$(hc obs | grep -oE 'cobblestonex[0-9]+' | head -1 | tr -dc 0-9); [ "${n:-0}" -ge 20 ] && break
done
T=$(place_near crafting_table) || { say "couldn't place a table"; exit 1; }
hc open $T >/dev/null; hc craft stone_pickaxe 3 >/dev/null; hc craft stone_sword 1 >/dev/null; hc craft furnace 1 >/dev/null
hc close >/dev/null; hc dig $T >/dev/null; sleep 1
say "$(hc obs | grep -oE 'inventory: .*' | cut -c1-300)"
