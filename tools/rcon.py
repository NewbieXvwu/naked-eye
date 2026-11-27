#!/usr/bin/env python3
"""Experimenter-only RCON tool. NOT part of the agent's interface."""
import os, socket, struct, sys

def _pkt(i, t, body):
    b = body.encode() + b"\x00\x00"
    return struct.pack("<iii", len(b) + 8, i, t) + b

def _recv(s):
    n = struct.unpack("<i", s.recv(4))[0]
    data = b""
    while len(data) < n:
        data += s.recv(n - len(data))
    i, t = struct.unpack("<ii", data[:8])
    return i, data[8:-2].decode(errors="replace")

def password():
    """the server's RCON password: random per install (see launcher/mc.py), read from its server.properties"""
    import os
    p = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "run", "server", "server.properties")
    try:
        for l in open(p):
            if l.startswith("rcon.password="): return l.split("=", 1)[1].strip()
    except OSError: pass
    return "honest"

def run(cmds, host="127.0.0.1", port=None, pw=None):
    pw = pw or password()
    if port is None:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import ports; port = ports.get()["rcon"]
    out = []
    with socket.create_connection((host, port)) as s:
        s.sendall(_pkt(1, 3, pw)); _recv(s)
        for c in cmds:
            s.sendall(_pkt(2, 2, c)); out.append(_recv(s)[1])
    return out

if __name__ == "__main__":
    for line in run(sys.argv[1:]):
        print(line)
