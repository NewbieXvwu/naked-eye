# hole.sh — panic shelter: dig 3 straight down where I stand, get in, cap it
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"
read X Y Z <<< "$(curl -s localhost:7070/observe | python3 -c "import json,sys,math; p=json.load(sys.stdin)['self']['pos']; print(math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))")"
for d in 1 2 3; do curl -s -X POST localhost:7070/act -d "{\"type\":\"dig\",\"x\":$X,\"y\":$((Y-d)),\"z\":$Z,\"resume\":true,\"wait\":200}" >/dev/null; done
curl -s -X POST localhost:7070/act -d "{\"type\":\"walk\",\"x\":$X,\"y\":$((Y-3)),\"z\":$Z,\"risk\":9,\"resume\":true,\"wait\":60}" >/dev/null
for i in 1 2 3; do
  NY=$(curl -s localhost:7070/observe | python3 -c "import json,sys,math; p=json.load(sys.stdin)['self']['pos']; print(math.floor(p[1]))")
  [ "$NY" -le $((Y-2)) ] && break
  curl -s -X POST localhost:7070/act -d "{\"type\":\"look_at\",\"x\":$X.5,\"y\":$((Y-3)),\"z\":$Z.5,\"resume\":true,\"wait\":3}" >/dev/null
  curl -s -X POST localhost:7070/act -d '{"type":"hold","keys":["forward"],"ticks":3,"resume":true}' >/dev/null; sleep 0.5
done
B=$(curl -s localhost:7070/observe | python3 -c "
import json,sys; inv=json.load(sys.stdin)['self']['inventory']
for want in ('dirt','cobblestone','cobbled_deepslate','oak_planks','birch_planks','jungle_planks','gravel','sand','stone','andesite','granite','diorite','tuff'):
    if any(i['item']==want for i in inv): print(want); break")
NY=$(curl -s localhost:7070/observe | python3 -c "import json,sys,math; print(math.floor(json.load(sys.stdin)['self']['pos'][1]))")
curl -s -X POST localhost:7070/act -d "{\"type\":\"place\",\"x\":$X,\"y\":$((NY+2)),\"z\":$Z,\"item\":\"$B\",\"resume\":true,\"wait\":60}" | python3 -c "import json,sys; print(json.load(sys.stdin)['observation']['control']['last_result'])"
hc sonar | sed -n 10p
