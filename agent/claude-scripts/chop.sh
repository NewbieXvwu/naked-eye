# chop.sh N — chop remembered/visible logs until I have N
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"; export HC_SCRIPT=1
have() { hc obs | grep -oE "_logx[0-9]+" | tr -dc '0-9\n' | awk '{s+=$1} END {print s+0}'; }
for round in 1 2 3 4 5 6; do
  [ "$(have)" -ge "$1" ] && break
  timeout 200 python3 agent/gather.py _log _log $1 >/dev/null 2>&1
  [ "$(have)" -ge "$1" ] && break
  p=$(hc recall _log 2>/dev/null | sed -n 2p | grep -oE "^ *-?[0-9]+, -?[0-9]+, -?[0-9]+" | tr -d ',')
  [ -z "$p" ] && { echo "no logs remembered"; break; }
  set -- $1 $p; hc near $2 $3 $4 >/dev/null 2>&1
  for dy in 0 1 2 3 4 5; do hc dig $2 $(( $3 + dy )) $4 >/dev/null 2>&1; done
  hc go $2 $3 $4 >/dev/null 2>&1
done
echo "logs: $(have)"
