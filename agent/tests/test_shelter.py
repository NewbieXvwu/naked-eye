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


def test_a_block_in_the_bag_is_not_a_block_in_the_hand():
    """The cap kept failing with 'clicked 3x, nothing appeared' because the dirt sat in the inventory
    while the hand was on the sword.  hold_item must move it to the hotbar and select it."""
    m = load()
    state = {"selected": 3, "slots": {9: "oak_planks", 20: "dirt"}}
    sent = []

    def fake_obs():
        inv = [{"slot": k, "item": v, "count": 8} for k, v in sorted(state["slots"].items())]
        return {"self": {"inventory": inv, "selected_slot": state["selected"],
                         "pos": [0.5, 64.0, 0.5], "health": 20}, "entities": []}

    def fake_act(q):
        sent.append(dict(q))
        if q.get("type") == "to_hotbar":
            item = state["slots"].pop(q["slot"])
            state["slots"][q["hotbar"]] = item
        elif q.get("type") == "hotbar":
            state["selected"] = q["slot"]
        return {"result": "ok"}

    m.obs = fake_obs
    m.act = fake_act
    assert m.hold_item("dirt") == 0
    kinds = [a.get("type") for a in sent]
    assert kinds == ["to_hotbar", "hotbar"], kinds
    assert sent[0]["slot"] == 20 and sent[0]["hotbar"] == 0


def test_hold_item_leaves_a_hotbar_block_where_it_is():
    m = load()
    state = {"selected": 3, "slots": {2: "dirt"}}
    sent = []

    def fake_obs():
        inv = [{"slot": k, "item": v, "count": 8} for k, v in sorted(state["slots"].items())]
        return {"self": {"inventory": inv, "selected_slot": state["selected"], "pos": [0.5, 64.0, 0.5]},
                "entities": []}

    m.obs = fake_obs
    m.act = lambda q: sent.append(dict(q)) or (state.__setitem__("selected", q["slot"])
                                               if q.get("type") == "hotbar" else None) or {"result": "ok"}
    assert m.hold_item("dirt") == 2
    assert [a.get("type") for a in sent] == ["hotbar"]


def test_hold_item_returns_none_when_the_item_is_missing():
    m = load()
    m.obs = lambda: {"self": {"inventory": [], "selected_slot": 0, "pos": [0.5, 64.0, 0.5]},
                     "entities": []}
    m.act = lambda q: {"result": "ok"}
    assert m.hold_item("dirt") is None
