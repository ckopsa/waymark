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
  ``agent_grants`` collection in the generic UI, approvals are
  confirm-gated actions with declared consequences, every negotiation
  step is in the audit log, and a human can *follow* the agent through
  its own permission request;
- an approval-mode invocation is a resource too
  (``pending → approved → closed | rejected``): the 202 hands the agent
  the approval's envelope, the human approves (optionally supplying the
  arguments the agent couldn't), and ``run`` executes the stored
  invocation through ``ctx.invoke`` — the same transition algorithm as
  everything else, one accountable audit entry, actor = whoever ran it.

Default-deny is *rendering*: :func:`apply_scope` redacts the envelope so
an ungrated field isn't dimmed or locked — it does not exist. The
advertisement and the enforcement stay one thing.
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


def _mint_token() -> str:
    return TOKEN_PREFIX + uuid.uuid4().hex + uuid.uuid4().hex


# ── the grant resource ──────────────────────────────────────────────────
class GrantState(StrEnum):
    DRAFT = "draft"          # link minted; the agent has the catalog, nothing else
    REQUESTED = "requested"  # the agent stated its task and its minimum ask
    GRANTED = "granted"      # a human approved; enforcement reads granted_* maps
    REVOKED = "revoked"


class GrantData(BaseModel):
    agent_name: str = Field(min_length=1, max_length=40,
                            description="Who this link is for (shown in "
                                        "feeds and approvals)")
    token: str = Field(default_factory=_mint_token,
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
    requested_hours: int = Field(default=24, ge=1, le=720,
                                 description="How long the access should last")
    # what enforcement reads (copied from requested_* by `approve`); during
    # an amendment (granted → requested) the old grant stays in force
    granted_fields: FieldMap = Field(default_factory=dict)
    granted_actions: ActionMap = Field(default_factory=dict)
    granted_args: ArgMap = Field(default_factory=dict)
    expires_at: AwareDatetime | None = None


class GrantCreate(GrantData):
    """The human mints a link: name the agent; the token generates itself.
    Everything else is the agent's to request and the human's to approve."""

    token: SkipJsonSchema[str] = Field(default_factory=_mint_token)
    agent_principal: SkipJsonSchema[str | None] = None
    task: SkipJsonSchema[str | None] = None
    requested_fields: SkipJsonSchema[FieldMap] = Field(default_factory=dict)
    requested_actions: SkipJsonSchema[ActionMap] = Field(default_factory=dict)
    requested_args: SkipJsonSchema[ArgMap] = Field(default_factory=dict)
    requested_hours: SkipJsonSchema[int] = 24
    granted_fields: SkipJsonSchema[FieldMap] = Field(default_factory=dict)
    granted_actions: SkipJsonSchema[ActionMap] = Field(default_factory=dict)
    granted_args: SkipJsonSchema[ArgMap] = Field(default_factory=dict)
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
    requested_hours: int = Field(default=24, ge=1, le=720)


async def _not_the_scoped_agent(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
    scope = getattr(ctx.principal, "scope", None)
    if scope is not None:
        return Deny()
    return Allow()

no_self_dealing = Guard(
    name="no_self_dealing", reads=("principal",),
    explain="An agent cannot judge its own access; a person approves it.",
    check=_not_the_scoped_agent,
)


class AgentGrant(Resource):
    kind = "agent_grant"
    State = GrantState
    Data = GrantData
    Create = GrantCreate

    initial = GrantState.DRAFT
    terminal = {GrantState.REVOKED}

    summary = "Agent link for {data.agent_name} · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In,
                            token=filterable.Eq)

    display = {"title": "Agent link — {data.agent_name}"}

    async def on_create(self, ctx: Ctx) -> None:
        # the token resolves to this exact actor id (engine._token_principal);
        # naming it here lets any client offer "follow this agent"
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
        self.data.requested_hours = inp.requested_hours

    @action(from_=GrantState.REQUESTED, to=GrantState.GRANTED,
            guards=[no_self_dealing],
            safety=Safety(idempotent=True, reversible=True, confirm=True,
                          consequence="The agent gets exactly the requested "
                                      "access, for the requested duration."),
            display=dict(label="Approve access", style="primary", order=1))
    async def approve(self, inp: None, ctx: Ctx) -> None:
        self.data.granted_fields = dict(self.data.requested_fields)
        self.data.granted_actions = dict(self.data.requested_actions)
        self.data.granted_args = dict(self.data.requested_args)
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


# ── the approval resource (an "access via approval" invocation) ────────
class ApprovalState(StrEnum):
    PENDING = "pending"
    APPROVED = "approved"
    CLOSED = "closed"      # ran (outcome recorded either way)
    REJECTED = "rejected"


class ApprovalData(BaseModel):
    grant_id: str = Field(json_schema_extra={"x-display": {
        "widget": "resource", "kind": "agent_grant"}})
    agent_principal: str = Field(max_length=128, json_schema_extra={
        "x-display": {"hidden": True}})
    agent_name: str = Field(max_length=40)
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

    summary = "{data.agent_name} asks: {data.title} · {state.label}"

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


def dead_grant() -> "AgentGrant":
    """The scope of a presented-but-unknown token. A token that names
    itself an agent link must NEVER fall through to another auth path —
    dead means scoped-to-nothing, not anonymous."""
    return AgentGrant(id="unknown-token", state=str(GrantState.REVOKED),
                      data=GrantData(agent_name="unknown agent"), version=0)


# ── scope evaluation ────────────────────────────────────────────────────
ENGINE_KINDS = {"agent_grant", "approval_request"}


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
    if kind == "agent_grant":
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


def _scope_fields(data: Any, modes: dict[str, str]) -> Any:
    if isinstance(data, dict):
        out: dict[str, Any] = {}
        for key, value in data.items():
            mode = modes.get(key, "hidden")
            if mode == "clear":
                # recurse into lists of objects so item fields obey the same
                # kind-level map (a plan's days carry plan-declared fields)
                if isinstance(value, list) and value \
                        and isinstance(value[0], dict):
                    out[key] = [_scope_fields(v, modes) for v in value]
                else:
                    out[key] = value
            elif mode == "hashed":
                out[key] = _hash_value(value)
            # hidden: the field does not exist for this principal
        return out
    return data


def apply_scope(doc: dict[str, Any], grant: Any, now: datetime) -> dict[str, Any]:
    """Redact an envelope to a grant's view. Default deny is absence:
    ungrated fields don't render, ungrated actions are honestly
    ``unavailable`` with the remedy naming the fix, approval-mode actions
    carry ``access: "approval"`` so the agent knows a 202 is coming."""
    kind = doc.get("kind", "")
    if owns(grant, kind, doc):
        allowed = (AGENT_GRANT_ACTIONS if kind == "agent_grant"
                   else AGENT_APPROVAL_ACTIONS)
        out = dict(doc)
        actions, unavailable = {}, dict(doc.get("unavailable") or {})
        for name, entry in (doc.get("actions") or {}).items():
            if name in allowed:
                actions[name] = entry
            else:
                unavailable[name] = {
                    "reason": "Yours to request; a person's to decide."}
        out["actions"], out["unavailable"] = actions, unavailable
        return out

    if kind.endswith("_collection"):
        base_kind = kind.removesuffix("_collection")
        out = dict(doc)
        data = dict(doc.get("data") or {})
        data["items"] = [apply_scope(item, grant, now)
                         for item in data.get("items") or []]
        out["data"] = data
        out["summary"] = f"{base_kind} collection (scoped view)"
        out["actions"], out["unavailable"] = _scope_actions(
            doc.get("actions") or {}, grant, now, base_kind,
            dict(doc.get("unavailable") or {}))
        return out

    out = dict(doc)
    modes = (grant.data.granted_fields.get(kind) or {}) \
        if grant_active(grant, now) else {}
    out["data"] = _scope_fields(doc.get("data") or {}, modes)
    if modes.get("summary") != "clear":
        out["summary"] = f"{kind.replace('_', ' ')} (scoped view)"
    out["actions"], out["unavailable"] = _scope_actions(
        doc.get("actions") or {}, grant, now, kind,
        dict(doc.get("unavailable") or {}))
    out.pop("parts", None)          # parts mirror data; re-admit later if needed
    out.pop("display", None)
    links = {}
    for rel, link in (doc.get("links") or {}).items():
        if not link:
            continue
        slim = {k: v for k, v in link.items() if k != "embedded"}
        if "summary" in slim:
            slim["summary"] = slim.get("kind", rel)
        links[rel] = slim
    out["links"] = links
    return out


def _scope_actions(actions: dict[str, Any], grant: Any, now: datetime,
                   kind: str, unavailable: dict[str, Any]
                   ) -> tuple[dict[str, Any], dict[str, Any]]:
    kept: dict[str, Any] = {}
    for name, entry in actions.items():
        mode = action_mode(grant, now, kind, name)
        if mode == "open":
            kept[name] = entry
        elif mode == "approval":
            kept[name] = {**entry, "access": "approval"}
        else:
            unavailable[name] = {"reason": SCOPE_REASON,
                                 "remedies": ["agent_grant.request_access"]}
    # guard-denied entries: keep their honest reasons (the agent needs them
    # to act well), but only for actions the grant knows about at all
    for name in list(unavailable):
        if name in actions or name in kept:
            continue
        if action_mode(grant, now, kind, name) == "none":
            unavailable[name] = {"reason": SCOPE_REASON,
                                 "remedies": ["agent_grant.request_access"]}
    return kept, unavailable
