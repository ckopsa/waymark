"""Guards (§10.2): one async function is both the advertisement and the enforcement.

A guard runs in two phases with a single implementation:

- **probe** (render time): called with ``inp=None`` to sort transitions into
  ``actions`` / ``unavailable`` / hidden. Guards whose function requires a
  typed input are short-circuited to ``Allow(pending_input=True)`` without
  being called.
- **invoke**: called with the validated input; a ``Deny`` becomes a 409 whose
  detail is exactly the string ``unavailable.reason`` would have shown.

``evaluate()`` returns ``(verdict, denier)`` so composites (``g1 & g2``)
report *which* guard denied without shared mutable state; the denier supplies
the reason template, remedies and hiding.
"""
from __future__ import annotations

import ast
import inspect
import textwrap
from collections.abc import Awaitable, Callable, Iterable
from datetime import datetime
from typing import Any

from pydantic import BaseModel

from .types import Allow, Ctx, Deny

GuardFn = Callable[[Any, Any, Ctx], Awaitable[Allow | Deny]]


class _SafeDict(dict):
    def __missing__(self, key: str) -> str:  # never crash rendering a reason
        return "{" + key + "}"


def _fn_needs_input(fn: Callable) -> bool:
    """True when the guard function's input parameter is annotated with a model.

    Such guards cannot decide without input and are probe-skipped.
    Unannotated (or ``None``-annotated) input params run in both phases.
    """
    params = list(inspect.signature(fn).parameters.values())
    if len(params) < 2:
        return False
    ann = params[1].annotation
    if ann is inspect.Parameter.empty or ann is None:
        return False
    if isinstance(ann, str):
        return ann not in ("None", "Any", "inp")
    return isinstance(ann, type) and issubclass(ann, BaseModel)


def _scan_deny_vars(fn: Callable) -> frozenset[str]:
    """Statically collect keys of ``Deny(vars={...})`` calls in the guard source."""
    try:
        src = textwrap.dedent(inspect.getsource(fn))
        tree = ast.parse(src)
    except (OSError, TypeError, SyntaxError):
        return frozenset()
    found: set[str] = set()
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                and node.func.id == "Deny"):
            continue
        for kw in node.keywords:
            if kw.arg == "vars" and isinstance(kw.value, ast.Dict):
                for key in kw.value.keys:
                    if isinstance(key, ast.Constant) and isinstance(key.value, str):
                        found.add(key.value)
    return frozenset(found)


def _scan_dependencies(fn: Callable) -> tuple[bool, frozenset[str] | None]:
    """Statically classify what a guard's verdict can depend on.

    Returns ``(reads_ctx, input_fields)``:

    - ``reads_ctx`` — the ctx parameter is referenced anywhere in the body.
      A guard that never touches ctx is *document-derivable*: its verdict is
      a pure function of ``(resource, input)``, so everything it refuses was
      knowable at render time.
    - ``input_fields`` — the set of ``inp.<field>`` attributes accessed, or
      ``None`` when ``inp`` escapes wholesale (passed to a helper, iterated…)
      and the field set is unknowable.

    Unreadable source degrades to ``(True, None)`` — conservatively impure.
    """
    try:
        params = list(inspect.signature(fn).parameters.values())
        src = textwrap.dedent(inspect.getsource(fn))
        tree = ast.parse(src)
    except (OSError, TypeError, ValueError, SyntaxError):
        return True, None
    inp_name = params[1].name if len(params) > 1 else None
    ctx_name = params[2].name if len(params) > 2 else None

    fields: set[str] = set()
    attr_bases: set[int] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) \
                and node.value.id == inp_name:
            fields.add(node.attr)
            attr_bases.add(id(node.value))

    names = [n for n in ast.walk(tree) if isinstance(n, ast.Name)]
    reads_ctx = any(n.id == ctx_name for n in names)
    escapes = any(n.id == inp_name and id(n) not in attr_bases for n in names)
    return reads_ctx, None if escapes else frozenset(fields)


class Guard:
    def __init__(
        self,
        fn: GuardFn,
        *,
        else_: str,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
        vars: Iterable[str] | None = None,
        name: str | None = None,
        needs_input: bool | None = None,
        admits: tuple[str, Callable[[Any], Iterable[Any]]] | None = None,
    ):
        self.fn = fn
        self.else_ = else_
        self.hide = hide
        self.remedies = tuple(remedies)
        self.becomes_available_at = becomes_available_at
        self.requires_token = requires_token
        self.name = name or getattr(fn, "__name__", "guard")
        self.needs_input = _fn_needs_input(fn) if needs_input is None else needs_input
        self.declared_vars = (
            frozenset(vars) if vars is not None else _scan_deny_vars(fn)
        )
        # ``admits=(field, fn)`` declares the guard's acceptance set for one
        # input field. ``fn(r)`` derives it from the document; ``fn(r, ctx)``
        # (sync or async) may read other resources/services at render time.
        # Render folds the result into the advertised input schema as an
        # enum, so clients never offer a value this guard would refuse.
        # Advertisement only — the guard body stays the enforcement;
        # conformance checks the two agree. Returning None declines to
        # constrain that render (e.g. no rotation linked → any theme goes).
        self.admits = admits
        self.reads_ctx, self.input_fields = _scan_dependencies(fn)

    async def admitted(self, r: Any, ctx: Any) -> list[Any] | None:
        """Evaluate the admits declaration; None = no constraint declared or
        the fn declined. Raises propagate — rendering decides tolerance."""
        if self.admits is None:
            return None
        _, fn = self.admits
        wants_ctx = len(inspect.signature(fn).parameters) >= 2
        out = fn(r, ctx) if wants_ctx else fn(r)
        if inspect.isawaitable(out):
            out = await out
        return None if out is None else list(out)

    async def evaluate(self, r: Any, inp: Any, ctx: Ctx) -> tuple[Allow | Deny, "Guard"]:
        """Return (verdict, denier). For a leaf guard the denier is itself."""
        if ctx.mode == "probe" and inp is None and self.needs_input:
            return Allow(pending_input=True), self
        return await self.fn(r, inp, ctx), self

    async def __call__(self, r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
        verdict, _ = await self.evaluate(r, inp, ctx)
        return verdict

    def render_reason(self, deny: Deny, r: Any = None) -> str:
        return self.else_.format_map(_SafeDict(deny.vars or {}))

    def becomes_available(self, deny: Deny, r: Any = None) -> dict[str, Any] | None:
        """Structured hope (§3): ``at`` / ``requires``; ``in_states`` is render's job."""
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
    """``g1 & g2``: all must allow; the first Deny wins and supplies the reason."""

    def __init__(self, *parts: Guard):
        self.parts = parts
        super().__init__(
            self._never, else_=parts[0].else_,
            hide=any(p.hide for p in parts),
            name=" & ".join(p.name for p in parts),
            needs_input=any(p.needs_input for p in parts),
            vars=(),
        )
        self.reads_ctx = any(p.reads_ctx for p in parts)
        self.input_fields = None

    def iter_leaves(self) -> Iterable[Guard]:
        for part in self.parts:
            yield from part.iter_leaves()

    @staticmethod
    async def _never(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:  # pragma: no cover
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
    """``g1 | g2``: any Allow wins; if all deny, the first Deny supplies the reason."""

    def __init__(self, *parts: Guard):
        self.parts = parts
        super().__init__(
            _AllGuard._never, else_=parts[0].else_,
            hide=all(p.hide for p in parts),
            name=" | ".join(p.name for p in parts),
            needs_input=all(p.needs_input for p in parts),
            vars=(),
        )
        self.reads_ctx = any(p.reads_ctx for p in parts)
        self.input_fields = None

    def iter_leaves(self) -> Iterable[Guard]:
        # an OR's leaves can't tighten a schema independently (any one part
        # allowing suffices), so the composite contributes no admits and its
        # parts are not surfaced for schema-gap analysis
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
    """The ``guard`` module-level object: ``@guard(...)`` and ``guard.role(...)``."""

    def __call__(
        self,
        else_: str,
        *,
        hide: bool = False,
        remedies: Iterable[str] = (),
        becomes_available_at: Callable[[Any], datetime] | None = None,
        requires_token: str | None = None,
        vars: Iterable[str] | None = None,
        admits: tuple[str, Callable[[Any], Iterable[Any]]] | None = None,
    ) -> Callable[[GuardFn], Guard]:
        def decorate(fn: GuardFn) -> Guard:
            return Guard(
                fn, else_=else_, hide=hide, remedies=remedies,
                becomes_available_at=becomes_available_at,
                requires_token=requires_token, vars=vars, admits=admits,
            )
        return decorate

    @staticmethod
    def role(name: str, *, else_: str | None = None,
             requires_token: str | None = None, hide: bool = False) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            return Allow() if name in ctx.principal.roles else Deny()

        return Guard(
            check,
            else_=else_ or f"Requires role '{name}'.",
            requires_token=requires_token or f"role:{name}",
            hide=hide, name=f"role:{name}", needs_input=False, vars=(),
        )

    @staticmethod
    def owner(field: str = "customer_id", *, else_: str | None = None,
              hide: bool = False) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            owner_id = getattr(r.data, field, None)
            return Allow() if str(owner_id) == ctx.principal.id else Deny()

        return Guard(
            check,
            else_=else_ or "Only the owner may do this.",
            requires_token=f"owner:{field}",
            hide=hide, name=f"owner:{field}", needs_input=False, vars=(),
        )

    @staticmethod
    def feature_flag(name: str, *, else_: str | None = None) -> Guard:
        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            flags = getattr(ctx.services, "features", None) or set()
            return Allow() if name in flags else Deny()

        return Guard(
            check,
            else_=else_ or f"Feature '{name}' is not enabled.",
            requires_token=f"feature:{name}",
            name=f"feature_flag:{name}", needs_input=False, vars=(),
        )

    @staticmethod
    def rate_limit(limit: int, per_seconds: float, *,
                   else_: str | None = None) -> Guard:
        """Declarative rate limit (§15): exhaustion renders as honest
        ``unavailable`` with ``retry_at``, not a mystery 429.

        In-memory per-process window keyed by (principal, guard); probe calls
        do not consume budget.
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
            check,
            else_=else_ or f"Rate limit of {limit} per {per_seconds:g}s exceeded.",
            name=f"rate_limit:{limit}/{per_seconds:g}s", needs_input=False, vars=(),
        )


guard = _GuardFactory()
