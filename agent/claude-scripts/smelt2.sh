# smelt2.sh ITEM FUEL — place a furnace next to me, smelt the whole ITEM stack with FUEL, collect output, pick the furnace back up
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"; export HC_SCRIPT=1; ITEM=$1; FUEL=$2
hc close >/dev/null 2>&1
r=$(hc place furnace | grep -oE "last: .*"); F=$(echo "$r" | grep -oE "at -?[0-9]+, -?[0-9]+, -?[0-9]+" | tail -1 | sed 's/at //; s/,//g')
[ -z "$F" ] && { echo "no furnace spot: $r"; exit 1; }
hc open $F >/dev/null
slot() { hc obs | grep -oE "\[[0-9]+\]$1x[0-9]+" | head -1 | grep -oE "^\[[0-9]+" | tr -d '['; }
cnt()  { hc obs | grep -oE "\[[0-9]+\]$1x[0-9]+" | head -1 | grep -oE "x[0-9]+$" | tr -d x; }
S=$(slot $ITEM); N=$(cnt $ITEM); [ -z "$S" ] && { echo "no $ITEM"; hc close >/dev/null; exit 1; }
hc click $S 0 pickup >/dev/null; hc click 0 0 pickup >/dev/null            # whole stack into the input
case $FUEL in coal|charcoal) need=$(( (N + 7) / 8 ));; *_planks) need=$(( (N * 2 + 2) / 3 ));; *_log) need=$(( (N * 2 + 2) / 3 ));; *) need=$N;; esac
C=$(slot $FUEL); [ -z "$C" ] && { echo "no $FUEL"; hc close >/dev/null; exit 1; }
hc click $C 0 pickup >/dev/null; for i in $(seq 1 $need); do hc click 1 1 pickup >/dev/null; done; hc click $C 0 pickup >/dev/null
echo "smelting $N $ITEM with $need $FUEL at $F"
sleep $(( N * 10 + 2 ))
hc click 2 0 quick_move >/dev/null; hc click 1 0 quick_move >/dev/null; hc close >/dev/null
hc dig $F >/dev/null; sleep 0.6; hc go $F >/dev/null 2>&1; sleep 0.6
hc obs | grep -oE "inventory: .*" | tr ',' '\n' | grep -iE "ingot|cooked|charcoal|furnace|glass|dried" | tr '\n' ' '; echo
