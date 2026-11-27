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


# --- death #6: the roof of the spawn cavern ----------------------------------------------------

def test_the_spawn_cavern_box_is_where_the_cavern_was_seen():
    from safety import in_spawn_cavern
    assert in_spawn_cavern(72, -6)      # death #5
    assert in_spawn_cavern(94, -16)     # death #6
    assert in_spawn_cavern(80, -35)     # death #2 cave-in area
    assert not in_spawn_cavern(200, -16)
    assert not in_spawn_cavern(94, -200)


# --- death #7: under the sea, the aquifer is not something to dig through ------------------------

def test_a_drowning_wake_stops_the_script_before_the_next_dig():
    res = {"result": "ok",
           "woke": "hurt (13 -> 11 HP) — drowning",
           "observation": {"control": {"last_result": "mine south x16 -> ok (woke: running out of air)"},
                           "self": {"health": 11, "dead": False}, "entities": []}}
    why = stop_reason(obs(11), res)
    assert why and "DROWNING" in why


def test_running_out_of_air_is_a_stop_not_a_warning():
    res = {"result": "ok",
           "woke": "running out of air",
           "observation": {"control": {"last_result": "head underwater, air 52%"},
                           "self": {"health": 20, "dead": False}, "entities": []}}
    assert "DROWNING" in stop_reason(obs(20), res)


def test_three_bubbles_left_is_already_too_late():
    o = obs(20)
    o["self"]["air_bubbles"] = 3
    why = stop_reason(o, {"result": "ok"})
    assert why and "air" in why.lower()


def test_water_breach_is_recognised_from_the_action_text():
    from safety import water_breach
    assert water_breach({"result": "tunnel north x16 -> liquid (water) where the next step goes"})
    assert water_breach({"result": "staircase -> done", "woke": "splashing"})
    assert not water_breach({"result": "staircase down east x12 -> done, 12 steps"})


def test_near_water_on_a_shore_with_the_sea_in_view():
    from safety import near_water
    o = {"self": {"biome": "stony_shore"}, "blocks_in_view": [{"block": "water"}]}
    assert near_water(o)
    dry = {"self": {"biome": "forest"}, "blocks_in_view": [{"block": "stone"}]}
    assert not near_water(dry)


def test_a_drowned_in_the_water_is_not_something_to_fight():
    """death #8: 5 HP, in the sea, kelp in sight, a drowned fifteen seconds away by sound."""
    o = {"self": {"health": 20, "in_water": True},
         "entities": [{"id": 4453, "type": "drowned", "dist": 10.2}]}
    why = stop_reason(o)
    assert why and "water" in why and "drowned" in why


def test_hurt_in_water_means_swim_to_land():
    o = {"self": {"health": 5, "in_water": True}, "entities": []}
    why = stop_reason(o)
    assert why and "land" in why


def test_a_drowned_on_dry_land_does_not_stop_ordinary_work():
    o = {"self": {"health": 20, "in_water": False},
         "entities": [{"id": 9, "type": "drowned", "dist": 19.0}]}
    assert stop_reason(o) is None
