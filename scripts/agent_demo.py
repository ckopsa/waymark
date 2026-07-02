"""Agent-client demo: accomplish "refund order X" purely by following
affordances — no constructed URLs, hard stop at confirm.

    uv run uvicorn app.main:app &          # server on :8000
    uv run python scripts/agent_demo.py
"""
from __future__ import annotations

import asyncio
import sys
import uuid

from waymark.client import AgentClient, PendingConfirmation

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"
MANAGER = {"X-Principal-Id": "boss", "X-Principal-Roles": "manager",
           "X-Principal-Display": "Boss", "X-Principal-Type": "agent"}

ORDER = {"items": [{"sku": "A-100", "qty": 2, "price": 12.10}],
         "total": 84.20, "currency": "USD"}


async def main() -> None:
    agent = AgentClient(BASE, headers=MANAGER)

    index = await agent._client.index()
    print(f"→ discovered kinds: {index['kinds']}")
    collection = await agent.fetch(index["collections"]["order"])
    print(f"→ {collection.summary}")

    create = collection.actions["create"]
    doc = await agent._client.post(
        create["href"], ORDER, {"Idempotency-Key": uuid.uuid4().hex})
    agent.graph.learn(doc)
    print(f"→ created: {doc.summary}")

    # plan toward 'paid' over the learned effect graph, verifying each step
    while doc.state != "paid":
        plan = agent.plan(doc, "paid")
        if not plan:
            print(f"   no route to 'paid' from {doc.state!r} yet; "
                  f"taking {list(doc.actions)[0]!r} to learn more")
            plan = [list(doc.actions)[0]]
        step = plan[0]
        body = None
        if step == "submit_payment":
            from app.services import VALID_METHOD

            body = {"payment_method_id": str(VALID_METHOD)}
        print(f"→ step: {step} (predicted → "
              f"{doc.actions[step]['effect']['to']})")
        result = await agent.act(doc, step, body)
        assert not isinstance(result, PendingConfirmation)
        doc = result
        print(f"   landed in {doc.state!r} ✓")

    # refund is confirm-gated: dry-run first, then the mandatory human stop
    ok, problem = await agent.dry_run(doc, "refund", {"reason": "demo"})
    print(f"→ dry_run refund: {'valid' if ok else problem}")

    pending = await agent.act(doc, "refund", {"reason": "demo"})
    assert isinstance(pending, PendingConfirmation)
    print(f"→ HARD STOP: {pending.reason}")
    print(f"   would do: {pending.summary}")

    answer = input("   approve refund? [y/N] ").strip().lower()
    if answer == "y":
        after = await pending.confirm()
        print(f"→ refunded; resource now: {after.summary}")
    else:
        print("→ not confirmed; nothing was invoked")

    await agent.aclose()


if __name__ == "__main__":
    asyncio.run(main())
