"""Roles: policy names are resources (design §9).

A ``Role`` template's *meaning* (what a role's visibility covers) lives in
grants; the role *registry* closes the silent-grant gap the v3 notes
recorded: a grant to a name nobody holds — or nobody spelled the same way
twice — used to grant nobody, silently. Now the name must exist here,
active, before a grant may name it; who is admin stays auditable, what
"admin" means stays reviewable.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from ..core.actions import action
from ..core.resource import Resource, filterable, sortable
from ..core.types import Ctx, Safety


class RoleState(StrEnum):
    ACTIVE = "active"
    RETIRED = "retired"


class RoleData(BaseModel):
    # the token grants and principals key on — one spelling, registered
    name: str = Field(min_length=1, max_length=40,
                      pattern=r"^[a-z][a-z0-9_-]{0,39}$",
                      description="The role token grants name as their "
                                  "holder (e.g. admin, reader)")
    description: str | None = Field(
        default=None, max_length=240,
        description="What holding this role is supposed to mean")


class Role(Resource):
    kind = "role"
    State = RoleState
    Data = RoleData

    initial = RoleState.ACTIVE
    terminal: set = set()  # retirement is reversible, deliberately

    summary = "{data.name} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        name=filterable.Eq,
    )
    sortable = sortable("name", default="name")
    # one spelling per role (design E2): a second "reader" would split the
    # grant surface silently
    unique = ("name",)

    display = {"title": "Role — {data.name}"}

    label_template = "{data.name}"

    @action(from_=RoleState.ACTIVE, to=RoleState.RETIRED,
            safety=Safety(idempotent=True, reversible=True, confirm=True,
                          consequence="New grants can no longer name this "
                                      "role; existing role-held grants stop "
                                      "amending until it is reactivated."),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=RoleState.RETIRED, to=RoleState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reactivate", order=1))
    async def reactivate(self, inp: None, ctx: Ctx) -> None:
        pass
