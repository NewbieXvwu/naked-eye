# Third-party components

Naked Eye's own code (everything in this repository except what is listed below) is under the MIT License (`LICENSE`).

| Component | Where | License |
|---|---|---|
| **Baritone** (modified: paths only through what the agent has seen, cheapest adequate tool, never digs with a sword) | `mods/baritone-honest.jar`, `src/mod/libs/baritone-honest.jar`; source: the `baritone` submodule (fork of [cabaletta/baritone](https://github.com/cabaletta/baritone)), also `baritone-honest.patch` | LGPL-3.0 (`licenses/LGPL-3.0.txt`, which builds on `licenses/GPL-3.0.txt`) |
| Fabric Loader, Fabric API, Fabric Language Kotlin | downloaded by `./setup.sh` | Apache-2.0 |
| Sponge Mixin (Fabric's fork) | downloaded by `./setup.sh` | MIT |
| ServerReplay | downloaded by `./setup.sh` | MIT |
| Eclipse Temurin (Java 25) | downloaded by `./setup.sh` if no Java 25 is installed | GPL-2.0 with Classpath Exception |

The honest client and the server mod talk to Baritone only through its interfaces; they are an "Application" in the
LGPL's sense, not a modification of it. The modified Baritone itself stays LGPL-3.0, with its full source in the fork.

**Minecraft** is Mojang's. Nothing of the game is in this repository: `./setup.sh` downloads it from Mojang. The offline
package that `tools/make_packages.sh` builds does contain game files — keep it for your own use, don't publish it.
Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.
