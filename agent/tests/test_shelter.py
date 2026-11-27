"""Tests for the shelter scripts (no game needed: pure functions only).

Run: python3 -m pytest agent/tests -q
"""
import importlib.util
import pathlib

SHELTER = pathlib.Path(__file__).resolve().parents[1] / "shelter.py"


def load():
    spec = importlib.util.spec_from_file_location("shelter", SHELTER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_cap_cell_is_above_the_players_own_head():
    m = load()
    # player ended standing at (80,110,-36) after digging; first failure tried (80,111,-36)
    assert m.cap_cell(80, 110, -36) == (80, 112, -36)
    assert m.cap_cell(64, 132, 8) == (64, 134, 8)


def test_cap_cell_is_never_the_cell_the_player_stands_in():
    m = load()
    feet = (80, 110, -36)
    cap = m.cap_cell(*feet)
    assert cap != feet
    assert cap[1] == feet[1] + 2
    assert cap[0] == feet[0] and cap[2] == feet[2]
