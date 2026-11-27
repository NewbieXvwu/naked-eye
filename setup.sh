#!/bin/sh
# Download what this machine needs (Java 25 if missing, Minecraft 26.3, Fabric, mods) into .cache/.
# The offline package already has it all: this then just checks, without touching the network.
cd "$(dirname "$0")" && exec python3 launcher/mc.py setup "$@"
