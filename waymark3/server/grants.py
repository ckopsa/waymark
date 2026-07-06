"""Agent links: least-privilege access, negotiated as ordinary Waymark.

The design (the user's, verbatim in spirit): a human mints an **agent
link** and hands its token to an LLM (or any) agent. By default the token
grants *nothing* — the agent can read the public catalog (discovery,
schemas, docs) and its own grant resource, and that's all. Given its task,
the agent requests the minimum it thinks it needs:

- per data **field**: view ``clear``, view ``hashed``, or (default) the
  field simply doesn't exist for it;
- per **affordance**: ``open``, ``approval`` (each invocation becomes a
  pending approval a human executes or rejects), or (default) ``none`` —
  rendered honestly as ``unavailable`` with the remedy pointing back at
  the grant;
- per affordance **argument**: ``edit``, ``approval``, or ``none`` — a
  required argument the agent can't supply routes the whole invocation to
  a human, who fills it at approval time;
- plus a **duration**; expiry returns everything to none.

Everything here is dogfood, which is the entire trick:

- the grant is a resource (``draft → requested ⇄ granted → revoked``), so
  "generated agent links along with their approval status" is just the
  ``grants`` collection in the generic UI, approvals are
  confirm-gated actions with declared consequences, every negotiation
  step is in the audit log, and a human can *follow* the agent through
  its own permission request;
- an approval-mode invocation is a resource too
  (``pending → approved → closed | rejected``): the 202 hands the agent
  the approval's envelope, the human approves (optionally supplying the
  arguments the agent couldn't), and ``run`` executes the stored
  invocation through ``ctx.invoke`` — the same transition algorithm as
  everything else, one accountable audit entry, actor = whoever ran it.

Default-deny is *rendering* — and in 3.0 it is rendering at the source
(design §1): :class:`GrantVisibility` is consulted by the projector while
building the envelope, so an ungrated field isn't dimmed, locked, or
redacted after the fact — it is never rendered. v2's ``apply_scope``
post-hoc rewriter is gone; the advertisement and the enforcement stay one
thing.
"""
from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any, Literal

from pydantic import AwareDatetime, BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from ..core.actions import Edit, action
from ..core.guards import Guard
from ..core.resource import Resource, filterable
from ..core.types import Acknowledged, Allow, Ctx, Deny, Safety

TOKEN_PREFIX = "wmk_"

FieldMode = Literal["clear", "hashed", "hidden"]
ActionMode = Literal["open", "approval", "none"]
ArgMode = Literal["edit", "approval", "none"]

FieldMap = dict[str, dict[str, FieldMode]]          # kind → field → mode
ActionMap = dict[str, dict[str, ActionMode]]        # kind → action → mode
ArgMap = dict[str, dict[str, dict[str, ArgMode]]]   # kind → action → arg → mode
OverMap = dict[str, list[str]]                      # kind → resource ids (selector)


def _mint_token() -> str:
    return TOKEN_PREFIX + uuid.uuid4().hex + uuid.uuid4().hex


# ── the grant resource ──────────────────────────────────────────────────
class GrantState(StrEnum):
    DRAFT = "draft"          # link minted; the agent has the catalog, nothing else
    REQUESTED = "requested"  # the agent stated its task and its minimum ask
    GRANTED = "granted"      # a human approved; enforcement reads granted_* maps
    REVOKED = "revoked"


class GrantData(BaseModel):
    holder_name: str = Field(min_length=1, max_length=40,
                             description="Who this grant is for (shown in "
                                         "feeds and approvals)")
    # one grant kind, three holders (design §9): a token principal (an
    # agent link), a member, or every holder of a role
    holder_kind: Literal["token", "member", "role"] = "token"
    holder_id: str | None = Field(
        default=None, max_length=128,
        description="The member id or role name this grant is held by "
                    "(token grants leave it blank; the token IS the holder)")
    token: str | None = Field(default=None,
                              json_schema_extra={"x-display": {"hidden": True}})
    # the actor id this link's holder acts under — so a supervisor can
    # follow the agent from the moment the link exists, before it acts
    agent_principal: str | None = Field(
        default=None, max_length=64,
        description="The principal this agent acts under (for following)",
        json_schema_extra={"x-display": {"raw": True}})
    task: str | None = Field(default=None, max_length=240,
                             description="The agent's stated task — why it "
                                         "needs what it requests")
    # what the agent asked for (visible to the approving human)
    requested_fields: FieldMap = Field(default_factory=dict)
    requested_actions: ActionMap = Field(default_factory=dict)
    requested_args: ArgMap = Field(default_factory=dict)
    # instance selector (design §9): kind → resource ids this grant is
    # narrowed to; an unlisted kind is kind-level. "Edit access to THIS
    # meal plan" is a selector, not a new mechanism.
    requested_over: OverMap = Field(default_factory=dict)
    requested_hours: int = Field(default=24, ge=1, le=720,
                                 description="How long the access should last")
    # what enforcement reads (copied from requested_* by `approve`); during
    # an amendment (granted → requested) the old grant stays in force
    granted_fields: FieldMap = Field(default_factory=dict)
    granted_actions: ActionMap = Field(default_factory=dict)
    granted_args: ArgMap = Field(default_factory=dict)
    granted_over: OverMap = Field(default_factory=dict)
    # who approved (design §9): a member approver becomes the grant's
    # attenuation ceiling — the holder's view can never exceed the
    # approver's, live
    approved_by: str | None = Field(
        default=None, max_length=128,
        json_schema_extra={"x-display": {"raw": True}})
    expires_at: AwareDatetime | None = None


class GrantCreate(GrantData):
    """Mint a grant: name the holder (an agent gets a token; a member or
    role is named by id). Everything else is the holder's to request and
    a person's to approve."""

    token: SkipJsonSchema[str | None] = None
    approved_by: SkipJsonSchema[str | None] = None
    agent_principal: SkipJsonSchema[str | None] = None
    task: SkipJsonSchema[str | None] = None
    requested_fields: SkipJsonSchema[FieldMap] = Field(default_factory=dict)
    requested_actions: SkipJsonSchema[ActionMap] = Field(default_factory=dict)
    requested_args: SkipJsonSchema[ArgMap] = Field(default_factory=dict)
    requested_over: SkipJsonSchema[OverMap] = Field(default_factory=dict)
    requested_hours: SkipJsonSchema[int] = 24
    granted_fields: SkipJsonSchema[FieldMap] = Field(default_factory=dict)
    granted_actions: SkipJsonSchema[ActionMap] = Field(default_factory=dict)
    granted_args: SkipJsonSchema[ArgMap] = Field(default_factory=dict)
    granted_over: SkipJsonSchema[OverMap] = Field(default_factory=dict)
    expires_at: SkipJsonSchema[AwareDatetime | None] = None


class RequestAccessInput(BaseModel):
    task: str = Field(min_length=1, max_length=240,
                      description="What you are trying to accomplish")
    requested_fields: FieldMap = Field(
        default_factory=dict,
        description="kind → field → clear|hashed|hidden (unlisted = hidden)")
    requested_actions: ActionMap = Field(
        default_factory=dict,
        description="kind → action → open|approval|none (unlisted = none)")
    requested_args: ArgMap = Field(
        default_factory=dict,
        description="kind → action → argument → edit|approval|none")
    requested_over: OverMap = Field(
        default_factory=dict,
        description="kind → resource ids: narrow this grant to specific "
                    "resources (unlisted kinds stay kind-level)")
    requested_hours: int = Field(default=24, ge=1, le=720)


async def _not_the_holder(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
    if getattr(ctx.principal, "scope", None) is not None:
        return Deny()  # a token holder never judges its own access
    data = r.data
    if hasattr(data, "holder_kind"):        # the grant itself
        holder_kind, holder_id = data.holder_kind, data.holder_id
    else:                                    # an approval names its grant
        grant = await ctx.read("grant", data.grant_id)
        if grant is None:
            return Allow()
        holder_kind, holder_id = grant.data.holder_kind, grant.data.holder_id
    if holder_kind == "member" and _holder_match(holder_id, ctx.principal.id):
        return Deny()
    return Allow()

no_self_dealing = Guard(
    name="no_self_dealing", reads=("principal", "grant"),
    explain="A holder cannot judge its own access; someone else approves it.",
    check=_not_the_holder,
)


class Grant(Resource):
    kind = "grant"
    State = GrantState
    Data = GrantData
    Create = GrantCreate

    initial = GrantState.DRAFT
    terminal = {GrantState.REVOKED}

    summary = "Grant for {data.holder_name} · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In,
                            token=filterable.Eq,
                            holder_kind=filterable.Eq,
                            holder_id=filterable.Eq)

    display = {"title": "Grant — {data.holder_name}"}

    async def on_create(self, ctx: Ctx) -> None:
        if self.data.holder_kind == "token":
            # the token resolves to this exact actor id
            # (engine._token_principal); naming it lets any client offer
            # "follow this agent" from the moment the link exists
            if not self.data.token:
                self.data.token = _mint_token()
            self.data.agent_principal = f"agent-link-{self.id[:8]}"

    @action(from_={GrantState.DRAFT, GrantState.GRANTED},
            to=GrantState.REQUESTED,
            input=RequestAccessInput,
            edit=Edit(prefill=("task", "requested_fields",
                               "requested_actions", "requested_args",
                               "requested_hours")),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Request access", style="primary", order=1,
                         description="State the task and the minimum access "
                                     "it needs; a person will review it."))
    async def request_access(self, inp: RequestAccessInput, ctx: Ctx) -> None:
        self.data.task = inp.task
        self.data.requested_fields = inp.requested_fields
        self.data.requested_actions = inp.requested_actions
        self.data.requested_args = inp.requested_args
        self.data.requested_over = inp.requested_over
        self.data.requested_hours = inp.requested_hours

    @action(from_=GrantState.REQUESTED, to=GrantState.GRANTED,
            guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=True, confirm=True,
                          consequence="The agent gets exactly the requested "
                                      "access, for the requested duration."),
            display=dict(label="Approve access", style="primary", order=1))
    async def approve(self, inp: None, ctx: Ctx) -> None:
        self.data.approved_by = ctx.principal.id
        self.data.granted_fields = dict(self.data.requested_fields)
        self.data.granted_actions = dict(self.data.requested_actions)
        self.data.granted_args = dict(self.data.requested_args)
        self.data.granted_over = dict(self.data.requested_over)
        self.data.expires_at = ctx.now + timedelta(
            hours=self.data.requested_hours)

    @action(from_=GrantState.REQUESTED, to=GrantState.DRAFT,
            guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Send back", order=2,
                         description="Decline this request; the agent may "
                                     "request differently. Any previously "
                                     "granted access is withdrawn."))
    async def deny(self, inp: None, ctx: Ctx) -> None:
        self.data.granted_fields = {}
        self.data.granted_actions = {}
        self.data.granted_args = {}
        self.data.granted_over = {}
        self.data.expires_at = None

    @action(from_={GrantState.DRAFT, GrantState.REQUESTED,
                   GrantState.GRANTED},
            to=GrantState.REVOKED,
            guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The link goes dead immediately and "
                                      "for good; the agent keeps nothing."),
            display=dict(label="Revoke link", style="danger", order=9))
    async def revoke(self, inp: None, ctx: Ctx) -> None:
        self.data.granted_fields = {}
        self.data.granted_actions = {}
        self.data.granted_args = {}
        self.data.granted_over = {}


# ── the approval resource (an "access via approval" invocation) ────────
class ApprovalState(StrEnum):
    PENDING = "pending"
    APPROVED = "approved"
    CLOSED = "closed"      # ran (outcome recorded either way)
    REJECTED = "rejected"


class ApprovalData(BaseModel):
    grant_id: str = Field(json_schema_extra={"x-display": {
        "widget": "resource", "kind": "grant"}})
    agent_principal: str = Field(max_length=128, json_schema_extra={
        "x-display": {"hidden": True}})
    holder_name: str = Field(max_length=40)
    title: str = Field(max_length=80,
                       description="What the agent wants to do, in words")
    target_kind: str = Field(max_length=64, json_schema_extra={
        "x-display": {"hidden": True}})
    target_id: str = Field(max_length=64, json_schema_extra={
        "x-display": {"hidden": True}})
    target_action: str = Field(max_length=128, json_schema_extra={
        "x-display": {"raw": True}})
    target_href: str = Field(max_length=300, json_schema_extra={
        "x-display": {"raw": True}})
    target_input: dict[str, Any] = Field(default_factory=dict)
    missing: list[str] = Field(
        default_factory=list,
        description="Required arguments the agent may not supply; fill them "
                    "when approving")
    outcome: str | None = Field(default=None, max_length=240)
    result_state: str | None = Field(default=None, max_length=64,
                                     json_schema_extra={"x-display": {
                                         "raw": True}})


class ApproveInput(BaseModel):
    overrides: dict[str, Any] = Field(
        default_factory=dict,
        description="Arguments to set or correct — including any the agent "
                    "was not allowed to supply")


class ApprovalRequest(Resource):
    kind = "approval_request"
    State = ApprovalState
    Data = ApprovalData

    initial = ApprovalState.PENDING
    terminal = {ApprovalState.CLOSED, ApprovalState.REJECTED}

    summary = "{data.holder_name} asks: {data.title} · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In,
                            grant_id=filterable.Eq)

    display = {"title": "Approval — {data.title}"}

    @action(from_=ApprovalState.PENDING, to=ApprovalState.APPROVED,
            input=ApproveInput, guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The agent's stored invocation becomes "
                                      "runnable exactly once, with your "
                                      "argument overrides applied."),
            display=dict(label="Approve", style="primary", order=1))
    async def approve(self, inp: ApproveInput, ctx: Ctx) -> None:
        self.data.target_input = {**self.data.target_input, **inp.overrides}
        still = [m for m in self.data.missing if m not in inp.overrides]
        self.data.missing = still

    @action(from_=ApprovalState.PENDING, to=ApprovalState.REJECTED,
            guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The stored invocation is discarded "
                                      "and will not run."),
            display=dict(label="Reject", style="danger", order=9))
    async def reject(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=ApprovalState.APPROVED, to=ApprovalState.CLOSED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Runs exactly what a human just approved; the "
                              "outcome is recorded on this resource either "
                              "way.")),
            display=dict(label="Run", style="primary", order=1))
    async def run(self, inp: None, ctx: Ctx) -> None:
        """Execute the stored invocation through the ordinary machinery —
        same transaction, same audit trail; the actor is whoever ran it."""
        if self.data.missing:
            self.data.outcome = ("Not run: required arguments were never "
                                 "supplied: " + ", ".join(self.data.missing))
            return
        try:
            if self.data.target_action == "create":
                # approval-mode create composes (design §2): the capture is
                # a stage on the write pipeline, and create is a write
                doc = await ctx.create(self.data.target_kind,
                                       self.data.target_input or {})
                self.data.outcome = "Ran successfully."
                self.data.result_state = doc.get("state")
                return
            target = await ctx.read(self.data.target_kind,
                                    self.data.target_id)
            if target is None:
                self.data.outcome = "Failed: the target no longer exists."
                return
            # the approval is consent for the CURRENT state — satisfy any
            # etag fence with the version being consented to
            etag = (f'W/"{self.data.target_kind}-{self.data.target_id}'
                    f'-v{target.version}"')
            doc = await ctx.invoke(self.data.target_kind, self.data.target_id,
                                   self.data.target_action,
                                   self.data.target_input or None,
                                   if_match=etag)
            self.data.outcome = "Ran successfully."
            self.data.result_state = doc.get("state")
        except Exception as exc:  # recorded, never swallowed silently
            self.data.outcome = f"Failed: {exc}"[:240]


def dead_grant() -> "Grant":
    """The scope of a presented-but-unknown token. A token that names
    itself an agent link must NEVER fall through to another auth path —
    dead means scoped-to-nothing, not anonymous."""
    return Grant(id="unknown-token", state=str(GrantState.REVOKED),
                 data=GrantData(holder_name="unknown agent"), version=0)


# ── scope evaluation ────────────────────────────────────────────────────
ENGINE_KINDS = {"grant", "approval_request"}


def grant_active(grant: Any, now: datetime) -> bool:
    if grant.state not in (str(GrantState.GRANTED), str(GrantState.REQUESTED)):
        return False
    if not grant.data.granted_actions and not grant.data.granted_fields:
        return False
    return grant.data.expires_at is not None and now < grant.data.expires_at


def field_mode(grant: Any, now: datetime, kind: str, field_name: str) -> str:
    if not grant_active(grant, now):
        return "hidden"
    return (grant.data.granted_fields.get(kind) or {}).get(field_name, "hidden")


def action_mode(grant: Any, now: datetime, kind: str, action_name: str) -> str:
    if not grant_active(grant, now):
        return "none"
    return (grant.data.granted_actions.get(kind) or {}).get(action_name, "none")


def arg_mode(grant: Any, now: datetime, kind: str, action_name: str,
             arg: str) -> str:
    if not grant_active(grant, now):
        return "none"
    modes = ((grant.data.granted_args.get(kind) or {}).get(action_name) or {})
    # an argument unmentioned under a granted action defaults to edit — the
    # arg map is for *narrowing* an action the agent asked for
    return modes.get(arg, "edit")


def owns(grant: Any, kind: str, doc_or_id: Any) -> bool:
    """The agent's own negotiation surface: its grant, and approvals that
    grant created."""
    if kind == "grant":
        target = doc_or_id if isinstance(doc_or_id, str) else \
            doc_or_id.get("self", "").rsplit("/", 1)[-1]
        return target == grant.id
    if kind == "approval_request":
        if isinstance(doc_or_id, dict):
            return doc_or_id.get("data", {}).get("grant_id") == grant.id
    return False


AGENT_GRANT_ACTIONS = {"request_access"}          # on its own grant
AGENT_APPROVAL_ACTIONS = {"run"}                  # on its own approvals

SCOPE_REASON = ("Outside this agent link's granted scope. State what you "
                "need through your grant's request_access.")


def _hash_value(value: Any) -> str:
    canon = json.dumps(value, sort_keys=True, default=str)
    return "sha256:" + hashlib.sha256(canon.encode()).hexdigest()[:12]


NEGOTIATION_REASON = "Yours to request; a person's to decide."


def _holder_match(holder_id: str | None, principal_id: str) -> bool:
    """A member holder matches its principal under both spellings: the
    OIDC resolver's ``member:<id>`` and a dev resolver's bare id."""
    return bool(holder_id) and principal_id in (holder_id,
                                                f"member:{holder_id}")


def _min_mode(a: str, b: str, rank: dict[str, int]) -> str:
    return a if rank.get(a, 0) <= rank.get(b, 0) else b


def _max_mode(a: str, b: str, rank: dict[str, int]) -> str:
    return a if rank.get(a, 0) >= rank.get(b, 0) else b


class GrantVisibility:
    """The grant-backed :class:`~waymark3.core.visibility.Visibility`
    (design §1): computed once per request from the token's grant, then
    consulted by the projector while *building* the envelope and by the
    write path to enforce. This replaces v2's ``apply_scope`` — there is
    no post-hoc redaction pass, no ``_collection`` suffix dispatch, no
    ``parts`` re-admit TODO: what may not be seen is never rendered.

    Instance selectors (design §9): a grant whose ``granted_over`` names
    ids for a kind applies only to those resources — everything else of
    that kind stays default-deny. "Edit access to *this* meal plan" is a
    selector, not a new mechanism.

    Delegation is attenuation (design §9): ``ceiling`` is the approving
    member's *current* effective visibility, intersected live — a holder's
    view can never exceed its approver's, and revoking the approver's own
    access shrinks the delegate's in the same render. No cleanup job.
    """

    full = False

    def __init__(self, grant: Any, now: datetime, *, ceiling: Any = None):
        self.grant = grant
        self.now = now
        self.ceiling = ceiling

    # the agent's own negotiation surface renders with exactly the
    # self-service actions, whatever the granted maps say
    def negotiation_actions(self, kind: str, id: str | None,
                            data: dict[str, Any] | None) -> set[str] | None:
        if kind == "grant" and id == self.grant.id:
            return set(AGENT_GRANT_ACTIONS)
        if kind == "approval_request" \
                and (data or {}).get("grant_id") == self.grant.id:
            return set(AGENT_APPROVAL_ACTIONS)
        return None

    def _selected(self, kind: str, id: str | None) -> bool:
        over = getattr(self.grant.data, "granted_over", None) or {}
        ids = over.get(kind)
        if not ids:
            return True  # kind-level grant
        return id is not None and id in ids

    def field(self, kind: str, name: str, id: str | None = None,
              owner: str | None = None) -> str:
        from ..core.visibility import FIELD_RANK

        if not self._selected(kind, id):
            return "hidden"
        mode = field_mode(self.grant, self.now, kind, name)
        if self.ceiling is not None:
            mode = _min_mode(mode, self.ceiling.field(kind, name, id, owner),
                             FIELD_RANK)
        return mode

    def action(self, kind: str, name: str, id: str | None = None,
               data: dict[str, Any] | None = None,
               owner: str | None = None) -> str:
        from ..core.visibility import ACTION_RANK

        own = self.negotiation_actions(kind, id, data)
        if own is not None:
            return "open" if name in own else "negotiation"
        if not self._selected(kind, id):
            return "none"
        mode = action_mode(self.grant, self.now, kind, name)
        if self.ceiling is not None:
            mode = _min_mode(mode,
                             self.ceiling.action(kind, name, id, data, owner),
                             ACTION_RANK)
        return mode

    def arg(self, kind: str, action: str, name: str) -> str:
        return arg_mode(self.grant, self.now, kind, action, name)

    def summary_clear(self, kind: str, id: str | None = None,
                      owner: str | None = None) -> bool:
        return self.field(kind, "summary", id, owner) == "clear"

    def restrict(self, kind: str) -> tuple[str, set[str]] | None:
        # agents browse whole collections; each item projects per grant
        # (v2 behavior, kept: the selector narrows items, not the listing)
        return None

    def hash(self, value: Any) -> str:
        return _hash_value(value)


class MemberVisibility:
    """A member's effective view (design §9): **full over what they own,
    the union of their member- and role-held grants otherwise.** Engine
    kinds (grants, approvals, members, subscriptions, jobs) stay open —
    they are the negotiation and administration surface, and their guards
    (``no_self_dealing``) still judge.

    The same object advertises (projection), enforces (the act gate), and
    pushes down (``restrict`` → collection SQL) — one declaration's worth
    of truth, three consumers.
    """

    full = False

    OPEN_KINDS = frozenset({"grant", "approval_request", "member",
                            "subscription", "job"})

    def __init__(self, principal_id: str, grants: list[Any], now: datetime):
        self.principal_id = principal_id
        self.now = now
        self.grants = [g for g in grants if grant_active(g, now)]

    def owns(self, owner: str | None) -> bool:
        return owner is not None and owner == self.principal_id

    def negotiation_actions(self, kind: str, id: str | None,
                            data: dict[str, Any] | None) -> set[str] | None:
        return None  # members negotiate through the open engine kinds

    def field(self, kind: str, name: str, id: str | None = None,
              owner: str | None = None) -> str:
        from ..core.visibility import FIELD_RANK

        if kind in self.OPEN_KINDS or self.owns(owner):
            return "clear"
        mode = "hidden"
        for g in self.grants:
            gv = GrantVisibility(g, self.now)
            mode = _max_mode(mode, gv.field(kind, name, id, owner), FIELD_RANK)
        return mode

    def action(self, kind: str, name: str, id: str | None = None,
               data: dict[str, Any] | None = None,
               owner: str | None = None) -> str:
        from ..core.visibility import ACTION_RANK

        if kind in self.OPEN_KINDS or self.owns(owner):
            return "open"
        if name == "create":
            return "open"  # what you create, you own — the baseline
        mode = "none"
        for g in self.grants:
            mode = _max_mode(
                mode, action_mode(g, self.now, kind, name)
                if GrantVisibility(g, self.now)._selected(kind, id)
                else "none",
                ACTION_RANK)
        return mode

    def arg(self, kind: str, action: str, name: str) -> str:
        from ..core.visibility import ARG_RANK

        if kind in self.OPEN_KINDS:
            return "edit"
        mode = "none"
        for g in self.grants:
            mode = _max_mode(mode, arg_mode(g, self.now, kind, action, name),
                             ARG_RANK)
        return mode

    def summary_clear(self, kind: str, id: str | None = None,
                      owner: str | None = None) -> bool:
        return self.field(kind, "summary", id, owner) == "clear"

    def approval_grant(self, kind: str, name: str,
                       id: str | None = None) -> Any | None:
        """The grant that puts (kind, action) in approval mode — the act
        path routes the invocation through its approval_request."""
        for g in self.grants:
            if GrantVisibility(g, self.now)._selected(kind, id) \
                    and action_mode(g, self.now, kind, name) == "approval":
                return g
        return None

    def restrict(self, kind: str) -> tuple[str, set[str]] | None:
        """The collection pushdown: owned rows plus instance-granted ids —
        unless some grant covers the kind unselected (kind-level), which
        lifts the restriction (items still project per field modes)."""
        if kind in self.OPEN_KINDS:
            return None
        ids: set[str] = set()
        for g in self.grants:
            data = g.data
            mentions = kind in (data.granted_fields or {}) \
                or kind in (data.granted_actions or {})
            if not mentions:
                continue
            over = (data.granted_over or {}).get(kind)
            if not over:
                return None  # kind-level grant: the whole listing projects
            ids.update(over)
        return (self.principal_id, ids)

    def hash(self, value: Any) -> str:
        return _hash_value(value)


async def member_visibility(storage: Any, principal: Any,
                            now: datetime) -> MemberVisibility:
    """Build a member's effective visibility: every granted grant held by
    them (member holder) or by any of their roles. Two indexed queries —
    the union itself is in-memory and request-scoped."""
    pid = principal.id
    member_id = pid.removeprefix("member:")
    held: list[Any] = []
    async with storage.session() as s:
        for holder_id in {member_id, pid}:
            rows, _ = await storage.query(
                s, "grant", filters={"state": "granted", "holder_kind": "member",
                                     "holder_id": holder_id},
                sort=None, page_size=200, page_number=1)
            held.extend(rows)
        for role in sorted(getattr(principal, "roles", ()) or ()):
            rows, _ = await storage.query(
                s, "grant", filters={"state": "granted", "holder_kind": "role",
                                     "holder_id": role},
                sort=None, page_size=200, page_number=1)
            held.extend(rows)
    unique = {g.id: g for g in held}
    return MemberVisibility(pid, list(unique.values()), now)


def visibility_of(principal: Any, now: datetime) -> Any:
    """The principal's visibility capability. A visibility attached at
    resolve time (member union, attenuated token) wins; a bare token
    principal falls back to its grant; everyone else is FULL. The one
    place the credential's shape is consulted."""
    from ..core.visibility import FULL

    attached = getattr(principal, "visibility", None)
    if attached is not None:
        return attached
    grant = getattr(principal, "scope", None)
    if grant is None:
        return FULL
    return GrantVisibility(grant, now)


# import-compat alias: an agent link is a token-held grant
AgentGrant = Grant
