"""Members: identity is a resource (design §9).

The pattern that made agent links snap in, applied to people: an admin's
invite is a create into ``invited`` (the outbox can send the email); first
OIDC login binds the IdP ``sub`` to the invited member → ``active``;
deactivation is a transition. Audit trail, generic UI, and conformance
come free — the admin console is the ``members`` collection view.

AuthN is externalized (the OIDC resolver, ``oidc.py``); authZ never is:
the member's ``roles`` are *inputs* to the visibility computation, which
stays in the engine where advertisement and enforcement are one object.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from ..core.actions import Edit, action
from ..core.guards import Guard
from ..core.resource import Resource, filterable, sortable
from ..core.types import Acknowledged, Allow, Ctx, Deny, Safety


class MemberState(StrEnum):
    INVITED = "invited"        # named by an admin; awaiting first login
    ACTIVE = "active"          # bound to an IdP subject; may hold grants
    DEACTIVATED = "deactivated"


class MemberData(BaseModel):
    email: str = Field(max_length=254, pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
                       description="Where the invitation goes; the IdP "
                                   "account it will bind to")
    display_name: str = Field(min_length=1, max_length=80)
    roles: list[str] = Field(
        default_factory=list, max_length=16,
        description="Role tokens visibility templates key on (e.g. admin)")
    # bound at first OIDC login (the resolver's doing, audited as a
    # transition); never supplied by hand
    subject: str | None = Field(
        default=None, max_length=256,
        json_schema_extra={"x-display": {"raw": True}})
    invited_by: str | None = Field(
        default=None, max_length=128,
        json_schema_extra={"x-display": {"raw": True}})


class MemberCreate(MemberData):
    """The invite form: who, as whom, with which roles."""

    subject: SkipJsonSchema[str | None] = None
    invited_by: SkipJsonSchema[str | None] = None


class BindInput(BaseModel):
    subject: str = Field(min_length=1, max_length=256,
                         description="The IdP subject this member logs in as")


async def _member_roles_registered(r: None, inp: MemberCreate,
                                   ctx: Ctx) -> Allow | Deny:
    # a create guard's check grades the validated invite (r=None, design
    # E9); the role registry is the one source of truth — the same
    # _active_role_named grants consult (design §9)
    from .grants import _active_role_named

    unknown = []
    for role in inp.roles or []:
        if not await _active_role_named(ctx, role):
            unknown.append(role)
    unknown = sorted(unknown)
    if unknown:
        return Deny(vars={"roles": ", ".join(unknown)})
    return Allow()


# member roles validate at invite (design §10, closing the v3-notes gap):
# a typo'd role on an invite used to name nobody until a grant failed
# weeks later — now the invite refuses it while the reviewer is looking
roles_registered_at_invite = Guard(
    name="roles_registered", judges=("roles",), reads=("role",),
    explain="No active role named {roles} — register the role first; an "
            "invite naming it would grant nobody.",
    vars=("roles",),
    check=_member_roles_registered,
    remedies=("role.create",),
)


class Member(Resource):
    kind = "member"
    State = MemberState
    Data = MemberData
    Create = MemberCreate

    initial = MemberState.INVITED
    terminal: set = set()  # deactivation is reversible, deliberately

    summary = "{data.display_name} · {data.email} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        email=filterable.Eq,
        subject=filterable.Eq,
    )
    sortable = sortable("email", default="email")

    display = {"title": "Member — {data.display_name}"}

    label_template = "{data.display_name}"

    create_guards = (roles_registered_at_invite,)

    async def on_create(self, ctx: Ctx) -> None:
        self.data.invited_by = ctx.principal.id

    @action(from_=MemberState.INVITED, to=MemberState.ACTIVE,
            input=BindInput,
            edit=Edit(prefill=("subject",), fence=False,
                      unfenced_reason="The binding is written once by the "
                                      "OIDC resolver at first login; there "
                                      "is no human form to clobber."),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Activate", order=1,
                         description="Bind this invitation to the IdP "
                                     "account that just logged in"))
    async def activate(self, inp: BindInput, ctx: Ctx) -> None:
        """The OIDC resolver invokes this on first login — the binding is
        an ordinary transition, so 'who became a member, when, via what'
        is the audit log, not a side table."""
        self.data.subject = inp.subject

    @action(from_=MemberState.ACTIVE, to=MemberState.DEACTIVATED,
            safety=Safety(idempotent=True, reversible=True, confirm=True,
                          consequence="The member can no longer sign in; "
                                      "their delegated grants stop resolving."),
            display=dict(label="Deactivate", style="danger", order=9))
    async def deactivate(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=MemberState.DEACTIVATED, to=MemberState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reactivate", order=1))
    async def reactivate(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={MemberState.INVITED, MemberState.ACTIVE},
            to=MemberState.INVITED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Unbinding only forces a fresh first-login "
                              "binding; membership itself is untouched.")),
            display=dict(label="Reset binding", order=5))
    async def unbind(self, inp: None, ctx: Ctx) -> None:
        self.data.subject = None
