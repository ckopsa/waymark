"""A compact Order-like resource used by core unit tests (no services needed)."""
from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum

from pydantic import AwareDatetime, BaseModel, Field

from waymark import Allow, Deny, Resource, action, filterable, guard, sortable


class TicketState(StrEnum):
    OPEN = "open"
    ASSIGNED = "assigned"
    RESOLVED = "resolved"
    CLOSED = "closed"


class TicketData(BaseModel):
    title: str
    priority: int = Field(ge=1, le=5, default=3)
    assignee: str | None = None
    opened_at: AwareDatetime | None = None


class AssignInput(BaseModel):
    assignee: str


@guard(else_="Assignee {assignee} is not on the team.", vars=["assignee"])
async def assignee_on_team(r, inp: AssignInput, ctx):
    if inp.assignee in ("alice", "bob"):
        return Allow()
    return Deny(vars={"assignee": inp.assignee}, errors={"assignee": ["unknown"]})


@guard(else_="Only low-priority tickets can be closed without resolution.")
async def low_priority(r, inp, ctx):
    return Allow() if r.data.priority <= 2 else Deny()


manager_only = guard.role("manager")


class Ticket(Resource):
    kind = "ticket"
    State = TicketState
    Data = TicketData

    initial = TicketState.OPEN
    terminal = {TicketState.CLOSED}

    summary = "Ticket {id} · {data.title} · p{data.priority} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        priority=filterable.Range,
        opened_at=filterable.After,
    )
    sortable = sortable("opened_at", "priority", default="-opened_at")

    @action(from_=TicketState.OPEN, to=TicketState.ASSIGNED,
            input=AssignInput, guards=[assignee_on_team],
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Assign", style="primary", order=1))
    async def assign(self, inp: AssignInput, ctx) -> None:
        self.data.assignee = inp.assignee

    @action(from_=TicketState.ASSIGNED, to=TicketState.OPEN,
            idempotent=True, reversible=True, confirm=False)
    async def unassign(self, inp: None, ctx) -> None:
        self.data.assignee = None

    @action(from_=TicketState.ASSIGNED, to=TicketState.RESOLVED,
            idempotent=True, reversible=False, confirm=False,
            requires_if_match=True)
    async def resolve(self, inp: None, ctx) -> None:
        pass

    @action(from_={TicketState.OPEN, TicketState.RESOLVED},
            to=TicketState.CLOSED,
            guards=[low_priority | manager_only],
            idempotent=False, reversible=False, confirm=True,
            display=dict(label="Close", style="danger"))
    async def close(self, inp: None, ctx) -> None:
        pass


def make_ticket(state: str = "open", **data) -> Ticket:
    defaults = dict(title="Broken build", priority=3,
                    opened_at=datetime(2026, 7, 1, 9, tzinfo=UTC))
    defaults.update(data)
    return Ticket(id="t-1", state=state, data=TicketData(**defaults), version=1,
                  created_at=datetime(2026, 7, 1, 9, tzinfo=UTC),
                  updated_at=datetime(2026, 7, 1, 9, tzinfo=UTC))
