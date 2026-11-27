#!/usr/bin/env python3
"""Rebuild recordings whose saving was cut short: python3 tools/replay_repair.py

ServerReplay writes a recording into NAME.tmp/ as it goes and packs it into NAME.mcpr when the recording ends (the
server stopping, the player leaving). If the server is killed while packing, NAME.mcpr is left broken but NAME.tmp/
still holds every packet. This packs it again (the length worked out from the last packet), checks the result, and
only then removes the .tmp folder. It won't run while the server is up: the .tmp folders are live recordings then.
"""
import json, os, struct, sys, zipfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REC = os.path.join(ROOT, "run", "server", "recordings")


def server_up():
    try: os.kill(int(open(os.path.join(ROOT, "run", "server.pid")).read()), 0); return True
    except Exception: return False


def ok(mcpr):
    try:
        with zipfile.ZipFile(mcpr) as z: return z.testzip() is None and "recording.tmcpr" in z.namelist()
    except Exception: return False


def last_timestamp(tmcpr):
    """recording.tmcpr is [int32 time ms][int32 length][packet] over and over (big-endian)"""
    t, n = 0, os.path.getsize(tmcpr)
    with open(tmcpr, "rb") as f:
        pos = 0
        while pos + 8 <= n:
            h = f.read(8)
            ts, ln = struct.unpack(">ii", h)
            if ln < 0 or pos + 8 + ln > n: break   # (a packet cut off at the very end: drop it)
            t = ts; pos += 8 + ln; f.seek(pos)
    return t


def repair(tmp):
    src = os.path.join(tmp, "changed")
    mcpr = tmp[:-4] + ".mcpr"
    if not os.path.exists(os.path.join(src, "recording.tmcpr")): return None
    meta = json.load(open(os.path.join(src, "metaData.json")))
    meta["duration"] = max(meta.get("duration", 0), last_timestamp(os.path.join(src, "recording.tmcpr")))
    part = mcpr + ".part"
    with zipfile.ZipFile(part, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(os.listdir(src)):
            if name == "metaData.json": z.writestr(name, json.dumps(meta))
            else: z.write(os.path.join(src, name), name)
    if not ok(part): os.remove(part); return False
    os.replace(part, mcpr)
    for name in os.listdir(src): os.remove(os.path.join(src, name))
    os.rmdir(src); os.rmdir(tmp)
    return meta["duration"]


if __name__ == "__main__":
    if server_up() and "--force" not in sys.argv:
        sys.exit("the server is running: its .tmp folders are recordings in progress (stop it first)")
    quiet = "--quiet" in sys.argv
    for d, subdirs, _ in os.walk(REC):
        for s in sorted(subdirs):
            if not s.endswith(".tmp"): continue
            tmp = os.path.join(d, s)
            r = repair(tmp)
            rel = os.path.relpath(tmp[:-4] + ".mcpr", ROOT)
            if r is None: continue
            print(f"rebuilt {rel} ({r // 60000} min {r // 1000 % 60} s)" if r is not False else f"couldn't rebuild {rel}")
