"""Core value types: principals, guard verdicts, safety.

2.0 change (design §3): ``Safety`` is a declaration object whose invariants
live in its constructor — a confirm without a stated consequence, or an
unacknowledged one-way door, cannot be expressed at all.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Literal

ActorType = Literal["human", "agent", "system"]


class DefinitionError(TypeError):
    """A resource definition violates a declared rule. Raised at import."""


@dataclass(frozen=True)
class Principal:
    id: str
    type: ActorType = "human"
    roles: frozenset[str] = frozenset()
    display: str = ""
    locale: str = "en"
    # a token-resolved principal carries its Grant (design: agent
    # links); None = an unscoped principal, untouched by grant enforcement
    scope: Any = field(default=None, compare=False)
    # the resolver may attach a computed Visibility (a member's grant
    # union, an attenuated token view); visibility_of() prefers it
    visibility: Any = field(default=None, compare=False)

    @classmethod
    def anonymous(cls) -> "Principal":
        return cls(id="anonymous", type="human", display="Anonymous")


@dataclass
class Ctx:
    """Per-invocation context passed to guards and handlers.

    ``mode`` distinguishes render-time probing (``inp is None``) from real
    invocation; guards whose ``check`` needs input short-circuit to
    ``Allow(pending_input=True)`` during probe.
    """

    principal: Principal
    now: datetime
    services: Any = None
    session: Any = None  # ambient AsyncSession during invoke; None in pure render tests
    locale: str = "en"
    correlation_id: str | None = None
    mode: Literal["probe", "invoke", "dry_run"] = "invoke"
    _invoker: Any = None  # set by the engine; enables ctx.invoke for workflows
    _reader: Any = None   # set by the engine; enables ctx.read for guards
    _finder: Any = None   # set by the engine; enables ctx.find for guards/hooks
    _rate: Any = None     # set by the engine; the bus-shared rate window (§8)
    _creator: Any = None  # set by the engine; enables ctx.create (§2)
    _actor_of: Any = None  # set by the engine; enables ctx.actor_of (E3)
    _deferrer: Any = None  # set by the engine; enables ctx.defer (E6)
    _effector: Any = None  # set by the engine; enables ctx.run_effects (§6)
    # (label, declared touches) while a handler runs (design E8): the same
    # declaration the envelope advertises, enforced at this surface
    _touch_scope: Any = None
    # the definition-lifecycle seam (design 7.0 §1–§2): set by the invoker;
    # consulted only by the definition kind's own handlers and guards
    # (promote's residency check, measure's meter) — never by app code
    _lifecycle: Any = None

    async def invoke(self, resource: type | str, id: str, action: str,
                     body: dict[str, Any] | None = None, *,
                     if_match: str | None = None) -> Any:
        """Invoke a transition on another resource through the engine.

        Shares this invocation's transaction and correlation_id. Fenced
        actions still demand ``if_match`` — a cascade is not exempt from
        the etag fence; pass the current etag if the caller holds consent
        for the current state (e.g. an approval's ``run``).
        """
        if self._invoker is None:
            raise RuntimeError("ctx.invoke is only available inside an engine-managed invocation")
        return await self._invoker(resource, id, action, body, ctx=self,
                                   if_match=if_match)

    async def create(self, resource: type | str,
                     body: dict[str, Any] | None, *,
                     acknowledged: frozenset[str] = frozenset()) -> Any:
        """Create another resource through the engine, in this invocation's
        transaction — what lets an approved agent proposal run create
        through the same machinery as any other approved action (§2).
        ``acknowledged`` carries warning-guard overrides a surface already
        recorded (an approval-mode create's stored acknowledgments, design
        §10); an ordinary child create passes none."""
        if self._creator is None:
            raise RuntimeError("ctx.create is only available inside an engine-managed invocation")
        return await self._creator(resource, body, ctx=self,
                                   acknowledged=frozenset(acknowledged))

    async def read(self, resource: type | str, id: str) -> Any:
        """Load another resource's current instance in this transaction —
        the read half of cross-resource guards."""
        if self._reader is None:
            raise RuntimeError("ctx.read is only available inside an engine-managed invocation")
        return await self._reader(resource, id, ctx=self)

    async def find(self, resource: type | str, *, sort: str | None = None,
                   limit: int = 25, page: int = 1,
                   **filters: Any) -> list[Any]:
        """Query another kind's instances in this transaction — the list half
        of cross-resource reads. Filters take the collection query's field
        names (e.g. ``state="active"``). ``page`` walks further pages of
        ``limit`` rows — what lets a caller that must see *every* match
        (the create-time fact computation) page exactly as the maintainer
        does instead of trusting one truncated read."""
        if self._finder is None:
            raise RuntimeError("ctx.find is only available inside an engine-managed invocation")
        return await self._finder(resource, filters, sort=sort, limit=limit,
                                  page=page, ctx=self)

    async def actor_of(self, resource: type | str, id: str,
                       action: str) -> str | None:
        """The actor of a resource's latest ``action`` transition — the
        log fact history-dependent authority reads (design E3)."""
        if self._actor_of is None:
            raise RuntimeError("ctx.actor_of is only available inside an engine-managed invocation")
        return await self._actor_of(resource, id, action, ctx=self)

    async def defer(self, service: Any,
                    artifacts: list[tuple[str, tuple[Any, ...]]], *,
                    action: str) -> str:
        """Async egress as a job resource (design E6): each artifact is
        ``(name, handler_args)`` run through ``service.call`` by a
        background runner. Returns the job id — the handler's to store or
        link; progress and outcomes live on the job."""
        if self._deferrer is None:
            raise RuntimeError("ctx.defer is only available inside an engine-managed invocation")
        return await self._deferrer(service, artifacts, action=action,
                                    ctx=self)

    async def run_effects(self, label: str, effects: list, *,
                          defer: bool = False) -> Any:
        """The declared external effects of a compound act (design §6):
        each is a resolved Service operation with its compensator. Inline
        (``defer=False``) they execute in order before the commit and the
        engine compensates in reverse on abort; deferred they ride the E6
        job kind post-commit. Every attempt is audited either way."""
        if self._effector is None:
            raise RuntimeError("ctx.run_effects is only available inside an engine-managed invocation")
        return await self._effector(label, effects, defer=defer, ctx=self)


@dataclass(frozen=True)
class Allow:
    pending_input: bool = False


@dataclass(frozen=True)
class Deny:
    vars: dict[str, Any] | None = None
    errors: dict[str, list[str]] | None = None
    retry_at: datetime | None = None


@dataclass(frozen=True)
class Acknowledged:
    """A written acknowledgment for an escape hatch (design §1, §3).

    Wherever 2.0 turns a v1 warning into a structural rule, the way out is
    not a bare waiver token but a sentence: you must say *why* this
    declaration is safe as written.
    """

    reason: str

    def __post_init__(self) -> None:
        if not self.reason or not self.reason.strip():
            raise DefinitionError("Acknowledged() requires a non-empty reason "
                                  "— the acknowledgment is the sentence")


@dataclass(frozen=True)
class Safety:
    """Declared safety semantics; the invariants are the constructor's.

    - ``confirm=True`` requires ``consequence`` — a confirm dialog that
      cannot say what happens is unrepresentable (v1's planned
      ``blind_confirm``).
    - ``fence=True`` renders as wire ``fence`` — one spelling per concept
      (design §8); v2's ``requires_if_match`` bridge property is gone.
    - ``one_way`` acknowledges an irreversible, unconfirmed, state-leaving
      transition (v1's planned ``one_way_door``); whether it is *required*
      is the machine checker's call — the graph knows about self-loops,
      this object does not.
    """

    idempotent: bool
    reversible: bool
    confirm: bool
    fence: bool = False
    consequence: str | None = None
    one_way: Acknowledged | None = None

    def __post_init__(self) -> None:
        if self.confirm and not (self.consequence and self.consequence.strip()):
            raise DefinitionError(
                "Safety(confirm=True) requires consequence='…' stating what "
                "will happen — a confirm that only asks 'are you sure?' is a "
                "blind confirm")
        if self.one_way is not None and (self.reversible or self.confirm):
            raise DefinitionError(
                "Safety(one_way=…) acknowledges an irreversible, unconfirmed "
                "transition; this one is "
                + ("reversible" if self.reversible else "confirmed"))

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "idempotent": self.idempotent,
            "reversible": self.reversible,
            "confirm": self.confirm,
        }
        if self.fence:
            out["fence"] = True
        return out


@dataclass(frozen=True)
class Effect:
    to: str
    terminal: bool = False
    emits: tuple[str, ...] = ()
    bulk: bool = False
    # declared touches (design E8); duck-typed to avoid a types↔touches cycle
    touches: tuple[Any, ...] = ()
    # the compound declaration (design §6); duck-typed likewise — its
    # blast_radius() folds creates/advances/effects summaries into the wire
    compound: Any = None

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {"to": self.to}
        if self.terminal:
            out["terminal"] = True
        if self.emits:
            out["emits"] = list(self.emits)
        if self.bulk:
            out["bulk"] = True
        if self.touches:
            out["touches"] = [t.to_wire() for t in self.touches]
        if self.compound is not None:
            out.update(self.compound.blast_radius())
        return out
