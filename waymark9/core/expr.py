"""Expressions (design 8.0 §1): the law's pure functions, as data.

A derivation's ``fn=`` and a pure guard's ``check=`` are pure functions
of declared inputs — and 7.0 drew its capability boundary exactly there:
declared *parameters* (``Tolerance``, ``where=``) can be held at
``proposed``, piloted per-population, and grandfathered exactly, because
the definition store can read them back out of a stored fingerprint;
*code* cannot, because a function body fingerprints as a hash. This
module deletes that cliff for every declaration that takes the offer:
an :class:`Expr` is a small, total, JSON-serializable tree —
``to_wire``/``from_wire`` round-trip exactly — so the fingerprint stores
the law itself, the diff pins the leaf that moved, and the overlay
(``server/derived.py``) evaluates a non-resident revision's tree
verbatim.

The vocabulary is earned, not designed (design 8.0 §1): literals, input
references, attribute access, comparison, boolean composition,
arithmetic, day offsets, date coercion, min/max, presence tests, and
the four quantifiers over list inputs — exactly what the ported
domains' ``fn=``/pure-``check=`` bodies wrote, nothing more. No calls,
no recursion, no names beyond the declared scope, no reads, no string
building: evaluation always terminates and can touch nothing the
declaration didn't name — the purity ``DerivedSpec.apply`` always
promised, now structural instead of trusted.

Two scopes, one language:

- **derived**: inputs bind by declared name, positionally matching
  ``over=`` (``E.f("weeks")``; ``Clock`` binds as ``"now"``, an edge
  input as ``"kind.field"``). Checked at declaration: a name the
  ``over=`` doesn't carry is a :class:`DefinitionError`.
- **guard** (``guard.expr``, design 8.0 §4): role references instead —
  ``E.data("start_date")``, ``E.input("meal_id")``, ``E.now()`` — over
  ``(row, input, clock)``. The roles a tree uses ARE its declaration:
  ``judges`` and ``reads`` derive from the tree, never sniffed.

Numeric semantics: ``E.num("0.02")`` is a decimal literal, and any
comparison or arithmetic with a ``Decimal`` operand promotes the other
side through ``Decimal(str(x))`` — the ``Tolerance`` discipline,
generalized, so a tolerance spelled as an expression judges the same
bytes the ``within=`` shorthand does. Ordering comparisons with a
``None`` operand are ``False`` (a missing value satisfies no ordering
claim — required-ness is the schema's job, the Relation precedent);
arithmetic over ``None`` propagates ``None``.
"""
from __future__ import annotations

from dataclasses import dataclass, field as dc_field
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any, Callable, Iterable, Mapping

from .types import DefinitionError

_MISSING = object()


@dataclass
class Scope:
    """One evaluation's bound names: ``vars`` for the derived calling
    convention (over-input name → resolved value), the guard roles
    (``data``/``input``/``now``), and the quantifier item."""

    vars: Mapping[str, Any] = dc_field(default_factory=dict)
    data: Any = None
    input: Any = None
    now: Any = None
    it: Any = _MISSING

    def with_it(self, item: Any) -> "Scope":
        return Scope(vars=self.vars, data=self.data, input=self.input,
                     now=self.now, it=item)


def _get(obj: Any, attr: str) -> Any:
    if obj is None:
        return None
    if isinstance(obj, Mapping):
        return obj.get(attr)
    return getattr(obj, attr, None)


def _promote(a: Any, b: Any) -> tuple[Any, Any]:
    """The Decimal discipline (module docstring): one Decimal operand
    promotes the other, so expression tolerances judge exactly."""
    if isinstance(a, Decimal) and not isinstance(b, Decimal) and b is not None:
        return a, Decimal(str(b))
    if isinstance(b, Decimal) and not isinstance(a, Decimal) and a is not None:
        return Decimal(str(a)), b
    return a, b


class Expr:
    """Base node. Subclasses are frozen dataclasses with ``eq=False`` —
    node identity is object identity; *semantic* equality is wire
    equality (``a.to_wire() == b.to_wire()``), which is also what the
    fingerprint compares. Operators build trees; nothing evaluates until
    the engine binds a scope."""

    def to_wire(self) -> dict[str, Any]:  # pragma: no cover - abstract
        raise NotImplementedError

    def eval(self, scope: Scope) -> Any:  # pragma: no cover - abstract
        raise NotImplementedError

    # ── tree-building sugar: arithmetic ───────────────────────────────
    def __add__(self, other: Any) -> "Expr":
        return Arith("+", self, wrap(other))

    def __radd__(self, other: Any) -> "Expr":
        return Arith("+", wrap(other), self)

    def __sub__(self, other: Any) -> "Expr":
        return Arith("-", self, wrap(other))

    def __rsub__(self, other: Any) -> "Expr":
        return Arith("-", wrap(other), self)

    def __mul__(self, other: Any) -> "Expr":
        return Arith("*", self, wrap(other))

    def __rmul__(self, other: Any) -> "Expr":
        return Arith("*", wrap(other), self)

    def __truediv__(self, other: Any) -> "Expr":
        return Arith("/", self, wrap(other))

    def __mod__(self, other: Any) -> "Expr":
        return Arith("%", self, wrap(other))

    # ── ordering builds Cmp nodes; eq/ne are methods (overriding __eq__
    # would poison every dict/set a spec object passes through) ────────
    def __lt__(self, other: Any) -> "Expr":
        return Cmp("lt", self, wrap(other))

    def __le__(self, other: Any) -> "Expr":
        return Cmp("le", self, wrap(other))

    def __gt__(self, other: Any) -> "Expr":
        return Cmp("gt", self, wrap(other))

    def __ge__(self, other: Any) -> "Expr":
        return Cmp("ge", self, wrap(other))

    def eq(self, other: Any) -> "Expr":
        return Cmp("eq", self, wrap(other))

    def ne(self, other: Any) -> "Expr":
        return Cmp("ne", self, wrap(other))

    # ── boolean composition ───────────────────────────────────────────
    def __and__(self, other: Any) -> "Expr":
        return And((self, wrap(other)))

    def __or__(self, other: Any) -> "Expr":
        return Or((self, wrap(other)))

    def __invert__(self) -> "Expr":
        return Not(self)

    # ── the unary vocabulary ──────────────────────────────────────────
    def abs(self) -> "Expr":
        return Abs(self)

    def is_set(self) -> "Expr":
        return IsSet(self)

    def is_null(self) -> "Expr":
        return Not(IsSet(self))


def wrap(value: Any) -> Expr:
    """A plain Python value in tree position is a literal."""
    if isinstance(value, Expr):
        return value
    if isinstance(value, Decimal):
        return Num(str(value))
    if isinstance(value, (datetime, date)):
        return Lit(value.isoformat())
    if value is None or isinstance(value, (bool, int, float, str)):
        return Lit(value)
    raise DefinitionError(
        f"expression literal {value!r} is not a JSON scalar — the tree "
        "must serialize exactly (design 8.0 §1)")


@dataclass(frozen=True, eq=False)
class Lit(Expr):
    value: Any

    def to_wire(self) -> dict[str, Any]:
        return {"lit": self.value}

    def eval(self, scope: Scope) -> Any:
        return self.value


@dataclass(frozen=True, eq=False)
class Num(Expr):
    """A decimal literal — THE tolerance spelling, kept exact. Stored as
    its literal text; evaluates as ``Decimal``, which promotes whatever
    it meets (see ``_promote``)."""

    value: str

    def __post_init__(self) -> None:
        try:
            Decimal(self.value)
        except InvalidOperation:
            raise DefinitionError(
                f"E.num({self.value!r}) is not a decimal literal") from None

    def to_wire(self) -> dict[str, Any]:
        return {"num": self.value}

    def eval(self, scope: Scope) -> Any:
        return Decimal(self.value)


@dataclass(frozen=True, eq=False)
class Var(Expr):
    """A derived input by declared name (``Clock`` → ``"now"``, an edge
    input → ``"kind.field"``) — the derived calling convention."""

    name: str

    def to_wire(self) -> dict[str, Any]:
        return {"var": self.name}

    def eval(self, scope: Scope) -> Any:
        if self.name not in scope.vars:
            raise DefinitionError(
                f"expression names input {self.name!r} which this "
                "evaluation did not bind — the declaration check should "
                "have refused it")
        return scope.vars[self.name]


@dataclass(frozen=True, eq=False)
class It(Expr):
    """The quantifier item. Attribute access builds :class:`Get`:
    ``E.it.have`` — the one place the tree reaches inside a list item."""

    def to_wire(self) -> dict[str, Any]:
        return {"it": None}

    def eval(self, scope: Scope) -> Any:
        if scope.it is _MISSING:
            raise DefinitionError(
                "E.it used outside a quantifier — the declaration check "
                "should have refused it")
        return scope.it

    def __getattr__(self, name: str) -> Expr:
        if name.startswith("_"):
            raise AttributeError(name)
        return Get(self, name)


@dataclass(frozen=True, eq=False)
class Get(Expr):
    of: Expr
    attr: str

    def to_wire(self) -> dict[str, Any]:
        return {"get": {"of": self.of.to_wire(), "attr": self.attr}}

    def eval(self, scope: Scope) -> Any:
        return _get(self.of.eval(scope), self.attr)

    def __getattr__(self, name: str) -> Expr:
        if name.startswith("_"):
            raise AttributeError(name)
        return Get(self, name)


@dataclass(frozen=True, eq=False)
class DataRef(Expr):
    """A row field, by name — the guard scope's ``E.data(...)``. At
    create there is no row yet; a DataRef then reads the value the same
    create would materialize when the caller binds one (guards.py binds
    the computed view), or ``None``."""

    name: str

    def to_wire(self) -> dict[str, Any]:
        return {"data": self.name}

    def eval(self, scope: Scope) -> Any:
        return _get(scope.data, self.name)


@dataclass(frozen=True, eq=False)
class InputRef(Expr):
    name: str

    def to_wire(self) -> dict[str, Any]:
        return {"input": self.name}

    def eval(self, scope: Scope) -> Any:
        return _get(scope.input, self.name)


@dataclass(frozen=True, eq=False)
class NowRef(Expr):
    def to_wire(self) -> dict[str, Any]:
        return {"now": None}

    def eval(self, scope: Scope) -> Any:
        return scope.now


_CMP: dict[str, Callable[[Any, Any], bool]] = {
    "eq": lambda a, b: a == b,
    "ne": lambda a, b: a != b,
    "lt": lambda a, b: a < b,
    "le": lambda a, b: a <= b,
    "gt": lambda a, b: a > b,
    "ge": lambda a, b: a >= b,
}
_ORDERING = frozenset({"lt", "le", "gt", "ge"})


@dataclass(frozen=True, eq=False)
class Cmp(Expr):
    op: str
    left: Expr
    right: Expr

    def __post_init__(self) -> None:
        if self.op not in _CMP:
            raise DefinitionError(f"comparison op {self.op!r} is not one "
                                  f"of {sorted(_CMP)}")

    def to_wire(self) -> dict[str, Any]:
        return {"cmp": {"op": self.op, "left": self.left.to_wire(),
                        "right": self.right.to_wire()}}

    def eval(self, scope: Scope) -> Any:
        a, b = self.left.eval(scope), self.right.eval(scope)
        if self.op in _ORDERING and (a is None or b is None):
            return False  # a missing value satisfies no ordering claim
        a, b = _promote(a, b)
        return _CMP[self.op](a, b)


@dataclass(frozen=True, eq=False)
class And(Expr):
    parts: tuple[Expr, ...]

    def to_wire(self) -> dict[str, Any]:
        return {"and": [p.to_wire() for p in self.parts]}

    def eval(self, scope: Scope) -> Any:
        return all(bool(p.eval(scope)) for p in self.parts)


@dataclass(frozen=True, eq=False)
class Or(Expr):
    parts: tuple[Expr, ...]

    def to_wire(self) -> dict[str, Any]:
        return {"or": [p.to_wire() for p in self.parts]}

    def eval(self, scope: Scope) -> Any:
        return any(bool(p.eval(scope)) for p in self.parts)


@dataclass(frozen=True, eq=False)
class Not(Expr):
    of: Expr

    def to_wire(self) -> dict[str, Any]:
        return {"not": self.of.to_wire()}

    def eval(self, scope: Scope) -> Any:
        return not self.of.eval(scope)


_ARITH: dict[str, Callable[[Any, Any], Any]] = {
    "+": lambda a, b: a + b,
    "-": lambda a, b: a - b,
    "*": lambda a, b: a * b,
    "/": lambda a, b: a / b,
    "//": lambda a, b: a // b,
    "%": lambda a, b: a % b,
}


@dataclass(frozen=True, eq=False)
class Arith(Expr):
    op: str
    left: Expr
    right: Expr

    def __post_init__(self) -> None:
        if self.op not in _ARITH:
            raise DefinitionError(f"arithmetic op {self.op!r} is not one "
                                  f"of {sorted(_ARITH)}")

    def to_wire(self) -> dict[str, Any]:
        return {"arith": {"op": self.op, "left": self.left.to_wire(),
                          "right": self.right.to_wire()}}

    def eval(self, scope: Scope) -> Any:
        a, b = self.left.eval(scope), self.right.eval(scope)
        if a is None or b is None:
            return None  # arithmetic over a missing value is missing
        a, b = _promote(a, b)
        return _ARITH[self.op](a, b)


@dataclass(frozen=True, eq=False)
class MinMax(Expr):
    kind: str  # "min" | "max"
    parts: tuple[Expr, ...]

    def to_wire(self) -> dict[str, Any]:
        return {self.kind: [p.to_wire() for p in self.parts]}

    def eval(self, scope: Scope) -> Any:
        values = [v for p in self.parts
                  if (v := p.eval(scope)) is not None]
        if not values:
            return None
        if len({type(v) for v in values} & {Decimal}) == 1:
            values = [Decimal(str(v)) if not isinstance(v, Decimal) else v
                      for v in values]
        return min(values) if self.kind == "min" else max(values)


@dataclass(frozen=True, eq=False)
class Abs(Expr):
    of: Expr

    def to_wire(self) -> dict[str, Any]:
        return {"abs": self.of.to_wire()}

    def eval(self, scope: Scope) -> Any:
        value = self.of.eval(scope)
        return None if value is None else abs(value)


@dataclass(frozen=True, eq=False)
class Days(Expr):
    """A day offset — the date-arithmetic vocabulary the domains wrote
    (``start + timedelta(days=7*weeks-1)``)."""

    of: Expr

    def to_wire(self) -> dict[str, Any]:
        return {"days": self.of.to_wire()}

    def eval(self, scope: Scope) -> Any:
        value = self.of.eval(scope)
        if value is None:
            return None
        if isinstance(value, Decimal):
            value = float(value)
        return timedelta(days=value)


@dataclass(frozen=True, eq=False)
class DateOf(Expr):
    """Coerce a datetime to its UTC calendar date — the ``now.date()``
    half of every clock-vs-date comparison."""

    of: Expr

    def to_wire(self) -> dict[str, Any]:
        return {"date": self.of.to_wire()}

    def eval(self, scope: Scope) -> Any:
        value = self.of.eval(scope)
        if isinstance(value, datetime):
            return value.astimezone(UTC).date()
        return value


@dataclass(frozen=True, eq=False)
class IsSet(Expr):
    of: Expr

    def to_wire(self) -> dict[str, Any]:
        return {"is_set": self.of.to_wire()}

    def eval(self, scope: Scope) -> Any:
        return self.of.eval(scope) is not None


@dataclass(frozen=True, eq=False)
class Quant(Expr):
    """The four quantifiers over a list input. ``all``/``any`` take a
    required item predicate; ``count`` an optional one; ``sum`` an
    optional item projection (default: the item itself) with a per-item
    ``default`` replacing ``None`` — ``sum(v or 0 for v in vs)``, said
    honestly. Empty lists: ``all`` → True, ``any`` → False, ``count`` →
    0, ``sum`` → 0 — Python's own semantics, which the lambdas relied
    on."""

    kind: str  # "all" | "any" | "count" | "sum"
    over: Expr
    where: Expr | None = None
    of: Expr | None = None
    default: Any = 0

    def __post_init__(self) -> None:
        if self.kind not in ("all", "any", "count", "sum"):
            raise DefinitionError(f"quantifier {self.kind!r} is not one of "
                                  "all/any/count/sum")
        if self.kind in ("all", "any") and self.where is None:
            raise DefinitionError(f"E.{self.kind}(...) requires an item "
                                  "predicate")
        if self.of is not None and self.kind != "sum":
            raise DefinitionError("of= is sum's projection; "
                                  f"{self.kind} takes where=")

    def to_wire(self) -> dict[str, Any]:
        inner: dict[str, Any] = {"over": self.over.to_wire()}
        if self.where is not None:
            inner["where"] = self.where.to_wire()
        if self.kind == "sum":
            inner["of"] = (self.of or It()).to_wire()
            inner["default"] = self.default
        return {self.kind: inner}

    def eval(self, scope: Scope) -> Any:
        items = self.over.eval(scope) or ()
        if self.kind == "all":
            return all(bool(self.where.eval(scope.with_it(i)))  # type: ignore[union-attr]
                       for i in items)
        if self.kind == "any":
            return any(bool(self.where.eval(scope.with_it(i)))  # type: ignore[union-attr]
                       for i in items)
        if self.where is not None:
            items = [i for i in items
                     if bool(self.where.eval(scope.with_it(i)))]
        if self.kind == "count":
            return len(list(items))
        proj = self.of or It()
        total: Any = 0
        for i in items:
            value = proj.eval(scope.with_it(i))
            total = total + (self.default if value is None else value)
        return total


# ── the wire, read back (design 8.0 §3: what the overlay evaluates) ─────
def from_wire(data: Any) -> Expr:
    """Reconstruct a tree from its stored form. This is the §3 seam: a
    non-resident revision's law is exactly what this returns from its
    fingerprint entry — refuse anything unknown loudly; a law that
    parses approximately is a law served approximately."""
    if not isinstance(data, Mapping) or len(data) != 1:
        raise DefinitionError(f"expression wire node {data!r} must be a "
                              "single-key object")
    key, value = next(iter(data.items()))
    match key:
        case "lit":
            return Lit(value)
        case "num":
            return Num(value)
        case "var":
            return Var(value)
        case "it":
            return It()
        case "get":
            return Get(from_wire(value["of"]), value["attr"])
        case "data":
            return DataRef(value)
        case "input":
            return InputRef(value)
        case "now":
            return NowRef()
        case "cmp":
            return Cmp(value["op"], from_wire(value["left"]),
                       from_wire(value["right"]))
        case "and":
            return And(tuple(from_wire(v) for v in value))
        case "or":
            return Or(tuple(from_wire(v) for v in value))
        case "not":
            return Not(from_wire(value))
        case "arith":
            return Arith(value["op"], from_wire(value["left"]),
                         from_wire(value["right"]))
        case "min" | "max":
            return MinMax(key, tuple(from_wire(v) for v in value))
        case "abs":
            return Abs(from_wire(value))
        case "days":
            return Days(from_wire(value))
        case "date":
            return DateOf(from_wire(value))
        case "is_set":
            return IsSet(from_wire(value))
        case "all" | "any" | "count" | "sum":
            return Quant(
                key, from_wire(value["over"]),
                where=(from_wire(value["where"])
                       if value.get("where") is not None else None),
                of=(from_wire(value["of"])
                    if key == "sum" and value.get("of") is not None else None),
                default=value.get("default", 0))
    raise DefinitionError(f"unknown expression node {key!r} — a law that "
                          "cannot be read back cannot be served")


# ── declaration-time validation (design 8.0 §1: checked at import) ──────
@dataclass
class ExprInfo:
    """What a tree declares by using: consumed by ``Derived`` (names must
    be over-inputs) and ``guard.expr`` (judges/reads derive from roles)."""

    vars: set[str] = dc_field(default_factory=set)
    data: set[str] = dc_field(default_factory=set)
    inputs: set[str] = dc_field(default_factory=set)
    uses_now: bool = False


def _walk(node: Expr, info: ExprInfo, depth: int) -> None:
    if isinstance(node, Var):
        info.vars.add(node.name)
    elif isinstance(node, DataRef):
        info.data.add(node.name)
    elif isinstance(node, InputRef):
        info.inputs.add(node.name)
    elif isinstance(node, NowRef):
        info.uses_now = True
    elif isinstance(node, It) and depth == 0:
        raise DefinitionError("E.it is the quantifier item; it means "
                              "nothing outside all/any/count/sum")
    for child, inner in _children(node, depth):
        _walk(child, info, inner)


def _children(node: Expr, depth: int) -> Iterable[tuple[Expr, int]]:
    if isinstance(node, (Get, Not, Abs, Days, DateOf, IsSet)):
        yield node.of, depth
    elif isinstance(node, (Cmp, Arith)):
        yield node.left, depth
        yield node.right, depth
    elif isinstance(node, (And, Or, MinMax)):
        for p in node.parts:
            yield p, depth
    elif isinstance(node, Quant):
        yield node.over, depth
        if node.where is not None:
            yield node.where, depth + 1
        if node.of is not None:
            yield node.of, depth + 1


def expr_info(node: Expr) -> ExprInfo:
    info = ExprInfo()
    _walk(node, info, 0)
    return info


def check_derived_expr(node: Expr, allowed: Iterable[str],
                       owner: str) -> None:
    """A derived expression speaks only its ``over=`` inputs' names —
    role references belong to the guard scope."""
    info = expr_info(node)
    if info.data or info.inputs or info.uses_now:
        raise DefinitionError(
            f"{owner}: E.data()/E.input()/E.now() are guard-scope "
            "references; a derivation's inputs are its over= entries "
            "(Clock binds as 'now')")
    unknown = info.vars - set(allowed)
    if unknown:
        raise DefinitionError(
            f"{owner}: expression names input(s) {sorted(unknown)} not in "
            f"over= (declared: {sorted(allowed)})")


def check_guard_expr(node: Expr, owner: str) -> ExprInfo:
    """A guard expression speaks roles, never bare Vars; what it uses is
    its declaration (judges from inputs, reads from now)."""
    info = expr_info(node)
    if info.vars:
        raise DefinitionError(
            f"{owner}: bare E.f() references are the derived calling "
            "convention; a guard expression reads E.data()/E.input()/"
            "E.now()")
    return info


# ── the builder ──────────────────────────────────────────────────────────
class _E:
    """``E`` — tree-building sugar. Every product is an :class:`Expr`;
    the tree, not the spelling, is the law."""

    it = It()

    @staticmethod
    def f(name: str) -> Expr:
        """A derived input by declared name (``E.f("weeks")``)."""
        return Var(name)

    @staticmethod
    def lit(value: Any) -> Expr:
        return wrap(value)

    @staticmethod
    def num(literal: str) -> Expr:
        return Num(literal)

    @staticmethod
    def data(name: str) -> Expr:
        return DataRef(name)

    @staticmethod
    def input(name: str) -> Expr:
        return InputRef(name)

    @staticmethod
    def now() -> Expr:
        return NowRef()

    @staticmethod
    def date(of: Any) -> Expr:
        return DateOf(wrap(of))

    @staticmethod
    def days(of: Any) -> Expr:
        return Days(wrap(of))

    @staticmethod
    def min(*parts: Any) -> Expr:
        return MinMax("min", tuple(wrap(p) for p in parts))

    @staticmethod
    def max(*parts: Any) -> Expr:
        return MinMax("max", tuple(wrap(p) for p in parts))

    @staticmethod
    def all(over: Any, where: Any) -> Expr:
        return Quant("all", wrap(over), where=wrap(where))

    @staticmethod
    def any(over: Any, where: Any) -> Expr:
        return Quant("any", wrap(over), where=wrap(where))

    @staticmethod
    def count(over: Any, where: Any = None) -> Expr:
        return Quant("count", wrap(over),
                     where=None if where is None else wrap(where))

    @staticmethod
    def sum(over: Any, of: Any = None, *, where: Any = None,
            default: Any = 0) -> Expr:
        return Quant("sum", wrap(over),
                     where=None if where is None else wrap(where),
                     of=None if of is None else wrap(of),
                     default=default)


E = _E()
