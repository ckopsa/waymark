"""Guards (design §1): a guard is a declaration; the callable is one field of it.

A 2.0 guard declares, up front:

- ``judges`` — the input fields whose values it grades;
- ``reads`` — what beyond the document its verdict consults (kind tokens
  like ``"meal"``, ``"services.payments"``, ``"now"``). Empty means the
  verdict is a pure function of ``(resource, input)`` — document-derivable;
- ``accepts`` — THE acceptance set for its single judged field. Render folds
  it into the advertised schema as an ``enum``; the engine enforces
  membership in the same set. One declaration, two consumers, no drift;
- ``relates`` — a declared cross-field relation (``("start", "<=", "end")``),
  rendered as ``x-display.relation`` on both fields and auto-enforced when
  no ``check`` overrides it;
- ``check`` — residual logic beyond set membership, an async
  ``(r, inp, ctx) -> Allow | Deny``;
- ``open`` — an :class:`Acknowledged` sentence, the *only* way to grade a
  field while giving clients nothing to constrain it with (the closure
  rule, enforced in checks.py, makes v1's ``open_input`` warning a
  definition-time error).

There is no AST inspection anywhere in this module. What v1 recovered from
guard source, 2.0 asks for — and uses at least twice, so it cannot rot.
"""
from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable, Iterable, Mapping
from datetime import datetime
from typing import Any

from pydantic import BaseModel

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


def _fn_needs_input(fn: Callable) -> bool:
    """True when the check function's input parameter is annotated with a
    model. Such checks cannot decide without input and are probe-skipped."""
    params = list(inspect.signature(fn).parameters.values())
    if len(params) < 2:
        return False
    ann = params[1].annotation
    if ann is inspect.Parameter.empty or ann is None:
        return False
    if isinstance(ann, str):
        return ann not in ("None", "Any", "inp")
    return isinstance(ann, type) and issubclass(ann, BaseModel)


def _wants_ctx(fn: Callable) -> bool:
    try:
        return len(inspect.signature(fn).parameters) >= 2
    except (TypeError, ValueError):
        return False


class Guard:
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
        vars: Iterable[str] | Callable[[Any], Mapping[str, Any]] | None = None,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
        name: str | None = None,
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
            if op not in RELATION_OPS:
                raise DefinitionError(
                    f"guard {self.name!r}: relates op {op!r} not one of "
                    f"{sorted(RELATION_OPS)}")
            for f in (a, b):
                if f not in self.judges:
                    self.judges = (*self.judges, f)

        if accepts is not None:
            if len(self.judges) != 1:
                raise DefinitionError(
                    f"guard {self.name!r}: accepts= constrains exactly one "
                    f"judged field; judges={list(self.judges)}. Multi-field "
                    "logic belongs in check= (with relates= or open= to "
                    "satisfy the closure rule)")
            if _wants_ctx(accepts) and not self.reads:
                raise DefinitionError(
                    f"guard {self.name!r}: accepts takes (r, ctx) but "
                    "declares reads=() — say what it reads (a kind token, "
                    "'services.x', 'now') so the engine can wire the render "
                    "ctx and the checks can classify the guard")
        if accepts is None and check is None and relates is None:
            raise DefinitionError(
                f"guard {self.name!r} declares no accepts, no check, and no "
                "relates — it can neither advertise nor enforce anything")

        if callable(vars):
            self.vars_fn = vars
            self.declared_vars: frozenset[str] | None = None  # empirical: token_prose covers it
        else:
            self.vars_fn = None
            self.declared_vars = frozenset(vars) if vars is not None else frozenset()

        self.needs_input = _fn_needs_input(check) if check is not None else False

    # ── classification (what v1 AST-scanned, 2.0 declares) ─────────────
    @property
    def reads_ctx(self) -> bool:
        return bool(self.reads)

    @property
    def input_fields(self) -> frozenset[str]:
        return frozenset(self.judges)

    @property
    def admits(self) -> tuple[str, Callable] | None:
        """(field, accepts_fn) when this guard advertises an acceptance set."""
        if self.accepts is None:
            return None
        return self.judges[0], self.accepts

    # ── the acceptance set: one declaration, two consumers ─────────────
    async def admitted(self, r: Any, ctx: Any) -> list[Any] | None:
        """Evaluate ``accepts``; None = no set declared or the fn declined
        to constrain this render. Raises propagate — the caller decided to
        resolve, so a missing dependency is a loud error, not a silent
        un-tightening (design §6)."""
        if self.accepts is None:
            return None
        out = self.accepts(r, ctx) if _wants_ctx(self.accepts) else self.accepts(r)
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
        if inp is not None and self.relates is not None and self.check is None:
            a, op, b = self.relates
            va, vb = getattr(inp, a, None), getattr(inp, b, None)
            if va is not None and vb is not None and not RELATION_OPS[op](va, vb):
                return Deny(vars={a: va, b: vb}), self
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
        relates: tuple[str, str, str] | None = None,
        open: Acknowledged | None = None,
        vars: Iterable[str] | Callable[[Any], Mapping[str, Any]] | None = None,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
    ) -> Callable[[CheckFn], Guard]:
        def decorate(fn: CheckFn) -> Guard:
            return Guard(
                explain=explain, judges=judges, reads=reads, check=fn,
                relates=relates, open=open, vars=vars, hide=hide,
                remedies=remedies, becomes_available_at=becomes_available_at,
                requires_token=requires_token, name=fn.__name__,
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
                   explain: str | None = None) -> Guard:
        """Declarative rate limit: exhaustion renders as honest
        ``unavailable`` with ``retry_at``, not a mystery 429.

        In-memory per-process window keyed by (principal, guard); probe
        calls do not consume budget. Distributed limiting can ride the Bus
        (design §7) — not built in v2.0.
        """
        from datetime import timedelta

        windows: dict[str, list[datetime]] = {}

        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            cutoff = ctx.now - timedelta(seconds=per_seconds)
            hits = [t for t in windows.get(ctx.principal.id, []) if t > cutoff]
            if len(hits) >= limit:
                return Deny(retry_at=hits[0] + timedelta(seconds=per_seconds))
            if ctx.mode == "invoke":
                hits.append(ctx.now)
            windows[ctx.principal.id] = hits
            return Allow()

        return Guard(
            explain=explain or f"Rate limit of {limit} per {per_seconds:g}s exceeded.",
            check=check, reads=("now",),
            name=f"rate_limit:{limit}/{per_seconds:g}s",
        )


guard = _GuardFactory()
