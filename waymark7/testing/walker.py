"""--waymark7-walk: random-walk each resource's *real* transitions from its
initial state, asserting the core invariants at every step. The declared
state machine is its own test oracle.

Seeded and deterministic; each walk drives the HTTP surface with the agent
client's discipline: only advertised actions, never constructed URLs.
"""
from __future__ import annotations

import random
import uuid

import pytest

from .conformance import H, build_input, fail_if_needs_example, fetch, make

pytestmark = pytest.mark.asyncio

WALKS = 5
MAX_STEPS = 12


async def test_walk(wm7, wm3_kind):
    kind = wm3_kind
    rdef = wm7.registry[kind]
    machine = rdef.cls.__waymark_machine__
    seed = int(wm7.engine.__dict__.get("waymark_walk_seed", 0))

    for walk in range(WALKS):
        rng = random.Random(f"{seed}:{kind}:{walk}")  # stable across processes
        instance = await make(wm7, kind, machine.initial)
        pname = rng.choice(sorted(wm7.principals))
        doc = await fetch(wm7, kind, instance.id, pname)

        for step in range(MAX_STEPS):
            # invariant: the affordance partition is complete at every step
            declared = {n for n, d in machine.actions.items() if not d.bulk}
            assert set(doc["actions"]) | set(doc["unavailable"]) <= declared

            if not doc["actions"]:
                assert doc["state"] in machine.terminal or \
                    doc["unavailable"], \
                    f"non-terminal dead end reached at {doc['state']}"
                break

            action = rng.choice(sorted(doc["actions"]))
            defn = machine.actions[action]
            async with wm7.storage.session() as s:
                current = await wm7.storage.load(s, kind, instance.id)
            body = await build_input(wm7, kind, defn, current)
            headers = H(pname)
            if defn.safety.fence:
                headers["If-Match"] = doc["meta"]["etag"]
            if not defn.safety.idempotent:
                headers["Idempotency-Key"] = uuid.uuid4().hex
            res = await wm7.client.post(f"{doc['self']}/-/{action}",
                                        json=body, headers=headers)
            fail_if_needs_example(res, kind, defn)
            assert res.status_code == 200, \
                f"walk {walk} step {step}: advertised {action} from " \
                f"{doc['state']} refused: {res.text}"
            after = res.json()
            # invariant: effects are state transitions, exactly as declared
            assert after["state"] == defn.to
            assert after["meta"]["version"] >= doc["meta"]["version"]
            doc = after
