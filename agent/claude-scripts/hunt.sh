# wander toward a waypoint, scanning for food animals; $1 $2 = target x z
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"; export HC_SCRIPT=1
FOOD='cow|pig|sheep|chicken|rabbit|mooshroom|goat'
scan() { curl -s localhost:7070/observe | python3 -c "
import json,sys,re; o=json.load(sys.stdin)
a=[e for e in o['entities'] if re.fullmatch('$FOOD', e['type'])]
a.sort(key=lambda e:e['dist'])
print(' '.join(f\"{e['type']}:{e['id']}:{e['dist']}\" for e in a))"; }
hc obs | head -1 | grep -q "PAUSED — a " && HC_SCRIPT= hc wait 1 >/dev/null
r=$(hc goxz $1 $2 2>&1 | grep -oE "last: .*|PAUSED.*" | head -1); echo "go: $r"
curl -s -X POST localhost:7070/act -d '{"type":"look_around","pitch":5,"wait":200}' >/dev/null
s=$(scan); echo "at $(hc obs | grep -oE '^at \[[^]]*\]')  food animals: ${s:-none}"
