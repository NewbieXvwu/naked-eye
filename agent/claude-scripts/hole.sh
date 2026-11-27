#!/bin/bash
# hole.sh — thin alias kept for old notes: the real script is agent/shelter.py, which verifies each step.
# The old version here dug the column and then let Baritone "walk" to the bottom; it stayed on the rim,
# the dirt drops stayed in the hole, `place` had nothing, and a skeleton shot it from 16 HP to 0
# (2026-11-27 05:51). Panic shelter: dig 3 down, get in, cap the top.
exec python3 "$(dirname "$0")/../shelter.py" "$@"
