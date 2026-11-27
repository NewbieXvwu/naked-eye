#!/usr/bin/env python3
"""Smelt something in a furnace I can see, the way a player clicks it.

Why this exists: claude-scripts/smelt2.sh clicked the *inventory* slot numbers from /observe on the
furnace *menu*, so the clicks landed on the wrong stacks (2026-11-27: raw iron went in, nothing came
out). The furnace menu is: 0 = input, 1 = fuel, 2 = output, then the player's main inventory starts at
menu slot 3 and the hotbar at menu slot 30. So obs slot n -> menu slot (n + 30 if n <= 8 else n - 6).

usage: agent/smelt.py X Y Z ITEM [COUNT] [--fuel ITEM] [--furnace-x X --furnace-y Y --furnace-z Z]
       exit 0 = smelted and collected, 1 = failed, 2 = refused
"""
import json
import sys
import urllib.request

API = "http://127.0.0.1:7004"


def call(path, data=None, timeout=180):
    req = urllib.request.Request(API + path, data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def obs():
    return call("/observe")


def act(q):
    return call("/act", q)


def click(slot, button=0, mode="pickup"):
    return act({"type": "slot_click", "slot": slot, "button": button, "mode": mode})


def menu_slot(obs_slot):
    """obs inventory slot -> furnace menu slot (see the module docstring)"""
    return obs_slot + 30 if obs_slot <= 8 else obs_slot - 6


def find(o, item):
    for i in o["self"].get("inventory", []):
        if i["item"] == item:
            return i
    return None


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    fuel_name = "oak_planks"
    if "--fuel" in argv:
        fuel_name = argv[argv.index("--fuel") + 1]
    if len(args) < 4:
        print(__doc__)
        return 2
    fx, fy, fz = int(args[0]), int(args[1]), int(args[2])
    item = args[3]
    count = int(args[4]) if len(args) > 4 else 1

    o = obs()
    if not find(o, item):
        print(f"no {item} in my inventory")
        return 2
    r = act({"type": "interact", "x": fx, "y": fy, "z": fz, "wait": 20})
    o = obs()
    c = o["self"].get("container") or {}
    if not c.get("slots"):
        print("did not open a furnace there:", (r.get("result") or r.get("observation", {}).get("control", {}).get("last_result")))
        return 1

    have = find(obs(), item)
    fuel = find(obs(), fuel_name)
    if not fuel:
        print(f"no fuel ({fuel_name})")
        return 2
    in_menu = [s for s in c["slots"] if s.get("item") == item]
    fuel_in_menu = [s for s in c["slots"] if s.get("item") == fuel_name]
    if not in_menu or not fuel_in_menu:
        print("could not find the stacks on the furnace menu:", [s.get("item") for s in c["slots"]])
        return 1
    # input: put the whole stack in
    click(in_menu[0]["slot"])
    click(0)
    # fuel: one item per `count` smells, then put the rest back
    click(fuel_in_menu[0]["slot"])
    for _ in range(max(1, count)):
        click(1, 1)
    click(fuel_in_menu[0]["slot"])
    print(f"loaded {item} x{have['count']} with {fuel_name}; waiting")

    for _ in range(30):
        act({"type": "wait", "wait": 100, "resume": True})
        c = obs()["self"].get("container") or {}
        out = [s for s in c.get("slots", []) if s["slot"] == 2 and s.get("item")]
        if out and out[0].get("count", 0) >= count:
            break
    c = obs()["self"].get("container") or {}
    out = [s for s in c.get("slots", []) if s["slot"] == 2 and s.get("item")]
    if not out:
        print("nothing came out of the furnace")
        act({"type": "close_screen"})
        return 1
    print(f"output: {out[0]['item']} x{out[0]['count']}")
    click(2, 0, "quick_move")
    act({"type": "close_screen", "wait": 10})
    o = obs()
    print("now:", ", ".join(f"{i['item']} x{i['count']}" for i in o["self"].get("inventory", []) if i["item"] in (out[0]["item"], item)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
