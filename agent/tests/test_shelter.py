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


def test_an_action_the_game_refused_because_of_an_alarm_is_sent_again_with_resume():
    m = load()
    sent = []

    def fake_call(path, data=None, timeout=180):
        sent.append(dict(data or {}))
        if not (data or {}).get("resume"):
            return {"error": "refused: the game is PAUSED (hurt 5 -> 2 HP — hit by something off-screen)"
                             " — look, decide, and send an action with resume=true"}
        return {"result": "ok"}

    m.call = fake_call
    out = m.act({"type": "dig", "x": 1, "y": 2, "z": 3})
    assert out["result"] == "ok"
    assert sent[0].get("resume") is None and sent[1]["resume"] is True


def test_shelter_says_it_failed_when_the_player_never_leaves_the_surface():
    """Death #9: every dig was refused while the world was paused, the player never moved a block down,
    and the script still printed 'capped'.  It must report the failure instead."""
    m = load()
    surface = {"self": {"pos": [91.4, 135.0, -0.6], "health": 5, "dead": False, "inventory": []},
               "entities": []}

    def fake_call(path, data=None, timeout=180):
        if path == "/observe":
            return surface
        return {"result": "ok"}

    m.call = fake_call
    m.obs = lambda: surface
    assert m.main(force=True) == 1
