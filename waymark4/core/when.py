"""Conditional demand (design §5): requiredness governed by a declared
predicate.

The deliverable tracker's scar: "if completing late, ``client_caused``
and a reason are required; otherwise forbidden" — requiredness governed
by a runtime comparison, expressible in 3.0 only as refuse-after-submit,
the trial-and-error 3.0 §5 exists to kill. 4.0 declares it on the input
model, the way :class:`~.groups.OneOf` is declared today::

    class CompleteInput(BaseModel):
        date_completed: date
        client_caused: bool | None = None
        reason: str | None = None

        late: ClassVar[When] = When(
            ("date_completed", ">", Field.of("due_date")),
            requires=("client_caused", "reason"),
            forbids_otherwise=True)

(``ClassVar`` keeps pydantic from reading the declaration as a field,
exactly as with ``OneOf``.) The predicate compares an input field against
either another input field (a plain string) or the *resource's* current
data (``Field.of("due_date")``). One declaration, three consumers:

- **The wire schema** carries it as JSON Schema ``if``/``then`` (and
  ``else`` when ``forbids_otherwise``), with a resource-field comparand
  resolved to its current value at render — per-resource schemas are the
  established shape (accepts= enums fold the same way), so the generic
  client reveals the conditional arm the moment the value crosses the
  line, from the schema alone. ISO-8601 date/date-time comparands are
  emitted through the numeric comparison keywords
  (``minimum``/``exclusiveMinimum``/…): same-format ISO strings order
  lexicographically exactly as they order temporally, so the one keyword
  family carries both — a deliberate liberty, stated here once. An
  input-vs-input comparand has no constant to resolve, so no
  ``if``/``then`` is emitted for it; enforcement still runs.
- **The invoker** enforces the same predicate at validation time —
  before guards, alongside schema validation, producing field-keyed 422s
  (:func:`when_errors`).
- **The demand class** reflects the base branch: conditionally-required
  fields are excluded from ``effort`` (:func:`conditional_fields`), and
  the ``if``/``then`` block is the honest advertisement of the
  conditional arm — the entry reads ``assent`` on time and the schema
  says what lateness will additionally demand.

Predicate semantics: when either comparand is absent (None), the
predicate does not hold — the base branch. When it holds, every
``requires=`` field must be present; when it does not and
``forbids_otherwise=True``, they must be absent. Import checks
(``checks.check_when``) verify the named fields exist and that
``requires=`` fields are Optional — the base branch must be able to omit
them, or the condition is a lie.
"""
from __future__ import annotations

import datetime as _dt
from collections.abc import Sequence
from decimal import Decimal
from enum import Enum
from typing import Any

from pydantic import BaseModel

from .guards import RELATION_OPS
from .types import DefinitionError

# the JSON Schema spelling of each comparison against a resolved constant
_CMP_KEYWORD = {
    "==": "const",
    ">": "exclusiveMinimum",
    ">=": "minimum",
    "<": "exclusiveMaximum",
    "<=": "maximum",
}


class Field:
    """A comparand naming the *resource's* data field (design §5):
    ``Field.of("due_date")`` reads the document, where a plain string in
    the predicate names a sibling input field."""

    def __init__(self, name: str):
        if not name or not str(name).strip():
            raise DefinitionError("Field.of() requires a data field name")
        self.name = str(name)

    @classmethod
    def of(cls, name: str) -> "Field":
        return cls(name)

    def __repr__(self) -> str:  # pragma: no cover - debugging nicety
        return f"Field.of({self.name!r})"


def _jsonify(value: Any) -> Any:
    if isinstance(value, _dt.datetime | _dt.date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, Enum):
        return value.value
    return value


class When:
    """Declared conditional requiredness on an input model — see the
    module docstring for the full argument. Discovered like ``OneOf``:
    a ``ClassVar`` on the model, collected by :func:`whens_of`."""

    def __init__(self, predicate: tuple[str, str, Any], *,
                 requires: Sequence[str] | str,
                 forbids_otherwise: bool = False):
        if not isinstance(predicate, tuple) or len(predicate) != 3:
            raise DefinitionError(
                "When takes a 3-tuple predicate: (input_field, op, "
                "input_field | Field.of('data_field'))")
        left, op, right = predicate
        if not isinstance(left, str) or not left:
            raise DefinitionError(
                f"When predicate left side {left!r} must name an input field")
        if op not in RELATION_OPS:
            raise DefinitionError(
                f"When predicate op {op!r} is not one of "
                f"{sorted(RELATION_OPS)}")
        if not isinstance(right, (str, Field)) or \
                (isinstance(right, str) and not right):
            raise DefinitionError(
                f"When predicate right side {right!r} must name an input "
                "field (str) or a resource field (Field.of(...))")
        req = (requires,) if isinstance(requires, str) else tuple(requires)
        if not req:
            raise DefinitionError(
                "When requires=() names no fields — a condition that "
                "demands nothing declares nothing")
        if left in req or (isinstance(right, str) and right in req):
            raise DefinitionError(
                "When: a predicate comparand cannot also be conditionally "
                "required — the condition would judge its own consequence")
        self.left = left
        self.op = op
        self.right = right
        self.requires = req
        self.forbids_otherwise = forbids_otherwise
        self.name = "when"

    def __set_name__(self, owner: type, name: str) -> None:
        self.name = name

    # ── import-time validation (called from checks.check_when) ──────────
    def validate_against(self, model_cls: type[BaseModel],
                         data_cls: type[BaseModel]) -> None:
        if self.left not in model_cls.model_fields:
            raise DefinitionError(
                f"{model_cls.__name__}.{self.name}: predicate field "
                f"{self.left!r} is not a field of the input model")
        if isinstance(self.right, Field):
            if self.right.name not in data_cls.model_fields:
                raise DefinitionError(
                    f"{model_cls.__name__}.{self.name}: "
                    f"Field.of({self.right.name!r}) is not a data field of "
                    "the resource")
        elif self.right not in model_cls.model_fields:
            raise DefinitionError(
                f"{model_cls.__name__}.{self.name}: predicate field "
                f"{self.right!r} is not a field of the input model")
        for f in self.requires:
            if f not in model_cls.model_fields:
                raise DefinitionError(
                    f"{model_cls.__name__}.{self.name}: requires field "
                    f"{f!r} is not a field of the input model")
            if model_cls.model_fields[f].is_required():
                raise DefinitionError(
                    f"{model_cls.__name__}.{self.name}: requires field "
                    f"{f!r} is non-optional — conditionally-required fields "
                    "must be Optional so the base branch can omit them")

    # ── evaluation: the one predicate, both consumers ────────────────────
    def describe(self) -> str:
        right = (f"the resource's {self.right.name.replace('_', ' ')}"
                 if isinstance(self.right, Field)
                 else self.right.replace("_", " "))
        return f"{self.left.replace('_', ' ')} {self.op} {right}"

    def _right_value(self, inp: BaseModel, resource: Any) -> Any:
        if isinstance(self.right, Field):
            if resource is None:
                return None
            return getattr(resource.data, self.right.name, None)
        return getattr(inp, self.right, None)

    def holds(self, inp: BaseModel, resource: Any) -> bool:
        left = getattr(inp, self.left, None)
        right = self._right_value(inp, resource)
        if left is None or right is None:
            return False  # an absent comparand is the base branch
        return RELATION_OPS[self.op](left, right)

    def errors(self, inp: BaseModel, resource: Any) -> dict[str, list[str]]:
        """Field-keyed 422 material, from the same predicate the schema
        advertises — the invoker's half of the declaration."""
        out: dict[str, list[str]] = {}
        if self.holds(inp, resource):
            for f in self.requires:
                if getattr(inp, f, None) is None:
                    out.setdefault(f, []).append(
                        f"required when {self.describe()}")
        elif self.forbids_otherwise:
            for f in self.requires:
                if getattr(inp, f, None) is not None:
                    out.setdefault(f, []).append(
                        f"must be omitted unless {self.describe()}")
        return out

    # ── the wire advertisement ───────────────────────────────────────────
    def schema_clause(self, resource: Any) -> dict[str, Any] | None:
        """The ``if``/``then``(/``else``) block, with a resource-field
        comparand resolved to its current value — per-resource schemas,
        exactly as accepts= enums fold. None when nothing constant can be
        resolved (input-vs-input, or the data field is unset): the
        enforcement half still runs; the schema simply doesn't
        over-promise a comparison it cannot state."""
        if not isinstance(self.right, Field):
            return None
        value = None if resource is None \
            else getattr(resource.data, self.right.name, None)
        if value is None:
            return None
        clause: dict[str, Any] = {
            "if": {"required": [self.left],
                   "properties": {self.left:
                                  {_CMP_KEYWORD[self.op]: _jsonify(value)}}},
            "then": {"required": list(self.requires)},
        }
        if self.forbids_otherwise:
            clause["else"] = {"not": {"anyOf": [{"required": [f]}
                                                for f in self.requires]}}
        return clause

    def __repr__(self) -> str:  # pragma: no cover - debugging nicety
        return (f"<When {self.name} {self.left}{self.op}"
                f"{self.right!r} requires={list(self.requires)}>")


def whens_of(model_cls: type) -> dict[str, When]:
    """Every When declared on the model (or its bases) — the OneOf
    discovery pattern, verbatim."""
    out: dict[str, When] = {}
    for klass in reversed(model_cls.__mro__):
        for name, value in vars(klass).items():
            if isinstance(value, When):
                out[name] = value
    return out


def conditional_fields(model_cls: type) -> frozenset[str]:
    """The fields some When conditionally requires — excluded from the
    base branch's demand class (design §5: effort reflects the branch a
    caller can always take; the if/then block advertises the rest)."""
    return frozenset(f for w in whens_of(model_cls).values()
                     for f in w.requires)


def when_errors(model_cls: type, inp: BaseModel,
                resource: Any) -> dict[str, list[str]]:
    """Every declared When's verdict on a validated input, merged — the
    invoker raises one 422 carrying all of them, not a drip-feed."""
    out: dict[str, list[str]] = {}
    for w in whens_of(model_cls).values():
        for f, msgs in w.errors(inp, resource).items():
            out.setdefault(f, []).extend(msgs)
    return out
