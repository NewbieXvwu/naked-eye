"""Tests for agent/probe.py — looking down the hole before stepping in.

Death #4: the spawn hill is the roof of an open cavern. `hc stairs north 7` dug one block, stepped
forward and fell from y135 to y100. The crosshair, pointed down into the hole, is the honest way to
see the void.
"""
import os
import sys

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))

import probe  # noqa: E402


def obs(text):
    return {"text": text}


def test_sees_the_floor_of_the_hole():
    o = obs("at [73, 135, -4]  day  light 15\ncrosshair: cobblestone@[73, 133, -4]\n")
    assert probe.crosshair(o) == "cobblestone@[73, 133, -4]"
    assert probe.floor_in_hole(o) is True


def test_nothing_under_means_void():
    o = obs("at [73, 135, -4]  day  light 15\ncrosshair: nothing\n")
    assert probe.floor_in_hole(o) is False


def test_missing_line_is_not_a_floor():
    assert probe.floor_in_hole(obs("at [73, 135, -4]  day\n")) is False
