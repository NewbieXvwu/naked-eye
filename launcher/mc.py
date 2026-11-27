#!/usr/bin/env python3
"""Portable launcher for Naked Eye (client + server) — Python 3.8+ standard library only.

    mc.py setup              download everything this platform needs into .cache/ (skips what is already there)
    mc.py setup --offline    check that everything is already there; never touches the network
    mc.py server             run the Fabric server (foreground)
    mc.py client             run the client (foreground), joins the local server
    mc.py prefetch-linux     (packaging) fill .cache/ for linux-x86_64, whatever this machine is

No Gradle at run time: the client is launched the way a normal launcher does it (Fabric's Knot on top of the
vanilla jar), so the only things needed are a Java 25 runtime, the game files and the four mods.
Supported: macOS (Apple Silicon and Intel) and Linux x86_64. Mojang ships no Linux arm64 natives.
"""
import hashlib, json, os, platform, shutil, subprocess, sys, tarfile, urllib.request, uuid, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.environ.get("HC_CACHE", os.path.join(ROOT, ".cache"))
RUN = os.path.join(ROOT, "run")
MC, LOADER, JAVA_MAJOR = "26.3", "0.20.1", 25
FABRIC_INSTALLER = "1.1.2"
FABRIC_API = ("fabric-api-0.167.0+26.3.jar", "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.167.0%2B26.3/fabric-api-0.167.0%2B26.3.jar",
              "5784ccebbac7ad1729da9c4f7207d2f2e96e73ee")
SERVER_MODS = [FABRIC_API,
    ("fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar", "https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/1.14.2%2Bkotlin.2.4.21/fabric-language-kotlin-1.14.2%2Bkotlin.2.4.21.jar",
     "f1d4ab6ecd54d9782521333a87eb50724ecb03e0"),
    ("ServerReplay-3.6.0+26.3.jar", "https://cdn.modrinth.com/data/qCvSZ8ra/versions/ly1yAeRN/ServerReplay-3.6.0%2B26.3.jar",
     "a1f6d811a96071662fcb6f062c4b3f6eab821c9d")]
OUR_CLIENT_MODS = ["honest-client-0.2.0.jar", "baritone-honest.jar", "honest-pause-1.0.0.jar"]   # shipped in mods/
OUR_SERVER_MODS = ["honest-pause-1.0.0.jar"]
OFFLINE = False


def plat():
    s, m = platform.system(), platform.machine().lower()
    osn = {"Darwin": "osx", "Linux": "linux"}.get(s)
    arch = "arm64" if m in ("arm64", "aarch64") else "x86_64" if m in ("x86_64", "amd64") else m
    if osn is None: sys.exit(f"unsupported OS {s} (macOS or Linux only)")
    if osn == "linux" and arch != "x86_64": sys.exit("Linux arm64 isn't supported: Mojang ships no Linux arm64 natives")
    return osn, arch


# ------------------------------------------------------------------ downloads
def fetch(url, dest, sha1=None):
    if os.path.exists(dest) and (sha1 is None or sha1file(dest) == sha1): return dest
    if OFFLINE: sys.exit(f"offline, and this file is missing: {os.path.relpath(dest, ROOT)}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "naked-eye"}), timeout=60) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            break
        except Exception as e:
            # python.org's Python on macOS often has no CA certificates installed: the system curl does
            if "CERTIFICATE_VERIFY_FAILED" in str(e) and shutil.which("curl") and \
                    subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", tmp, url]).returncode == 0: break
            if attempt == 3: sys.exit(f"download failed: {url}: {e}")
    if sha1 and sha1file(tmp) != sha1: os.remove(tmp); sys.exit(f"checksum mismatch: {url}")
    os.replace(tmp, dest)
    return dest


def sha1file(p):
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def jget(url, dest):
    fetch(url, dest)
    with open(dest) as f: return json.load(f)


def maven_path(coord):
    g, a, v = coord.split(":")[:3]
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"


# ------------------------------------------------------------------ Mojang version metadata
def version_json():
    p = os.path.join(CACHE, "mc", f"{MC}.json")
    if os.path.exists(p):
        with open(p) as f: return json.load(f)
    man = jget("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", os.path.join(CACHE, "mc", "manifest.json"))
    v = next(v for v in man["versions"] if v["id"] == MC)
    fetch(v["url"], p, v["sha1"])
    with open(p) as f: return json.load(f)


def rules_ok(rules, osn, arch):
    if not rules: return True
    ok = False
    for r in rules:
        o = r.get("os", {}); hit = True
        if "name" in o and o["name"] != osn: hit = False
        if "arch" in o and o["arch"] != ("x86" if arch == "x86" else arch): hit = False
        if "features" in r: hit = False
        if hit: ok = r["action"] == "allow"
    return ok


def libraries(v, osn, arch):
    out = []
    for lib in v["libraries"]:
        if not rules_ok(lib.get("rules"), osn, arch): continue
        a = lib.get("downloads", {}).get("artifact")
        if a: out.append((os.path.join(CACHE, "mc", "libraries", a["path"]), a["url"], a["sha1"]))
    return out


def fabric_libs():
    prof = jget(f"https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/profile/json", os.path.join(CACHE, "mc", f"fabric-{LOADER}.json"))
    libs = [(os.path.join(CACHE, "mc", "libraries", maven_path(l["name"])), l["url"].rstrip("/") + "/" + maven_path(l["name"]), l.get("sha1"))
            for l in prof["libraries"]]
    return prof, libs


def assets(v):
    ai = v["assetIndex"]
    idx = os.path.join(CACHE, "mc", "assets", "indexes", ai["id"] + ".json")
    fetch(ai["url"], idx, ai["sha1"])
    with open(idx) as f: objs = json.load(f)["objects"]
    # the offline package leaves out music, records and the non-English languages (~390 MB the agent never uses;
    # their volume is 0 there, so the game doesn't look for them)
    trim = os.path.join(CACHE, "mc", "assets", "TRIMMED")
    skip = tuple(open(trim).read().split()) if os.path.exists(trim) else ()
    hashes = sorted({o["hash"] for k, o in objs.items() if not (skip and k.startswith(skip))})
    get = lambda h: fetch(f"https://resources.download.minecraft.net/{h[:2]}/{h}", os.path.join(CACHE, "mc", "assets", "objects", h[:2], h), h)
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(16) as ex:   # ~4000 small files: one at a time takes 10+ minutes
        for i, _ in enumerate(ex.map(get, hashes)):
            if i % 1000 == 0: print(f"  assets {i}/{len(hashes)}", flush=True)
    return ai["id"]


# ------------------------------------------------------------------ Java
def java_bin():
    """a Java 25: the bundled runtime/, else JAVA_HOME/PATH if it is >= 25, else one downloaded from Adoptium"""
    osn, arch = plat()
    cands = [os.path.join(ROOT, "runtime", f"{osn}-{arch}", "bin", "java"), os.path.join(CACHE, "jdk", "bin", "java"),
             os.path.join(CACHE, "jdk", "Contents", "Home", "bin", "java")]
    if os.environ.get("JAVA_HOME"): cands.append(os.path.join(os.environ["JAVA_HOME"], "bin", "java"))
    if shutil.which("java"): cands.append(shutil.which("java"))
    for j in cands:
        if os.path.exists(j) and java_major(j) >= JAVA_MAJOR: return j
    if OFFLINE: sys.exit("offline, and no Java 25 found (set JAVA_HOME to one)")
    url = f"https://api.adoptium.net/v3/binary/latest/{JAVA_MAJOR}/ga/{'mac' if osn == 'osx' else 'linux'}/{'aarch64' if arch == 'arm64' else 'x64'}/jdk/hotspot/normal/eclipse"
    print("downloading Java", JAVA_MAJOR, "(Adoptium) ...", flush=True)
    tgz = fetch(url, os.path.join(CACHE, "jdk.tar.gz"))
    dest = os.path.join(CACHE, "jdk"); shutil.rmtree(dest, ignore_errors=True)
    with tarfile.open(tgz) as t:
        top = t.getnames()[0].split("/")[0]
        t.extractall(CACHE, **({"filter": "tar"} if hasattr(tarfile, "tar_filter") else {}))
    os.replace(os.path.join(CACHE, top), dest); os.remove(tgz)
    return java_bin()


def java_major(j):
    try:
        out = subprocess.run([j, "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=30).stderr
        line = next(l for l in out.splitlines() if "java.specification.version" in l)
        return int(line.split("=")[1].strip().split(".")[0])
    except Exception:
        return 0


# ------------------------------------------------------------------ setup
def setup(osn=None, arch=None, with_java=True):
    o, a = plat() if osn is None else (osn, arch)
    print(f"[setup] {o}-{a}, cache: {CACHE}{'  (offline)' if OFFLINE else ''}", flush=True)
    if with_java: java_bin()
    v = version_json()
    fetch(v["downloads"]["client"]["url"], os.path.join(CACHE, "mc", f"client-{MC}.jar"), v["downloads"]["client"]["sha1"])
    for p, u, s in libraries(v, o, a) + fabric_libs()[1]: fetch(u, p, s)
    print("[setup] game assets (sounds, languages) ...", flush=True); assets(v)
    # server: vanilla jar + Fabric's server launcher + mods
    sv = os.path.join(CACHE, "server")
    fetch(v["downloads"]["server"]["url"], os.path.join(sv, "server.jar"), v["downloads"]["server"]["sha1"])
    fetch(f"https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/{FABRIC_INSTALLER}/server/jar", os.path.join(sv, "fabric-server-launch.jar"))
    for name, u, s in SERVER_MODS: fetch(u, os.path.join(CACHE, "mods", name), s)
    for name in set(OUR_CLIENT_MODS + OUR_SERVER_MODS):
        if not os.path.exists(os.path.join(ROOT, "mods", name)): sys.exit(f"missing mods/{name} (it ships with this package)")
    print("[setup] done", flush=True)


# ------------------------------------------------------------------ run
def link_mods(dest, names):
    os.makedirs(dest, exist_ok=True)
    for f in os.listdir(dest):
        if f.endswith(".jar"): os.remove(os.path.join(dest, f))
    for n in names:
        src = os.path.join(ROOT, "mods", n) if os.path.exists(os.path.join(ROOT, "mods", n)) else os.path.join(CACHE, "mods", n)
        shutil.copy2(src, os.path.join(dest, n))


def seed_dir(dest, template):
    """first run: copy the template (configs) without overwriting what the run already changed"""
    if not os.path.isdir(template): return
    for base, _, files in os.walk(template):
        for f in files:
            s = os.path.join(base, f); d = os.path.join(dest, os.path.relpath(s, template))
            if not os.path.exists(d): os.makedirs(os.path.dirname(d), exist_ok=True); shutil.copy2(s, d)


def server():
    d = os.path.join(RUN, "server"); os.makedirs(d, exist_ok=True)
    sv = os.path.join(CACHE, "server")
    # Fabric's server launcher looks for the vanilla jar at .fabric/server/<ver>-server.jar (else downloads it again)
    for src, dst in (("server.jar", os.path.join(".fabric", "server", f"{MC}-server.jar")), ("fabric-server-launch.jar", "fabric-server-launch.jar")):
        if not os.path.exists(os.path.join(d, dst)):
            os.makedirs(os.path.dirname(os.path.join(d, dst)), exist_ok=True); shutil.copy2(os.path.join(sv, src), os.path.join(d, dst))
    # an offline package ships the server's first-run downloads (Fabric's libraries, the unpacked vanilla bundle)
    seed_dir(d, os.path.join(sv, "firstrun"))
    seed_dir(d, os.path.join(ROOT, "config", "server"))
    rcon_password(d)
    link_mods(os.path.join(d, "mods"), [m[0] for m in SERVER_MODS] + OUR_SERVER_MODS)
    # record the agents only, not the people watching them (the list lives in config/naked-eye/agents.txt)
    try:
        agents = [l.strip() for l in open(os.path.join(d, "config", "naked-eye", "agents.txt")) if l.strip() and not l.startswith("#")]
        rc = os.path.join(d, "config", "server-replay", "config.json")
        cfg = json.load(open(rc)); cfg["player_predicate"] = {"type": "has_name", "names": agents or ["Agent"]}
        json.dump(cfg, open(rc, "w"), indent=2)
    except Exception as e:
        print("[server] couldn't set the replay player list:", e, flush=True)
    j = java_bin(); os.chdir(d)
    os.execv(j, [j, f"-Xmx{os.environ.get('HONEST_SERVER_XMX', '2G')}", "-jar", "fabric-server-launch.jar", "nogui"])


def rcon_password(server_dir):
    """RCON listens on every network interface: give each install its own random password, never the one in git"""
    import secrets
    p = os.path.join(server_dir, "server.properties")
    lines = open(p).read().splitlines()
    for i, l in enumerate(lines):
        if l.startswith("rcon.password=") and l.split("=", 1)[1].strip() in ("", "honest", "CHANGED-ON-FIRST-START"):
            lines[i] = "rcon.password=" + secrets.token_hex(16)
            open(p, "w").write("\n".join(lines) + "\n")
    return next(l.split("=", 1)[1].strip() for l in lines if l.startswith("rcon.password="))


def client():
    osn, arch = plat()
    v = version_json(); prof, flibs = fabric_libs()
    # HONEST_NAME: another player on the same machine (e.g. a second agent, or a test watcher) with its own game dir/port
    name = os.environ.get("HONEST_NAME", "Agent")
    gd = os.path.join(RUN, "client" if name == "Agent" else "client-" + name); os.makedirs(gd, exist_ok=True)
    seed_dir(gd, os.path.join(ROOT, "config", "client"))
    link_mods(os.path.join(gd, "mods"), [FABRIC_API[0]] + OUR_CLIENT_MODS)
    nat = os.path.join(CACHE, "natives"); os.makedirs(nat, exist_ok=True)
    cp = [p for p, _, _ in flibs] + [p for p, _, _ in libraries(v, osn, arch)] + [os.path.join(CACHE, "mc", f"client-{MC}.jar")]
    missing = [p for p in cp if not os.path.exists(p)]
    if missing: sys.exit(f"{len(missing)} game files missing, e.g. {missing[0]} — run ./setup.sh first")
    sub = {"${natives_directory}": nat, "${launcher_name}": "naked-eye", "${launcher_version}": "1", "${classpath}": os.pathsep.join(cp)}
    jvm = []
    for a in v["arguments"]["jvm"]:
        if isinstance(a, dict):
            if not rules_ok(a.get("rules"), osn, arch): continue
            vals = a["value"] if isinstance(a["value"], list) else [a["value"]]
        else: vals = [a]
        for x in vals:
            for k, s in sub.items(): x = x.replace(k, s)
            jvm.append(x)
    jvm = prof["arguments"]["jvm"] + [f"-Xmx{os.environ.get('HONEST_XMX', '2G')}",
           "-Dhonest.headless=" + os.environ.get("HONEST_HEADLESS", "true" if no_screen(osn) else "false"),
           "-Dhonest.show=" + ("true" if os.environ.get("HONEST_SHOW") == "1" else "false"),
           "-Dhonest.port=" + os.environ.get("HONEST_PORT", "7070"),
           "-Dhonest.pause=" + ("false" if os.environ.get("HONEST_PAUSE") == "0" or os.environ.get("HONEST_REVIEW") else "true"),
           "-Dhonest.review=" + ("true" if os.environ.get("HONEST_REVIEW") else "false"),
           "-Dhonest.review.flightlog=" + os.environ.get("HONEST_REVIEW_FLIGHTLOG", ""),
           "-Dhonest.rcon.pass=" + rcon_password(os.path.join(RUN, "server")) if os.path.exists(os.path.join(RUN, "server", "server.properties")) else "-Dhonest.rcon.pass=",
           "-Dhonest.debug=" + ("false" if os.environ.get("HONEST_DEBUG") == "0" else "true")] + os.environ.get("HONEST_JAVA_OPTS", "").split() + jvm
    game = ["--username", name, "--version", MC, "--gameDir", gd, "--assetsDir", os.path.join(CACHE, "mc", "assets"),
            "--assetIndex", v["assetIndex"]["id"], "--uuid", str(uuid.uuid3(uuid.NAMESPACE_DNS, "Agent")), "--accessToken", "0",
            "--versionType", "release", "--quickPlayMultiplayer", os.environ.get("HONEST_SERVER", "127.0.0.1:25565"), "--width", "1280", "--height", "720"]
    env = dict(os.environ)
    if no_screen(osn):
        # no screen: SDL's offscreen driver + Mesa's surfaceless EGL + llvmpipe software GL; no sound device
        env.update(SDL_VIDEO_DRIVER="offscreen", EGL_PLATFORM="surfaceless", LIBGL_ALWAYS_SOFTWARE="1", GALLIUM_DRIVER="llvmpipe", ALSOFT_DRIVERS="null")
        mesa = os.path.join(ROOT, "runtime", "mesa-linux-x86_64")
        if os.path.isdir(mesa) and (os.environ.get("HONEST_BUNDLED_MESA") == "1" or not have_system_egl()):
            print("[client] no system Mesa: using the bundled one", flush=True)
            env["LD_LIBRARY_PATH"] = os.path.join(mesa, "lib") + os.pathsep + env.get("LD_LIBRARY_PATH", "")
            env["__EGL_VENDOR_LIBRARY_FILENAMES"] = os.path.join(mesa, "50_mesa.json")
            env["LIBGL_DRIVERS_PATH"] = os.path.join(mesa, "lib", "dri")
    if os.environ.get("HONEST_MUTE", "1" if osn == "linux" else "0") == "1": env["ALSOFT_DRIVERS"] = "null"
    os.chdir(gd)
    j = java_bin()
    os.execve(j, [j] + jvm + [prof["mainClass"]] + game, env)


def no_screen(osn):
    """Linux with no display (a server, a sandbox): render offscreen, and don't draw at all"""
    return osn == "linux" and os.environ.get("HONEST_WINDOW") != "1" and not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY")


def have_system_egl():
    try: return "libEGL.so.1" in subprocess.run(["/sbin/ldconfig", "-p"], capture_output=True, text=True).stdout
    except Exception: return os.path.exists("/usr/lib/x86_64-linux-gnu/libEGL.so.1")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--offline" in args or os.path.exists(os.path.join(ROOT, ".offline")): OFFLINE = True
    cmd = args[0] if args else "help"
    if cmd == "setup": setup()
    elif cmd == "prefetch-linux": setup("linux", "x86_64", with_java=False)
    elif cmd == "server": server()
    elif cmd == "client": client()
    else: print(__doc__)
