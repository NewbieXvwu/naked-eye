# Naked Eye — brief for the AI playing it

You are the brain of a Minecraft player (Java Edition 26.3, survival, difficulty easy, fixed world seed
`20261126`, everyone starts on the same spot). A Fabric mod turns the game into an API that tells you **only what a
human player could perceive**: what is in view and lit well enough to see, sounds as subtitles with a
rough direction, your own inventory and health. No X-ray, no exact positions of things behind walls, no
mob health bars. You act the way a player does: look, walk, click, hold keys, open containers.

## Start

```sh
./setup.sh          # first time only: downloads Java 25 + the game (~1 GB). The offline package skips this.
./start.sh          # server + client in the background; returns when the API is up
tools/hc obs        # what I see right now
tools/hc help       # every command
./stop.sh           # freeze + save the world, stop everything (always do this before you leave)
```

Needs Python 3.8+, ~4 GB RAM and ~3 GB of disk. The game window is for humans watching and starts hidden
(`./window.sh show|hide`); Linux without a display runs fully headless.

## How to play

- Everything goes through `tools/hc` (or HTTP on `127.0.0.1:7070`: `GET /observe`, `POST /act`). Read
  `tools/hc help` first. The useful ones: `obs`, `look`, `turn`, `go X Y Z` (pathfinding only through
  places you have seen), `walk`, `dig`, `tunnel`/`stairs`/`upstairs`, `place`, `fight ID`, `eat`, `craft`, `open`/`close`,
  `hold KEYS TICKS`, `wait TICKS`.
- **The world is frozen while you think.** It runs only while one of your actions is in flight, and freezes
  again a moment after the action is done. Take your time.
- Some things happen on their own, fast, like reflexes: swimming up when out of air, digging out when
  buried, pouring a water bucket when in lava, backing off a swelling creeper, hitting back when hit.
- When something alarming happens mid-action (hurt, a hostile mob close, on fire), the world pauses and
  the action is interrupted with a `PAUSED` / `woke early` reason. Look, decide, act.
- `tools/hc speed 3` runs the whole game (server and client together) 3× faster. Allowed and fair: you and
  the world speed up together. Don't go higher than the machine keeps up with (it drops back by itself).
- You may write your own scripts on top of `tools/hc` / the API (see `agent/` for examples).

## Rules (what keeps it fair)

Allowed: the agent API (`tools/hc`, port 7070), `tools/hc speed`, `tools/hc pause`, your own scripts using them.

Not allowed — these are the experimenter's tools and would be cheating:
- RCON (`tools/rcon.py`, port 25575) and any server command (`/give`, `/tp`, `/locate`, `/seed`, …)
- the experimenter API on port 7071 (ground truth; switched off when started with `HONEST_DEBUG=0`)
- reading or editing world or server files (`run/server/`), the client's memory files, or the seed
- changing the mods, the configs or the launcher

Every session is recorded (`run/server/recordings/`, viewable in ReplayMod; `tools/replay_cut.py`
removes the frozen thinking time), and the server log keeps every command that was run.

## Files

`launcher/mc.py` portable launcher · `mods/` the four mods this package ships · `config/` first-run
templates · `tools/` `hc`, tests (`verify.py`, experimenter-only), RCON · `agent/` example scripts ·
`src/` source of the mods and the Baritone patch · `README.md` the project's history (in Chinese).
