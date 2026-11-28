#!/bin/sh
# Cheat mode: from bare hands to iron gear, as one chain (each step stops the chain on an alarm or low HP).
#   wood -> wooden pickaxe -> stone tools + furnace -> iron + coal + gold -> smelt -> iron pickaxe, sword, helmet,
#   chestplate, leggings, shield, bucket, golden boots (piglins), flint and steel if there's flint
# usage: agent/regear.sh   (run it by day)
cd "$(dirname "$0")/.."
hc=tools/hc
step() {   # step "baritone args" — run a Baritone command, retry twice after an alarm, stop the chain if HP < 10
  for i in 1 2 3; do
    out=$($hc b "$@"); echo "$out" | grep -E "^HP|last:|woke" | sed 's/^/  /'
    hp=$(echo "$out" | grep -oE "^HP [0-9]+" | grep -oE "[0-9]+")
    [ "${hp:-20}" -lt 10 ] && { echo "regear: HP $hp — stopping"; exit 1; }
    echo "$out" | grep -q "woke early" || return 0
  done
}
have() { $hc obs | sed -n 3p | grep -oE "$1x[0-9]+" | head -1 | grep -oE "[0-9]+$" || echo 0; }

echo "== wood";   step mine 10 oak_log birch_log jungle_log spruce_log acacia_log dark_oak_log cherry_log mangrove_log
for p in oak birch jungle spruce acacia dark_oak cherry mangrove; do $hc make ${p}_planks 3 >/dev/null 2>&1; done
$hc make stick 3; $hc make wooden_pickaxe
echo "== stone";  step mine 26 stone
$hc make stone_pickaxe; $hc make stone_pickaxe; $hc make stone_sword; $hc make furnace
echo "== ores";   step mine 30 iron_ore deepslate_iron_ore; step mine 12 coal_ore deepslate_coal_ore
$hc smelt raw_iron coal
$hc make iron_pickaxe                 # (before the gold: gold needs it, and the stone ones are worn out by now)
step mine 4 gold_ore deepslate_gold_ore; step mine 20 gravel
$hc smelt raw_gold coal
echo "== iron gear"
for it in iron_sword iron_helmet iron_chestplate iron_leggings shield bucket golden_boots flint_and_steel; do $hc make $it; done
# wear it (armour slots by shift-click), shield to the off hand
$hc wear iron_helmet,iron_chestplate,iron_leggings,golden_boots,shield
echo "== food"; $hc hunt pig,cow,sheep 4 | grep -E "→|woke"; for f in porkchop beef mutton chicken; do $hc smelt $f coal >/dev/null; done
$hc obs | head -3
