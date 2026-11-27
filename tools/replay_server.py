#!/usr/bin/env python3
"""Serve a recording back to a real client, as if it were the live server.

ServerReplay recorded every packet the server sent the agent (login -> configuration -> play), already encoded.
This plays them back over an ordinary connection on 127.0.0.1:PORT, so a client with the honest mod joins
"the same world at the same moment" and its perception can be recomputed at any point (see tools/review.py).

The client's packets are read and dropped; only two of them matter: the acknowledgements after "login finished"
and after "finish configuration", which the real server waited for before going on.
Frozen stretches (the brain thinking) are skipped in time, as in replay_cut.py.

    replay_server.py REPLAY.mcpr [--port 25599] [--speed 1]      (standalone: plays once, from the start)
"""
import json, socket, struct, sys, threading, time, zipfile

TICKING_STATE = {777: 0x82}
LOGIN_FINISHED, FINISH_CONFIGURATION = 0x02, 0x03


def varint(b, j):
    v = s = 0
    while True:
        x = b[j]; j += 1; v |= (x & 0x7F) << s; s += 7
        if not x & 0x80: return v, j


def enc_varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F; v >>= 7
        out.append(b | (0x80 if v else 0))
        if not v: return bytes(out)


def load(path):
    """-> list of (t_ms_with_thinking_removed, packet_bytes, phase) ; phase: login/config/play"""
    with zipfile.ZipFile(path) as z:
        meta = json.loads(z.read("metaData.json")); data = z.read("recording.tmcpr")
    state_id = TICKING_STATE[meta["protocol"]]
    frames, i, phase, frozen_at, removed = [], 0, "login", None, 0
    while i + 8 <= len(data):
        ts, ln = struct.unpack(">ii", data[i:i + 8]); p = data[i + 8:i + 8 + ln]; i += 8 + ln
        if len(p) < ln: break
        pid, j = varint(p, 0)
        if phase == "play" and pid == state_id and ln - j == 5:
            frozen = p[j + 4] == 1
            if frozen and frozen_at is None: frozen_at = ts
            elif not frozen and frozen_at is not None: removed += ts - frozen_at; frozen_at = None
        t = (frozen_at if frozen_at is not None else ts) - removed
        frames.append((t, p, phase))
        if phase == "login" and pid == LOGIN_FINISHED: phase = "config"
        elif phase == "config" and pid == FINISH_CONFIGURATION and ln == 1: phase = "play"
    return meta, frames


class Player:
    """streams frames to one client connection; can be paused, sped up, and told to stop at a frame"""
    def __init__(self, frames, speed=1.0, protocol=777):
        self.frames, self.speed = frames, speed
        self.pos = 0                 # next frame to send
        self.paused = False
        self.stop_at = None          # pause when pos reaches this
        self.done = threading.Event()
        self.rx = 0; self.rx_cv = threading.Condition()
        # pausing the replay must stop the client's world too (or its clock runs on): send it a "frozen" ticking
        # state while paused, and the recording's own state again on resume
        self.state_id = TICKING_STATE[protocol]
        self.rec_state = enc_varint(self.state_id) + struct.pack(">f", 20.0) + b"\x00"   # as last seen in the stream
        self.held = False

    def _reader(self, conn):
        """count the client's packets (length-prefixed frames, uncompressed): handshake, hello, acknowledgements…"""
        buf = b""
        while True:
            try: b = conn.recv(65536)
            except OSError: b = b""
            with self.rx_cv:
                if not b: self.rx = -1; self.rx_cv.notify_all(); return
                buf += b
                while buf:
                    try: n, j = varint(buf, 0)
                    except IndexError: break
                    if len(buf) < j + n: break
                    buf = buf[j + n:]; self.rx += 1
                self.rx_cv.notify_all()

    def _await_client(self, since, timeout=30):
        with self.rx_cv:
            self.rx_cv.wait_for(lambda: self.rx == -1 or self.rx > since, timeout)

    def serve(self, conn):
        threading.Thread(target=self._reader, args=(conn,), daemon=True).start()
        self._await_client(1)                       # both handshake and hello, before "login finished"
        clock0 = None
        while self.pos < len(self.frames):
            if self.rx == -1: break
            if self.paused or (self.stop_at is not None and self.pos >= self.stop_at):
                self.paused = True; clock0 = None
                if not self.held and self.frames[self.pos][2] == "play":
                    rate = struct.unpack(">f", self.rec_state[-5:-1])[0]
                    p = enc_varint(self.state_id) + struct.pack(">f", rate) + b"\x01"
                    conn.sendall(enc_varint(len(p)) + p); self.held = True
                time.sleep(0.02); continue
            if self.held:
                conn.sendall(enc_varint(len(self.rec_state)) + self.rec_state); self.held = False
            t, p, phase = self.frames[self.pos]
            if phase == "play":
                if clock0 is None: clock0 = (time.monotonic(), t)
                wait = (t - clock0[1]) / 1000 / self.speed - (time.monotonic() - clock0[0])
                if wait > 0: time.sleep(min(wait, 0.05)); continue
            with self.rx_cv: mark = self.rx
            conn.sendall(enc_varint(len(p)) + p)
            self.pos += 1
            pid, j = varint(p, 0)
            if phase == "play" and pid == self.state_id and len(p) - j == 5: self.rec_state = p
            if (phase == "login" and pid == LOGIN_FINISHED) or (phase == "config" and pid == FINISH_CONFIGURATION and len(p) == 1):
                self._await_client(mark)               # the real server waited for the client's acknowledgement
        self.done.set()


def serve_once(frames, port=25599, speed=1.0):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port)); srv.listen(1)
    player = Player(frames, speed)
    def run():
        conn, _ = srv.accept()
        player.serve(conn)
    threading.Thread(target=run, daemon=True).start()
    return player, srv


if __name__ == "__main__":
    path = sys.argv[1]
    port = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 25599
    speed = float(sys.argv[sys.argv.index("--speed") + 1]) if "--speed" in sys.argv else 1.0
    meta, frames = load(path)
    play = [f for f in frames if f[2] == "play"]
    print(f"{len(frames)} packets, {play[-1][0] / 1000:.0f} s of play (thinking removed); waiting on 127.0.0.1:{port}", flush=True)
    player, srv = serve_once(frames, port, speed)
    while not player.done.is_set(): time.sleep(0.5)
    print("replay finished", flush=True)
