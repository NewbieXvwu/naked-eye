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
- When something alarming happens mid-action (hurt, a hostile mob close, on fire, a fall that will hurt), the world
  pauses and the action is interrupted with a `PAUSED` / `woke early` reason. Look, decide, act.
- The world may stand still while you're in mid-air: full HP then means nothing. `obs` says `⚠ FALLING` with the
  speed, the drop so far, the ground below (if you've seen it) and what the landing will cost. You see how fast
  you and everything in view are moving (`motion`, and `moving` on each entity) — still true while the world is frozen.
  Digging out the block you stand on is refused unless you know what's under it (`force` to insist); a water bucket in
  the hotbar is poured by reflex before a bad landing.
- **Speed up.** Right after `./start.sh`, run `tools/hc speed auto`: it finds the fastest speed this machine keeps
  up with (server and client together) and uses it. It's fair: you and the world speed up together, the world is
  frozen while you think anyway, and alarms and reflexes are checked every game tick at any speed. If the machine
  ever falls behind, the game drops back to 1× by itself — run `tools/hc speed auto` again later.
- Several copies of this folder can run on one machine at once (each gets its own ports and world; `tools/hc`
  finds its own).
- You may write your own scripts on top of `tools/hc` / the API (see `agent/` for examples).
- **Narrate as you play**, like subtitles: every few actions, `tools/hc note "…"` — one short line on how it's
  going and what you'll do next ("night's coming, digging in under the oak", "creeper hissing behind me, backing off").
  It goes into the game's record and onto the screen of anyone watching; it's how your failures get learned from.

## Cheat mode (a speedrun)

`HONEST_CHEAT=1 ./start.sh` turns this copy's world into a cheat-mode world for good (`run/cheat-mode`). The goal is
then to **finish the game (kill the Ender Dragon) in as little in-game time as possible** — the clock is the world's
own (`⏱ IGT` in `obs`), so thinking is still free.

- **You know everything the vanilla server sends this client**: every block of every loaded chunk (X-ray), every
  entity it knows of with its exact position, synced health and gear, through walls and in the dark.
  Not what the server never sends: the seed, structure locations, unopened chests, other players' inventories.
- **Your hands are still a client's hands**: no commands, no RCON, no faked packets (no-fall, flying, reach). What
  changes is that nothing pretends to be human: the view snaps, clicks aren't rate-limited, and Baritone runs at
  full power on the real world (parkour, digging down, water-bucket landings, mob avoidance, inventory sorting).
- Reading the world: `hc scan` (one screen: ores, obsidian, lava, chests, spawners, fortress/bastion blocks, mobs),
  `hc find BLOCK[,B] [r] [n]` (exact veins, nearest first), `hc ents [r] [type|hostile|item]`.
- Acting: `hc b CMD` runs a Baritone command until it's done (`mine 3 diamond_ore`, `goto X Y Z`, `goto
  crafting_table`, `follow entity cow`, `explore`, `thisway 300`, `surface`, `farm`); `hc make ITEM [n]` crafts
  (placing a table if needed), `hc smelt ITEM [fuel]` smelts a whole stack with just enough fuel.
- `obs` is compact in this mode: status, inventory, threats, the speedrun clock and only the sounds that mean danger.

## Sharing a world with other agents

You may be told to play alongside other agents — as team-mates, rivals, or both. You're told who shares the world
when someone joins or leaves, and the world's rules (an event line starting `rules here:`).

- **Time.** The world runs only while *every* agent is acting and stands still while any of you thinks. Your action
  may seem to hang: that's someone else thinking. `wait N` / `hold … N` count ticks of world time, however long that
  takes. If a thinking limit is set, thinking past it lets the world go on without you until your next action.
- **Who's around.** `players_online` in `obs` is the Tab list (names and teams). Other players show among the
  entities with their `name`, `team` and `teammate`; as in the game, a name tag shows through walls and in the dark up
  to 64 m — unless that player sneaks (so do you, if you'd rather not be spotted).
- **Talking.** `hc say TEXT` (everyone), `hc team TEXT` (your team), `hc tell NAME TEXT` (one player). What others say
  shows in `obs` as `🗨 NAME [team …]: …` lines, and a message wakes you from a wait. By default talking is free and
  instant, even while the world stands still — talk things over as much as you like. If the rules say talking takes
  time, a message takes about as long as saying it out loud, and arrives only as the world runs: keep it short.
- **Only in the game.** Talk through the game's chat and nothing else: no files, no shared folders, no other agent's
  API or `HC_AGENT`. Don't read or touch other agents' folders (`run/client-NAME/`: their memory and logs).
- Everything said is kept (team and private messages too) and read afterwards — that's half the fun of it.

## Rules (what keeps it fair)

Allowed: the agent API (`tools/hc`, port 7070), `tools/hc speed`, `tools/hc pause`, your own scripts using them.

Not allowed — these are the experimenter's tools and would be cheating:
- RCON (`tools/rcon.py`, port 25575) and any server command (`/give`, `/tp`, `/locate`, `/seed`, …)
- the experimenter API on port 7071 (ground truth; switched off when started with `HONEST_DEBUG=0`)
- reading or editing world or server files (`run/server/`), the client's memory files, or the seed
- changing the mods, the configs or the launcher
- `tools/review.py` while a game is on (it shows the truth about the world: it's for reviewing a game afterwards)
- reaching other agents outside the game's chat, or their folders and APIs (see above)

Every session is recorded (`run/server/recordings/`, viewable in ReplayMod; the time the world stood frozen is
left out of the replay's clock, while everything that happened is kept), the client keeps a flight log of every
request and observation, and the server log keeps every command that was run.

## Files

`launcher/mc.py` portable launcher · `mods/` the four mods this package ships · `config/` first-run
templates · `tools/` `hc`, tests (`verify.py`, experimenter-only), RCON · `agent/` example scripts ·
`src/` source of the mods and the Baritone patch · `README.md` the project's history (in Chinese).
