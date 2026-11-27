"""Tests for the rules every macro script shares (agent/safety.py) and the way out of a shelter.

Deaths #2 and #3 both happened because a script kept working while something was happening to it.
"""
import os
import sys

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "..", "..", "agent"))

from safety import HOSTILE_METRES, LOW_HP, hostiles, stop_reason  # noqa: E402
import shelter  # noqa: E402


def obs(hp=20, dead=False, entities=(), inventory=()):
    return {"self": {"health": hp, "dead": dead, "inventory": list(inventory)},
            "entities": list(entities)}


def mob(kind, dist, id_=1):
    return {"type": kind, "name": kind, "dist": dist, "id": id_}


# --- the shared rules -------------------------------------------------------------------------

def test_quiet_situation_lets_the_script_work():
    assert stop_reason(obs(20)) is None


def test_low_hp_stops_work():
    assert "HP" in stop_reason(obs(LOW_HP))


def test_losing_hp_stops_work_even_at_19_out_of_20():
    assert "lost HP" in stop_reason(obs(19), start_hp=20)


def test_a_hostile_closer_than_the_limit_stops_work():
    assert "hostile" in stop_reason(obs(20, entities=[mob("zombie", HOSTILE_METRES - 2)]))


def test_the_same_hostile_further_away_does_not():
    assert stop_reason(obs(20, entities=[mob("zombie", HOSTILE_METRES + 2)])) is None


def test_hostiles_are_listed_nearest_first():
    got = hostiles(obs(20, entities=[mob("skeleton", 9), mob("zombie", 3)]))
    assert [m["type"] for m in got] == ["zombie", "skeleton"]


def test_animals_are_not_hostiles():
    assert stop_reason(obs(20, entities=[mob("chicken", 2), mob("cow", 4)])) is None


def test_death_is_reported_as_death():
    assert stop_reason(obs(0, dead=True)) == "dead"


# --- the way out of a shelter (death #3) ------------------------------------------------------

def test_cap_stays_on_while_a_zombie_stands_outside():
    why = shelter.exit_reason(obs(20, entities=[mob("zombie", 5)]))
    assert why and "zombie" in why


def test_cap_stays_on_at_six_hp_in_the_open():
    assert shelter.exit_reason(obs(6)) is not None


def test_cap_comes_off_when_the_coast_is_clear():
    assert shelter.exit_reason(obs(20, entities=[mob("chicken", 3)])) is None


# --- death #5: the alarm that says LETHAL -----------------------------------------------------

def test_a_falling_wake_stops_the_script_even_with_full_hp():
    woke = "PAUSED: FALLING (20 m so far, ground 37 m below) — LETHAL: the landing will kill me"
    why = stop_reason(obs(20), {"result": "ok", "woke": woke})
    assert why and "FALLING" in why


def test_the_wake_is_read_from_the_response_not_the_stale_result():
    # exactly the shape sent back by /act: the task text is the previous task's, the alarm is in "woke"
    res = {"result": "ok",
           "woke": "PAUSED: FALLING (20 m so far, ground 37 m below) — LETHAL",
           "observation": {"control": {"last_result": "baritone go 73, 134, -8 -> arrived"},
                           "self": {"health": 20, "dead": False}, "entities": []}}
    assert "FALLING" in stop_reason(obs(20), res)


def test_a_quiet_response_does_not_stop_anything():
    res = {"result": "ok", "woke": None,
           "observation": {"control": {"last_result": "staircase down south x8 -> done"}}}
    assert stop_reason(obs(20), res) is None
