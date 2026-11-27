#!/usr/bin/env python3
"""Build the T13 test cave (experimenter tool). Three levels, one one-way drop, one way home.

  A  entrance room (stand y=14)  ──corridor──▶ ledge ──DROP 5 m (one way)──▶ B  big chamber (stand y=9)
  ▲                                                                            │ stairs down
  └──────────── 12-step stair tunnel up ◀──────── C  deepest room (stand y=2) ◀┘ corridor
"""
import sys, time
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from rcon import run as rcon

X0, Y0, Z0 = 3000, 150, 3000
def at(x, y, z): return f"{X0+x} {Y0+y} {Z0+z}"
def box(x1, y1, z1, x2, y2, z2, b): return f"fill {at(x1,y1,z1)} {at(x2,y2,z2)} {b}"

def build():
    # in a fresh world these chunks don't exist yet: generate them first (the server stalls for seconds doing it)
    rcon([f"forceload add {X0-16} {Z0-16} {X0+60} {Z0+50}"])
    for _ in range(60):
        if all("passed" in "".join(rcon([f"execute if loaded {X0+dx} {Y0} {Z0+dz}"])) for dx in (-16, 60) for dz in (-16, 50)): break
        time.sleep(1)
    cmds = [box(-2, -2, -2, 20, 22, 34, "stone"), box(21, -2, -2, 42, 22, 34, "stone")]
    # A: entrance room
    cmds += [box(0, 14, 0, 8, 16, 8, "air"), box(0, 17, 0, 8, 17, 8, "glowstone")]
    # corridor A -> ledge, ledge, B
    cmds += [box(9, 14, 3, 21, 15, 4, "air"), box(9, 16, 3, 21, 16, 4, "glowstone")]
    cmds += [box(22, 9, 0, 30, 16, 10, "air"), box(22, 17, 0, 30, 17, 10, "glowstone"),
             box(21, 9, 0, 21, 13, 10, "stone")]                           # B's west wall below the ledge mouth
    # stairs B -> down south to y=2, then flat south, then west into C
    for s in range(8):
        cmds += [box(25, 9 - s, 11 + s, 26, 11 - s, 11 + s, "air"), box(25, 12 - s, 11 + s, 26, 12 - s, 11 + s, "glowstone")]
    cmds += [box(25, 2, 19, 26, 4, 25, "air"), box(25, 5, 19, 26, 5, 25, "glowstone"),
             box(9, 2, 24, 26, 4, 25, "air"), box(9, 5, 24, 26, 5, 25, "glowstone")]
    # C: deepest room
    cmds += [box(0, 2, 22, 8, 4, 30, "air"), box(0, 5, 22, 8, 5, 30, "glowstone")]
    # stairs C -> up north to A (12 steps), 2 wide
    for s in range(13):
        cmds += [box(2, 2 + s, 21 - s, 3, 4 + s, 21 - s, "air"), box(2, 5 + s, 21 - s, 3, 5 + s, 21 - s, "glowstone")]
    rcon(cmds)

if __name__ == "__main__":
    build()
    if "--tp" in sys.argv:
        rcon([f"tp Agent {X0+4.5} {Y0+14} {Z0+4.5} facing {X0+15} {Y0+14.5} {Z0+4}"])
