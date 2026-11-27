"""Tests for the staircase's stop rules (agent/descend.py:stop_reason).

Death #2: a 13-block fall took 20 HP to 1, the script answered the mod's pause with
resume:true, kept digging, and a skeleton 15 m away in the dark shot the last hit point off.
These tests pin the rules that would have stopped it.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from descend import LOW_HP, HOSTILE_METRES, stop_reason  # noqa: E402


def obs(hp=20, dead=False, entities=()):
    return {"self": {"health": hp, "dead": dead}, "entities": list(entities)}


def mob(kind, dist, id_=1):
    return {"type": kind, "name": kind, "dist": dist, "id": id_}


def test_healthy_shaft_keeps_digging():
    assert stop_reason(obs(20)) is None


def test_dead_is_not_a_decision():
    assert stop_reason(obs(0, dead=True)) == "dead"


def test_low_hp_stops_the_shaft():
    why = stop_reason(obs(LOW_HP))
    assert why and "HP" in why


def test_losing_hp_stops_even_when_still_comfortable():
    # 20 -> 17 is not dangerous yet, but it means something is happening: deal with it first.
    why = stop_reason(obs(17), start_hp=20)
    assert why and "lost HP" in why


def test_a_fall_is_a_stop():
    # The 13-block fall of death #2: 20 HP to 1. Either rule may speak first; both mean "stop".
    why = stop_reason(obs(1), res="staircase down north x16 -> took damage (20 -> 1 HP) after 0 steps")
    assert why
    # A smaller hit at healthy HP still stops the shaft, and says why.
    why = stop_reason(obs(18), res="took damage (20 -> 18) — I fell", start_hp=20)
    assert why and ("took damage" in why or "lost HP" in why)
    # And with no HP information at all, the wake reason alone must still stop it.
    why = stop_reason(obs(None), res="took damage (20 -> 18) — I fell")
    assert why and "took damage" in why


def test_a_hostile_close_by_stops_the_shaft():
    why = stop_reason(obs(20, entities=[mob("skeleton", HOSTILE_METRES - 1)]))
    assert why and "hostile" in why


def test_a_hostile_far_away_does_not():
    assert stop_reason(obs(20, entities=[mob("skeleton", HOSTILE_METRES + 10)])) is None


def test_animals_do_not_stop_it():
    assert stop_reason(obs(20, entities=[mob("chicken", 3), mob("cow", 1)])) is None


def test_no_floor_is_left_to_the_plugging_branch():
    # The loop plugs the hole and carries on; only an unpluggable void stops it.
    assert stop_reason(obs(20), res="no floor under the next step (73, 122, -2 is air)") is None
