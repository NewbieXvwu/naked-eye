#!/bin/bash
export HC_SCRIPT=1  # my scripts must stop at a pause, not click through it
# branch-mine: tunnel in chunks, a torch behind each chunk; stop on anything that isn't "done"
DIR=${1:-east}; CHUNKS=${2:-6}; LEN=${3:-12}; UNTIL=${4:-diamond,lava,sculk}; GRAB=${5:-coal_ore,iron_ore,gold_ore,redstone,lapis}
for i in $(seq 1 $CHUNKS); do
  hc slot ${SLOT:-4} >/dev/null
  r=$(hc tunnel $DIR $LEN $UNTIL $GRAB; hc watch 300)
  last=$(echo "$r" | grep -oE "last: .*" | tail -1)
  echo "chunk $i: $last"
  case "$last" in *"done, $LEN steps"*) ;; *) echo "$r" | grep -E "^HP|^at|blocks in|woke|#" | cut -c1-400; exit 0;; esac
  pos=$(hc obs | grep -oE "^at \[[^]]*" | tr -d 'at[,' )
  read x y z <<< "$pos"; x=${x%.*}; z=${z%.*}; y=${y%.*}
  hc place $x $((y+1)) $z torch >/dev/null  # torch can't go in my own cell; try the wall-side cell behind
  case $DIR in east) bx=$((x-1)); bz=$z;; west) bx=$((x+1)); bz=$z;; south) bx=$x; bz=$((z-1));; north) bx=$x; bz=$((z+1));; esac
  echo "  torch: $(hc place $bx $((y+1)) $bz torch | grep -oE 'last: .*' | sed 's/.*-> //')"
done
