# portal.sh X Y Z — build a 4x5 nether portal frame in the x-plane at z=Z, inner bottom at (X+1..X+2, Y+1); Y = the frame's bottom row
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"; export HC_SCRIPT=1
X=$1; Y=$2; Z=$3
pl() { r=$(hc place $1 $2 $3 $4 2>&1 | grep -oE "last: .*"); echo "  $1 $2 $3 $4: ${r:0:80}"; }
# corners (any block) give the columns and the top row something to stand on
pl $X $Y $Z cobblestone; pl $((X+3)) $Y $Z cobblestone
pl $((X+1)) $Y $Z obsidian; pl $((X+2)) $Y $Z obsidian
for dy in 1 2 3; do pl $X $((Y+dy)) $Z obsidian; pl $((X+3)) $((Y+dy)) $Z obsidian; done
pl $X $((Y+4)) $Z cobblestone; pl $((X+3)) $((Y+4)) $Z cobblestone
pl $((X+1)) $((Y+4)) $Z obsidian; pl $((X+2)) $((Y+4)) $Z obsidian
