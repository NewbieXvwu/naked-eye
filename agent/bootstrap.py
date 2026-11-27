#!/usr/bin/env python3
"""Empty hands -> a stone pickaxe, a stone sword and some cobblestone, in one command.

Why: after a death the agent respawns bare-handed, and every rebuild cost 5-10 minutes of clicking
(logs, planks, a table, sticks, a wooden pickaxe, stone...). That is the same every time, so it is
the same script every time.

What it does (each step is skipped when it is already done):
  1. climb out of the hole a death often leaves us in
  2. chop 3 logs (walk to the trunk, dig it, walk onto the drop — a dig never picks up after itself)
  3. planks -> sticks -> a crafting table, placed the easy way (`place ITEM` picks a cell next to me)
  4. wooden pickaxe, then 6 stone -> stone pickaxe + stone sword

usage: agent/bootstrap.py [--logs N] [--stone N]
       exit 0 = has a stone pickaxe, 2 = stopped for a decision (read the last line), 1 = failed
"""
import json
import time
import math
import sys
import urllib.request

from hcapi import API  # which client? HC_AGENT (see hcapi.py)
LOGS = ("jungle_log", "oak_log", "birch_log", "spruce_log", "dark_oak_log", "acacia_log",
        "mangrove_log", "cherry_log", "pale_oak_log")
STONE = ("stone", "cobblestone", "andesite", "granite", "diorite", "tuff", "deepslate")


def call(path, data=None, timeout=600):
    req = urllib.request.Request(API + path, data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def obs():
    return call("/observe")


def act(q, timeout=600):
    """Send one action and return its last_result, waiting for the task to end.

    The /act endpoint answers {"result": "ok"} as soon as the action is queued;
    the work itself runs in the background.  Digging without waiting means the
    drop is never collected (the block is not broken yet when we walk over).
    """
    before = obs()["control"].get("last_result")
    r = call("/act", q, timeout=timeout)
    if isinstance(r, dict) and r.get("result") not in (None, "ok"):
        return r                      # refused outright: no task will start
    t0 = time.time()
    quiet_since = None
    while time.time() - t0 < timeout:
        o = obs()
        ctl = o["control"]
        if ctl.get("task") == "idle" and ctl.get("last_result") != before:
            return {"result": ctl.get("last_result") or ""}
        if ctl.get("task") == "idle":
            quiet_since = quiet_since or time.time()
            if time.time() - quiet_since > 3:
                return {"result": "refused: nothing happened (screen open?)"}
        else:
            quiet_since = None
        time.sleep(0.15)
    return {"result": "timeout"}


def feet(o):
    p = o["self"]["pos"]
    return math.floor(p[0]), math.floor(p[1]), math.floor(p[2])


def count(o, item):
    return sum(i["count"] for i in o["self"].get("inventory", []) if i["item"] == item)


def slot_of(o, item):
    for i in o["self"].get("inventory", []):
        if i["item"] == item:
            return i["slot"]
    return None


def say(msg):
    print(msg, flush=True)


def close_screen(o):
    if o.get("self", {}).get("open_screen") or o.get("control", {}).get("open_screen"):
        act({"type": "close"})
        return True
    return False


def find(o, names, within=24.0):
    """The nearest visible block whose name is in `names`, or None."""
    best = None
    for b in o.get("blocks_in_view") or []:
        if b.get("block") in names and b.get("dist", 99) <= within:
            if best is None or b["dist"] < best["dist"]:
                best = b
    return best


def dig_and_take(x, y, z):
    """Break one block and walk onto it, which is how a player picks the drop up."""
    act({"type": "dig", "x": x, "y": y, "z": z})
    act({"type": "go", "x": x, "y": y, "z": z}, timeout=120)


def escape_the_hole(o):
    """A respawn often lands in a 2-deep hole. Dig a few steps up until the sky is visible."""
    if o["self"].get("light", 15) >= 14 and o["self"].get("on_ground", True):
        return
    for d in ("north", "east", "south", "west"):
        r = act({"type": "mine", "dir": d, "steps": 3, "up": True, "wait": 200})
        o = r.get("observation", {}) or obs()
        if o["self"].get("light", 0) >= 14:
            say(f"climbed out going {d}")
            return


def sweep_logs():
    """Look around (and up) for a trunk: a forest hides what is right in front of us."""
    for yaw in (0, 45, 90, 135, 180, 225, 270, 315):
        for pitch in (0, 20, 40):
            act({"type": "look", "yaw": yaw, "pitch": pitch}, timeout=30)
            b = find(obs(), LOGS)
            if b:
                return b
    return None


def chop_logs(want=3):
    got = 0
    for _ in range(40):
        o = obs()
        have = sum(count(o, i) for i in LOGS)
        if have >= want:
            break
        b = find(o, LOGS)
        if not b:
            b = sweep_logs()
        if not b:
            say(f"no log in sight ({have}/{want} logs)")
            return have
        x, y, z = b["nearest"]
        if b["dist"] > 3.4:
            act({"type": "go", "near": True, "x": x, "y": y, "z": z}, timeout=300)
        r = act({"type": "dig", "x": x, "y": y, "z": z})
        got = r.get("result")
        act({"type": "go", "x": x, "y": y, "z": z}, timeout=120)
        have = sum(count(obs(), i) for i in LOGS)
        say(f"  log {x},{y},{z}: {got} -> {have} log(s)")
    return sum(count(obs(), i) for i in LOGS)


def open_table(pos):
    act({"type": "interact", "x": pos[0], "y": pos[1], "z": pos[2]})
    o = obs()
    ok = o.get("self", {}).get("open_screen")
    return bool(ok)


def craft(item, n=1):
    r = act({"type": "craft", "item": item, "count": n})
    o = r.get("observation", {})
    say(f"  craft {item} x{n}: {o.get('control', {}).get('last_result', r.get('result'))}")


def craft_at_table(pos, item, n=1):
    open_table(pos)
    craft(item, n)
    close_screen(obs())   # the crafting screen stays open; every later action would be refused


def place_table(o):
    """Place the crafting table somewhere next to us and return its block position."""
    have = slot_of(o, "crafting_table")
    if have is None:
        # a table may already stand nearby from an earlier life
        b = find(o, ("crafting_table",))
        if b:
            return tuple(b["nearest"])
        return None
    r = act({"type": "place", "item": "crafting_table"})
    say("  place table: " + str((r.get("observation", {}).get("control", {}) or {}).get("last_result")))
    o = obs()
    b = find(o, ("crafting_table",), within=6.0)
    if b:
        return tuple(b["nearest"])
    fx, fy, fz = feet(o)
    for x, y, z in ((fx + 1, fy, fz), (fx - 1, fy, fz), (fx, fy, fz + 1), (fx, fy, fz - 1),
                    (fx + 1, fy, fz + 1), (fx - 1, fy, fz - 1)):
        act({"type": "place", "x": x, "y": y, "z": z, "item": "crafting_table"})
        b = find(obs(), ("crafting_table",), within=6.0)
        if b:
            return tuple(b["nearest"])
    return None


def mine_stone(want):
    got = 0
    for _ in range(30):
        o = obs()
        if count(o, "cobblestone") >= want:
            break
        b = find(o, STONE, within=20.0)
        if not b:
            say("no stone in sight")
            break
        x, y, z = b["nearest"]
        if b["dist"] > 3.4:
            act({"type": "go", "near": True, "x": x, "y": y, "z": z}, timeout=300)
        o = obs()
        if (x, y, z) == feet(o):
            # never stand in the block we break: step aside first
            act({"type": "walk", "x": x + 1, "y": y, "z": z})
        r = act({"type": "dig", "x": x, "y": y, "z": z})
        act({"type": "go", "x": x, "y": y, "z": z}, timeout=120)
        got = count(obs(), "cobblestone")
        say(f"  stone {x},{y},{z}: {r.get('result')} -> {got} cobble")
    return count(obs(), "cobblestone")


def main(argv):
    want_logs, want_stone = 3, 6
    if "--logs" in argv:
        want_logs = int(argv[argv.index("--logs") + 1])
    if "--stone" in argv:
        want_stone = int(argv[argv.index("--stone") + 1])

    o = obs()
    if o["self"].get("dead"):
        say("dead — respawn first (hc respawn)")
        return 1
    close_screen(o)
    escape_the_hole(o)

    o = obs()
    if count(o, "stone_pickaxe"):
        say("already have a stone pickaxe")
        return 0

    have_pick = count(o, "wooden_pickaxe") or count(o, "stone_pickaxe") or count(o, "iron_pickaxe")
    table = None
    if not have_pick:
        say("chopping wood")
        if chop_logs(want_logs) < 1:
            say("no wood: cannot start")
            return 1
        o = obs()
        for log in LOGS:
            if count(o, log):
                kind = log.replace("_log", "_planks")
                if not count(o, kind):
                    craft(kind, count(o, log))
                break
        if not count(obs(), "crafting_table"):
            craft("crafting_table", 1)
        table = place_table(obs())
        if not table:
            say("could not place the crafting table")
            return 1
        say(f"table at {table}")
        if count(obs(), "stick") < 2:
            craft_at_table(table, "stick", 1)
        if not count(obs(), "wooden_pickaxe"):
            craft_at_table(table, "wooden_pickaxe", 1)
        o = obs()
        pick = slot_of(o, "wooden_pickaxe")
        if pick is None:
            say("no pickaxe was made")
            return 1

    # the pickaxe must be in hand: a tool left in the backpack digs nothing
    o = obs()
    pick = slot_of(o, "stone_pickaxe") or slot_of(o, "iron_pickaxe") or slot_of(o, "wooden_pickaxe")
    if pick is None:
        say("no pickaxe in the inventory")
        return 1
    if pick > 8:
        act({"type": "to_hotbar", "slot": pick, "hotbar": 0})
    act({"type": "hotbar", "slot": 0})

    # 4. stone -> stone tools
    if count(obs(), "cobblestone") < want_stone:
        say("mining stone")
        if mine_stone(want_stone) < 3:
            say("not enough stone")
            return 2
    o = obs()
    if count(o, "cobblestone") and not count(o, "stone_pickaxe"):
        if table is None:
            b = find(o, ("crafting_table",), within=8.0)
            table = tuple(b["nearest"]) if b else place_table(o)
        if not table:
            say("no crafting table for stone tools")
            return 2
        craft_at_table(table, "stone_pickaxe", 1)
    o = obs()
    if count(o, "cobblestone") and count(o, "stone_pickaxe") and not count(o, "stone_sword"):
        craft_at_table(table, "stone_sword", 1)

    o = obs()
    say("inventory: " + ", ".join(f"{i['item']}x{i['count']}" for i in o["self"]["inventory"]))
    if count(o, "stone_pickaxe"):
        say("ready: stone pickaxe")
        return 0
    say("stopped without a stone pickaxe")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
