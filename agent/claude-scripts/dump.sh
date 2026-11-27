# dump.sh YAW — face YAW (back down the tunnel), throw junk that way; keep 1 stack of cobble + 1 of deepslate as building blocks
cd "$(dirname "$0")/../.."; PATH="$PWD/tools:$PATH"; export HC_SCRIPT=1
curl -s -X POST localhost:7070/act -d "{\"type\":\"look\",\"yaw\":$1,\"pitch\":-5,\"wait\":5}" >/dev/null
slots=$(curl -s localhost:7070/observe | python3 -c "
import json,sys
inv=json.load(sys.stdin)['self']['inventory']
junk={'granite','diorite','andesite','tuff','gravel','dirt','leaf_litter','wooden_pickaxe','wooden_sword','redstone','calcite','smooth_basalt','rotten_flesh','raw_copper','copper_ore','cobbled_deepslate','cobblestone','stone_sword','iron_sword','stone_axe','wooden_axe','sand','clay_ball','bone'}
keep={'cobblestone':1,'cobbled_deepslate':1,'stone_sword':0}
out=[]; seen={}
for i in sorted(inv,key=lambda i:-i['count']):
    n=i['item']
    if n in junk:
        seen[n]=seen.get(n,0)+1
        if seen[n]>keep.get(n,0): out.append(str(i['slot']))
    elif n=='stone_pickaxe' and i.get('durability',100)<=5: out.append(str(i['slot']))
print(','.join(out))")
[ -n "$slots" ] && hc drop $slots >/dev/null
n=$(echo "$slots" | tr ',' '\n' | grep -c .); sleep $(( n / 3 + 1 ))
echo "dumped $n stacks"
