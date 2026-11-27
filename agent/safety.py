#!/usr/bin/env python3
"""What every macro script must ask before it takes another step.

The world freezes while the brain thinks, so the scripts the brain writes are a cerebellum: they act
many times with no decision in between. That only works while nothing is happening. These are the
rules that make a script hand control back instead of finishing its plan.

Learned the hard way:
  - death #2 (skeleton): a fall took 20 HP to 1, the script answered the alarm with resume:true and
    kept mining coal until a skeleton 15 m away in the dark finished it.
  - death #3 (zombie): the script kept chopping wood while a zombie hit it (18 HP -> 8), and the cap of
    the shelter was opened with a zombie 5 m away, at 6 HP, bare-handed.
  - death #5 (fall): the mod stopped the world with "⏸ PAUSED: FALLING (20 m so far, ground 37 m below)
    — LETHAL". The staircase script looked only at the "result" field, never at "woke", and answered
    the alarm by resuming — so the fall finished. An alarm that says LETHAL is not a pause to walk off.

usage:
    from safety import stop_reason, hostiles
    why = stop_reason(obs)          # a human sentence, or None to carry on
"""
HOSTILE = ("zombie", "skeleton", "creeper", "spider", "enderman", "witch", "drowned", "husk",
           "stray", "pillager", "zombie_villager", "phantom", "slime", "magma_cube", "blaze",
           "ghast", "piglin", "hoglin", "wither_skeleton", "guardian", "elder_guardian")

LOW_HP = 14          # at or below: stop working, eat or retreat
HOSTILE_METRES = 12  # closer than this and the next thing that happens is a hit, not a block


def hostiles(o, within=HOSTILE_METRES):
    """The hostile mobs the agent can perceive within `within` metres, nearest first."""
    close = []
    for e in o.get("entities", []):
        name = (e.get("type") or e.get("name") or "").lower()
        d = e.get("dist")
        if d is not None and d <= within and any(h in name for h in HOSTILE):
            close.append(e)
    return sorted(close, key=lambda e: e.get("dist", 0))


def describe(mobs, n=3):
    return ", ".join(f"{m.get('type')}#{m.get('id')} {m['dist']:.1f} m" for m in mobs[:n])


def res_text(res):
    """What an act told us, in one string — including the reason it woke early.

    The reason an action stopped early lives in the response's "woke" field, not in the task's
    "result" (which is often the *previous* task's text). A script that reads only "result" is blind
    to every alarm: that is exactly how death #5 happened.
    """
    if isinstance(res, dict):
        ctl = (res.get("observation") or {}).get("control") or {}
        parts = [res.get("woke"), ctl.get("last_result"), res.get("result"), res.get("reason")]
        return " ".join(str(p) for p in parts if p)
    return str(res or "")


def stop_reason(o, res="", start_hp=None, within=HOSTILE_METRES):
    """Why the script must stop and give control back (a human sentence), or None to carry on."""
    me = o.get("self", {})
    if me.get("dead"):
        return "dead"
    text = res_text(res)
    r = text.lower()
    if "falling" in r or "lethal" in r:
        return f"FALLING: {text.strip()} — the world is mid-air; do not resume without a water bucket"
    hp = me.get("health")
    if hp is not None:
        if hp <= LOW_HP:
            return f"HP {hp} is too low to work on — eat or save myself first"
        if start_hp is not None and hp < start_hp:
            return f"lost HP ({start_hp} -> {hp}) — something got me"
    if "took damage" in r or "fell" in r:
        return f"took damage: {text}"
    for bad in ("lava", "refused", "won't", "stuck", "blocked", "died", "couldn't dig"):
        if bad in r:
            return res
    close = hostiles(o, within)
    if close:
        return "hostiles near: " + describe(close)
    return None
