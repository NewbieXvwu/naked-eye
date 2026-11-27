#!/usr/bin/env python3
"""The address of *my* client, not somebody else's.

Every script here used to hardcode http://127.0.0.1:7070, which is the lead agent's
API. A teammate that ran `agent/kelp.py` then swam the lead's body while it thought it
was swimming its own - it only found out because the inventory it was reading was not
its own. The name of the client is HC_AGENT (the same variable tools/hc uses), and
tools/ports.py knows every port in this world.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

NAME = os.environ.get("HC_AGENT", "Agent")
try:
    import ports as _ports
    PORT = _ports.agent_api(NAME)
except Exception:  # no run/ports.json yet (a fresh clone): tools/hc's default is 7070
    PORT = 7070

API = f"http://127.0.0.1:{PORT}"
