#!/usr/bin/env python3
"""Cut the thinking out of a ReplayMod recording.

The world is frozen (`/tick freeze`) whenever the brain is thinking, so a raw recording is mostly a
still frame. The server tells every client when it freezes and unfreezes (the ticking-state packet),
and ServerReplay records those packets, so the recording already marks every pause exactly.
This drops the frozen stretches' wall-clock time: packets sent while frozen (chunks, block updates)
are kept, just squeezed onto the instant the pause began.

    replay_cut.py IN.mcpr [OUT.mcpr]      (default OUT: IN-cut.mcpr)
"""
import json, struct, sys, zipfile

TICKING_STATE = {777: 0x82}   # protocol -> id of ClientboundTickingStatePacket (float tick rate, bool frozen)


def varint(b, j):
    v = s = 0
    while True:
        x = b[j]; j += 1; v |= (x & 0x7F) << s; s += 7
        if not x & 0x80: return v, j


def cut(src, dst):
    with zipfile.ZipFile(src) as z:
        meta = json.loads(z.read("metaData.json"))
        data = z.read("recording.tmcpr")
        others = {n: z.read(n) for n in z.namelist() if n not in ("metaData.json", "recording.tmcpr", "recording.tmcpr.crc32")}
    pid_state = TICKING_STATE[meta["protocol"]]
    out = bytearray(); i = 0; removed = 0; frozen_at = None; frozen_n = 0
    while i + 8 <= len(data):
        ts, ln = struct.unpack(">ii", data[i:i + 8]); p = data[i + 8:i + 8 + ln]; i += 8 + ln
        if len(p) < ln: break                          # truncated tail of an unclean stop
        pid, j = varint(p, 0)
        if pid == pid_state and ln - j == 5:
            frozen = p[j + 4] == 1
            if frozen and frozen_at is None: frozen_at = ts; frozen_n += 1
            elif not frozen and frozen_at is not None: removed += ts - frozen_at; frozen_at = None
            continue                                   # the viewer never needs to see the world freeze
        t = (frozen_at if frozen_at is not None else ts) - removed
        out += struct.pack(">ii", t, ln) + p
    end = (frozen_at if frozen_at is not None else ts) - removed
    meta["duration"] = end
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("metaData.json", json.dumps(meta))
        z.writestr("recording.tmcpr", bytes(out))
        for n, b in others.items(): z.writestr(n, b)
    return meta["duration"], removed + ((ts - frozen_at) if frozen_at is not None else 0), frozen_n


if __name__ == "__main__":
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src.removesuffix(".mcpr") + "-cut.mcpr"
    kept, gone, n = cut(src, dst)
    print(f"{dst}: {kept/1000:.0f} s of play kept, {gone/1000:.0f} s of thinking cut ({n} pauses)")
