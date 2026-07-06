"""Guards (design §1): a guard is a declaration; the callable is one field of it.

A 2.0 guard declares, up front:

- ``judges`` — the input fields whose values it grades;
- ``reads`` — what beyond the document its verdict consults (kind tokens
  like ``"meal"``, ``"services.payments"``, ``"now"``). Empty means the
  verdict is a pure function of ``(resource, input)`` — document-derivable;
- ``accepts`` — THE acceptance set for its single judged field. Render folds
  it into the advertised schema as an ``enum``; the engine enforces
  membership in the same set. One declaration, two consumers, no drift;
- ``check`` — residual logic beyond set membership, an async
  ``(r, inp, ctx) -> Allow | Deny``;

Anything cross-field is a :class:`Relation` (design §5): an enumerable
tuple set (``accepts=``) or a comparison (``op="<="``) — v2's ``relates=``
spelling is retired, and using it names the replacement.
- ``open`` — an :class:`Acknowledged` sentence, the *only* way to grade a
  field while giving clients nothing to constrain it with (the closure
  rule, enforced in checks.py, makes v1's ``open_input`` warning a
  definition-time error).

3.0 change (design §8): there is no AST inspection *and no signature
inspection* in this module. A callable's needs are read from the
declaration, never sniffed from the callable:

- ``accepts`` is called ``accepts(r, ctx)`` iff the guard declares
  ``reads≠()``, else ``accepts(r)`` — ``reads`` already states whether a
  ctx is needed; v2's ``_wants_ctx`` parameter-counting is gone.
- a ``check`` is probe-skipped iff the guard ``judges`` input fields —
  a check that grades input cannot decide without it; v2's
  ``_fn_needs_input`` annotation-sniffing is gone. Override with
  ``needs_input=`` when judgment and input genuinely diverge.
- a ``vars`` callable moved to ``vars_fn=`` and must be accompanied by
  ``vars=`` naming what it produces — the static template check always
  runs; v2's "empirical: token_prose covers it" punt is gone.
"""
from __future__ import annotations

import inspect  # isawaitable only — never signatures
from collections.abc import Awaitable, Callable, Iterable, Mapping
from datetime import datetime
from typing import Any

from .types import Acknowledged, Allow, Ctx, DefinitionError, Deny

CheckFn = Callable[[Any, Any, Ctx], Awaitable[Allow | Deny]]

RELATION_OPS: dict[str, Callable[[Any, Any], bool]] = {
    "<": lambda a, b: a < b,
    "<=": lambda a, b: a <= b,
    ">": lambda a, b: a > b,
    ">=": lambda a, b: a >= b,
    "==": lambda a, b: a == b,
}


class _SafeDict(dict):
    def __missing__(self, key: str) -> str:  # never crash rendering a reason
        return "{" + key + "}"


class Guard:
    is_relation = False  # Relation widens the single-field accepts invariant
    op: str | None = None  # set by comparison Relations only

    def __init__(
        self,
        *,
        explain: str,
        judges: Iterable[str] = (),
        reads: Iterable[str] = (),
        accepts: Callable[..., Any] | None = None,
        check: CheckFn | None = None,
        relates: tuple[str, str, str] | None = None,
        open: Acknowledged | None = None,
        vars: Iterable[str] | None = None,
        vars_fn: Callable[[Any], Mapping[str, Any]] | None = None,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
        name: str | None = None,
        needs_input: bool | None = None,
    ):
        if not explain or not explain.strip():
            raise DefinitionError("Guard requires explain='…' — every refusal "
                                  "must come with its reason")
        self.explain = explain
        self.judges: tuple[str, ...] = tuple(judges)
        self.reads: tuple[str, ...] = tuple(reads)
        self.accepts = accepts
        self.check = check
        self.relates = relates
        self.open = open
        self.hide = hide
        self.remedies = tuple(remedies)
        self.becomes_available_at = becomes_available_at
        self.requires_token = requires_token
        self.name = name or getattr(check or accepts, "__name__", None) or "guard"
        if self.name == "<lambda>":
            self.name = "guard"

        if relates is not None:
            a, op, b = relates
            raise DefinitionError(
                f"guard {self.name!r}: relates= is retired — a comparison is "
                f"the special case of a Relation (design §5). Declare "
                f"Relation(judges=({a!r}, {b!r}), op={op!r}, explain=…)")

        if accepts is not None and not self.is_relation:
            if len(self.judges) != 1:
                raise DefinitionError(
                    f"guard {self.name!r}: accepts= constrains exactly one "
                    f"judged field; judges={list(self.judges)}. Multi-field "
                    "acceptance is a Relation (design §5)")
        if accepts is None and check is None and not self.is_relation:
            raise DefinitionError(
                f"guard {self.name!r} declares no accepts and no check — it "
                "can neither advertise nor enforce anything")

        if callable(vars):
            raise DefinitionError(
                f"guard {self.name!r}: vars= takes the *names* the template "
                "uses; a callable that produces them is vars_fn= — and it "
                "still declares its names so the template check always runs "
                "(design §8: nothing sniffed, nothing empirical)")
        if vars_fn is not None and not vars:
            raise DefinitionError(
                f"guard {self.name!r}: vars_fn= requires vars=('name', …) "
                "declaring what it produces — the callable's output is a "
                "declaration, not a discovery")
        self.vars_fn = vars_fn
        self.declared_vars: frozenset[str] = (
            frozenset(vars) if vars is not None else frozenset())

        # Probe semantics are derived from the declaration: a check that
        # judges input fields cannot decide without input. Not sniffed.
        if needs_input is not None:
            self.needs_input = needs_input
        else:
            self.needs_input = check is not None and bool(self.judges)

    # ── classification (what v1 AST-scanned, 2.0 declares) ─────────────
    @property
    def reads_ctx(self) -> bool:
        return bool(self.reads)

    @property
    def input_fields(self) -> frozenset[str]:
        return frozenset(self.judges)

    @property
    def admits(self) -> tuple[str, Callable] | None:
        """(field, accepts_fn) when this guard advertises a *single-field*
        acceptance set. Relations advertise via ``relation_admits``."""
        if self.accepts is None or self.is_relation:
            return None
        return self.judges[0], self.accepts

    # ── the acceptance set: one declaration, two consumers ─────────────
    async def admitted(self, r: Any, ctx: Any) -> list[Any] | None:
        """Evaluate ``accepts``; None = no set declared or the fn declined
        to constrain this render. Raises propagate — the caller decided to
        resolve, so a missing dependency is a loud error, not a silent
        un-tightening (design §6).

        The call shape is the declaration's (design §8): ``reads≠()`` ⇒
        ``accepts(r, ctx)``, ``reads=()`` ⇒ ``accepts(r)``. Never sniffed.
        """
        if self.accepts is None:
            return None
        out = self.accepts(r, ctx) if self.reads else self.accepts(r)
        if inspect.isawaitable(out):
            out = await out
        return None if out is None else list(out)

    # ── evaluation: enforcement runs the same declarations ─────────────
    async def evaluate(self, r: Any, inp: Any, ctx: Ctx) -> tuple[Allow | Deny, "Guard"]:
        """Return (verdict, denier). For a leaf guard the denier is itself."""
        pending = False
        if inp is not None and self.accepts is not None:
            field = self.judges[0]
            value = getattr(inp, field, None)
            if value is not None:
                allowed = await self.admitted(r, ctx)
                if allowed is not None and value not in allowed \
                        and str(value) not in {str(a) for a in allowed}:
                    return Deny(vars={field: value}), self
        if self.check is not None:
            if ctx.mode == "probe" and inp is None and self.needs_input:
                return Allow(pending_input=True), self
            return await self.check(r, inp, ctx), self
        return Allow(pending_input=pending), self

    async def __call__(self, r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
        verdict, _ = await self.evaluate(r, inp, ctx)
        return verdict

    # ── explanation ─────────────────────────────────────────────────────
    def render_reason(self, deny: Deny, r: Any = None) -> str:
        merged: dict[str, Any] = {}
        if self.vars_fn is not None and r is not None:
            try:
                merged.update(self.vars_fn(r))
            except Exception:
                pass  # a reason must render even when its garnish cannot
        if deny.vars:
            merged.update(deny.vars)
        return self.explain.format_map(_SafeDict(merged))

    def becomes_available(self, deny: Deny, r: Any = None) -> dict[str, Any] | None:
        """Structured hope: ``at`` / ``requires``; ``in_states`` is render's job."""
        if deny.retry_at is not None:
            return {"at": deny.retry_at.isoformat()}
        if self.becomes_available_at is not None and r is not None:
            try:
                return {"at": self.becomes_available_at(r).isoformat()}
            except Exception:
                return None
        if self.requires_token is not None:
            return {"requires": self.requires_token}
        return None

    def iter_leaves(self) -> Iterable["Guard"]:
        yield self

    def __and__(self, other: "Guard") -> "Guard":
        return _AllGuard(self, other)

    def __or__(self, other: "Guard") -> "Guard":
        return _AnyGuard(self, other)

    def __repr__(self) -> str:
        return f"<Guard {self.name}>"


class Relation(Guard):
    """Multi-field acceptance (design §5): one set of admissible *tuples*,
    two consumers — "one accepts, two consumers" extended past single
    fields, which is exactly where v2's declaration cliff was (any judgment
    relating two fields fell to ``check=`` with hand-written prose).

    - **Render** folds the set into part-bound entries: the part key fixes
      one tuple component, and the other judged field's schema gains the
      matching components as its ``enum`` — Tuesday's picker only offers
      meals that serve taco night. An item with *no* admissible tuples
      does not bind the action at all.
    - **The engine enforces membership in the same set** on invoke.

    ``accepts(r)`` / ``accepts(r, ctx)`` (per ``reads``, as with any guard)
    returns an iterable of tuples ordered like ``judges``. Comparison is by
    equality with a per-component string fallback, matching single-field
    acceptance semantics.

    A *comparison* is the special case that can't enumerate its tuples:
    ``Relation(judges=("start", "end"), op="<=", explain=…)`` — enforced
    here, advertised as ``x-display.relation`` on both fields. This is
    what v2 spelled ``relates=`` on an ordinary guard; one spelling now.
    """

    is_relation = True

    def __init__(self, *, judges: Iterable[str],
                 accepts: Callable[..., Any] | None = None,
                 op: str | None = None,
                 explain: str, reads: Iterable[str] = (),
                 vars: Iterable[str] | None = None,
                 vars_fn: Callable[[Any], Mapping[str, Any]] | None = None,
                 hide: bool = False, remedies: Iterable[str] = (),
                 becomes_available_at: Callable[[Any], datetime] | None = None,
                 requires_token: str | None = None, name: str | None = None):
        judges = tuple(judges)
        if len(judges) < 2:
            raise DefinitionError(
                f"Relation {name or ''!r} judges {list(judges)} — a "
                "single-field acceptance set is an ordinary Guard")
        if (accepts is None) == (op is None):
            raise DefinitionError(
                f"Relation {name or ''!r}: declare exactly one of accepts= "
                "(an enumerable tuple set) or op= (a comparison)")
        if op is not None:
            if op not in RELATION_OPS:
                raise DefinitionError(
                    f"Relation {name or ''!r}: op {op!r} not one of "
                    f"{sorted(RELATION_OPS)}")
            if len(judges) != 2:
                raise DefinitionError(
                    f"Relation {name or ''!r}: a comparison relates exactly "
                    f"two fields; judges={list(judges)}")
        super().__init__(explain=explain, judges=judges, reads=reads,
                         accepts=accepts, vars=vars, vars_fn=vars_fn,
                         hide=hide, remedies=remedies,
                         becomes_available_at=becomes_available_at,
                         requires_token=requires_token,
                         name=name or "relation")
        self.op = op

    @property
    def relation_admits(self) -> tuple[tuple[str, ...], Callable] | None:
        if self.accepts is None:
            return None
        return self.judges, self.accepts

    async def evaluate(self, r: Any, inp: Any, ctx: Ctx) -> tuple[Allow | Deny, "Guard"]:
        if inp is None:
            # availability probing: a relation grades input, so it cannot
            # veto an action before the form is filled
            return Allow(pending_input=True), self
        values = [getattr(inp, f, None) for f in self.judges]
        if any(v is None for v in values):
            return Allow(), self  # required-ness is the schema's job
        if self.op is not None:
            if not RELATION_OPS[self.op](values[0], values[1]):
                return Deny(vars=dict(zip(self.judges, values))), self
            return Allow(), self
        allowed = await self.admitted(r, ctx)
        if allowed is None:
            return Allow(), self
        tup = tuple(values)
        if not any(_tuple_match(tup, tuple(a)) for a in allowed):
            return Deny(vars=dict(zip(self.judges, values))), self
        return Allow(), self


def _tuple_match(got: tuple, allowed: tuple) -> bool:
    return len(got) == len(allowed) and all(
        g == a or str(g) == str(a) for g, a in zip(got, allowed))


class _AllGuard(Guard):
    """``g1 & g2``: all must allow; the first Deny wins and supplies the
    reason. Composites compose their metadata (design §1): judges union,
    reads union — v1 reported 'unknowable'."""

    def __init__(self, *parts: Guard):
        self.parts = parts
        super().__init__(
            explain=parts[0].explain,
            judges=tuple(dict.fromkeys(f for p in parts for f in p.judges)),
            reads=tuple(dict.fromkeys(t for p in parts for t in p.reads)),
            check=self._composite_check,
            hide=any(p.hide for p in parts),
            name=" & ".join(p.name for p in parts),
        )
        self.needs_input = any(p.needs_input for p in parts)

    def iter_leaves(self) -> Iterable[Guard]:
        for part in self.parts:
            yield from part.iter_leaves()

    @staticmethod
    async def _composite_check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:  # pragma: no cover
        raise AssertionError("composite guards run via evaluate()")

    async def evaluate(self, r: Any, inp: Any, ctx: Ctx) -> tuple[Allow | Deny, Guard]:
        pending = False
        for part in self.parts:
            verdict, denier = await part.evaluate(r, inp, ctx)
            if isinstance(verdict, Deny):
                return verdict, denier
            pending = pending or verdict.pending_input
        return Allow(pending_input=pending), self


class _AnyGuard(Guard):
    """``g1 | g2``: any Allow wins; if all deny, the first Deny supplies the
    reason. An OR's parts cannot tighten a schema independently, so the
    composite advertises nothing and judges nothing."""

    def __init__(self, *parts: Guard):
        self.parts = parts
        super().__init__(
            explain=parts[0].explain,
            check=_AllGuard._composite_check,
            hide=all(p.hide for p in parts),
            name=" | ".join(p.name for p in parts),
            reads=tuple(dict.fromkeys(t for p in parts for t in p.reads)),
        )
        self.needs_input = all(p.needs_input for p in parts)

    def iter_leaves(self) -> Iterable[Guard]:
        yield self

    async def evaluate(self, r: Any, inp: Any, ctx: Ctx) -> tuple[Allow | Deny, Guard]:
        first: tuple[Deny, Guard] | None = None
        for part in self.parts:
            verdict, denier = await part.evaluate(r, inp, ctx)
            if isinstance(verdict, Allow):
                return verdict, self
            if first is None:
                first = (verdict, denier)
        assert first is not None
        return first


class _GuardFactory:
    """The ``guard`` module-level object: ``@guard(...)`` decorator sugar for
    check-based guards, plus the stock guards."""

    def __call__(
        self,
        explain: str,
        *,
        judges: Iterable[str] = (),
        reads: Iterable[str] = (),
        open: Acknowledged | None = None,
        vars: Iterable[str] | None = None,
        vars_fn: Callable[[Any], Mapping[str, Any]] | None = None,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
        needs_input: bool | None = None,
    ) -> Callable[[CheckFn], Guard]:
        def decorate(fn: CheckFn) -> Guard:
            return Guard(
                explain=explain, judges=judges, reads=reads, check=fn,
                open=open, vars=vars, vars_fn=vars_fn,
                hide=hide, remedies=remedies,
                becomes_available_at=becomes_available_at,
                requires_token=requires_token, name=fn.__name__,
                needs_input=needs_input,
            )
        return decorate

    @staticmethod
    def role(name: str, *, explain: str | None = None,
             requires_token: str | None = None, hide: bool = False) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            return Allow() if name in ctx.principal.roles else Deny()

        return Guard(
            explain=explain or f"Requires role '{name}'.",
            check=check, reads=("principal",),
            requires_token=requires_token or f"role:{name}",
            hide=hide, name=f"role:{name}",
        )

    @staticmethod
    def owner(field: str = "customer_id", *, explain: str | None = None,
              hide: bool = False) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            owner_id = getattr(r.data, field, None)
            return Allow() if str(owner_id) == ctx.principal.id else Deny()

        return Guard(
            explain=explain or "Only the owner may do this.",
            check=check, reads=("principal",),
            requires_token=f"owner:{field}",
            hide=hide, name=f"owner:{field}",
        )

    @staticmethod
    def feature_flag(name: str, *, explain: str | None = None) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            flags = getattr(ctx.services, "features", None) or set()
            return Allow() if name in flags else Deny()

        return Guard(
            explain=explain or f"Feature '{name}' is not enabled.",
            check=check, reads=("services.features",),
            requires_token=f"feature:{name}",
            name=f"feature_flag:{name}",
        )

    @staticmethod
    def rate_limit(limit: int, per_seconds: float, *,
                   explain: str | None = None,
                   scope: str | None = None) -> Guard:
        """Declarative rate limit: exhaustion renders as honest
        ``unavailable`` with ``retry_at``, not a mystery 429.

        The window rides the bus (design §8): an engine-managed ctx
        carries the shared :class:`~..server.bus.RateCoordinator`, so
        every worker enforces one limit. Probe calls never consume budget.
        Windows are keyed ``(scope, principal)``; ``scope`` defaults to
        the guard's parameter shape — declare it when two same-shaped
        limiters must not share a budget. Outside an engine (unit tests,
        pure render), a guard-local window applies.
        """
        from datetime import timedelta

        name = f"rate_limit:{limit}/{per_seconds:g}s"
        key_scope = scope or name
        local: Any = None  # lazy guard-local fallback

        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            nonlocal local
            coord = ctx._rate
            if coord is None:
                if local is None:
                    from ..server.bus import RateCoordinator

                    local = RateCoordinator(None)
                coord = local
            key = f"{key_scope}:{ctx.principal.id}"
            cutoff = ctx.now - timedelta(seconds=per_seconds)
            hits = coord.window(key, cutoff)
            if len(hits) >= limit:
                return Deny(retry_at=hits[0] + timedelta(seconds=per_seconds))
            if ctx.mode == "invoke":
                await coord.hit(key, ctx.now)
            return Allow()

        return Guard(
            explain=explain or f"Rate limit of {limit} per {per_seconds:g}s exceeded.",
            check=check, reads=("now",),
            name=name,
        )


guard = _GuardFactory()
