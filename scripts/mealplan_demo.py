"""Agent-client demo for the meal planner: plan a week purely by following
affordances — guard refusals steer the agent, hard stop before the calendar.

    uv run uvicorn mealplan.main:app &     # server on :8000
    uv run python scripts/mealplan_demo.py
"""
from __future__ import annotations

import asyncio
import sys
import uuid
from datetime import date, timedelta

from waymark2.client import AgentClient, PendingConfirmation, Problem

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"
AGENT = {"X-Principal-Id": "claude", "X-Principal-Display": "Claude",
         "X-Principal-Type": "agent"}

# next Tuesday — our week runs Tuesday to Tuesday
TUESDAY = date.today() + timedelta(days=(1 - date.today().weekday()) % 7)
SUNDAY = TUESDAY + timedelta(days=5)

# what a real deployment would get from the LLM: suggestions with recipes
SUGGESTIONS = [
    {"name": "Tacos al pastor", "theme": "mexican", "thaw_hours": 4,
     "prep_minutes": 40, "recipe": "# Tacos al pastor\n\nMarinate the pork…"},
    {"name": "Chicken tikka masala", "theme": "indian", "thaw_hours": 6,
     "prep_minutes": 50, "recipe": "# Tikka masala\n\nToast the spices…"},
]


async def create(agent: AgentClient, collection, body: dict):
    doc = await agent._client.post(collection.actions["create"]["href"], body,
                                   {"Idempotency-Key": uuid.uuid4().hex})
    agent.graph.learn(doc)
    return doc


async def main() -> None:
    agent = AgentClient(BASE, headers=AGENT)
    index = await agent._client.index()
    print(f"→ discovered kinds: {index['kinds']}")

    meals = await agent.fetch(index["collections"]["meal"])
    rotations = await agent.fetch(index["collections"]["rotation"])
    plans = await agent.fetch(index["collections"]["plan"])

    # rotations start inactive; new plans draw from the most recently
    # activated one
    rotation = await create(agent, rotations, {})
    rotation = await agent.act(rotation, "activate")
    print(f"→ {rotation.summary}")

    suggested = [await create(agent, meals, body) for body in SUGGESTIONS]
    print(f"→ suggested: {[m.summary for m in suggested]}")

    # the human accepts both suggestions onto the meal list
    tacos, tikka = [await agent.act(m, "accept") for m in suggested]

    # no rotation_id: the plan auto-selects the active rotation and
    # pre-themes its Sundays from it
    plan = await create(agent, plans, {"start_date": TUESDAY.isoformat()})
    print(f"→ {plan.summary}")
    sunday_theme = next(d["theme"] for d in plan.data["days"]
                        if d["date"] == SUNDAY.isoformat())
    print(f"→ Sunday came pre-themed from the rotation: {sunday_theme}")

    tacos_id = tacos.self_href.rsplit("/", 1)[-1]
    tikka_id = tikka.self_href.rsplit("/", 1)[-1]

    # Tuesday + tacos: on-theme, sails through
    plan = await agent.act(plan, "assign_meal",
                           {"date": TUESDAY.isoformat(), "meal_id": tacos_id})
    print(f"→ assigned tacos to Taco Tuesday ✓")

    # Sunday + tikka: Sunday is pre-themed from the rotation, so an indian
    # meal is refused until the theme says indian — the 409 *is* the
    # unavailable.reason, and it names the remedies
    try:
        await agent.act(plan, "assign_meal",
                        {"date": SUNDAY.isoformat(), "meal_id": tikka_id})
    except Problem as p:
        print(f"→ refused, honestly: {p.detail}")

    plan = await agent.act(plan, "set_sunday_theme",
                           {"date": SUNDAY.isoformat(), "theme": "indian"})
    plan = await agent.act(plan, "assign_meal",
                           {"date": SUNDAY.isoformat(), "meal_id": tikka_id})
    print(f"→ Sunday is indian night: tikka masala ✓")

    # cover the rest of the week and finalize
    for day in (d for d in (TUESDAY + timedelta(days=i) for i in range(7))
                if d not in (TUESDAY, SUNDAY)):
        plan = await agent.act(plan, "mark_eating_out",
                               {"date": day.isoformat()})
    plan = await agent.act(plan, "finalize")
    print(f"→ {plan.summary}")

    # the grocery list follows from the finalized plan
    groceries = await create(agent, await agent.fetch(
        index["collections"]["grocery_list"]),
        {"plan_id": plan.self_href.rsplit("/", 1)[-1]})
    for item in ({"name": "pork shoulder", "quantity": "3 lbs",
                  "category": "meat"},
                 {"name": "corn tortillas", "quantity": "24",
                  "category": "pantry"}):
        groceries = await agent.act(groceries, "add_item", item)
    groceries = await agent.act(groceries, "finalize")
    print(f"→ {groceries.summary}")

    # thaw reminder → calendar is confirm-gated: the mandatory human stop
    task = await create(agent, await agent.fetch(
        index["collections"]["prep_task"]), {
        "plan_id": plan.self_href.rsplit("/", 1)[-1],
        "date": TUESDAY.isoformat(), "meal_name": "Tacos al pastor",
        "task_type": "thaw",
        "due_at": f"{(TUESDAY - timedelta(days=1)).isoformat()}T18:00:00+00:00"})
    pending = await agent.act(task, "schedule",
                              {"calendar_event_id": "gcal-demo-1"})
    assert isinstance(pending, PendingConfirmation)
    print(f"→ HARD STOP before the family calendar: {pending.reason}")
    print(f"   would do: {pending.summary}")

    answer = input("   put it on the calendar? [y/N] ").strip().lower()
    if answer == "y":
        after = await pending.confirm()
        print(f"→ scheduled; task now: {after.summary}")
    else:
        print("→ not confirmed; nothing was invoked")

    await agent.aclose()


if __name__ == "__main__":
    asyncio.run(main())
