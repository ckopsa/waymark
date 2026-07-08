"""OneOf field groups (design §5): declared exclusivity.

v2's scar: the mealplan's eating-out was four parallel fields whose mutual
exclusion was re-maintained by hand in three handlers, and "the day is
covered" was re-derived as a boolean expression in a fourth place. One
declaration now yields all of it:

- the invariant — at most one arm filled — enforced by the invoker after
  every handler (a handler that fills two arms is a definition bug and
  raises; with ``clears=True`` filling one arm clears the others);
- the wire hint (``one_of`` beside the schema/parts it governs) so clients
  render the choice as a choice;
- the derived predicate (:meth:`OneOf.filled_arm`) that application code
  consumes instead of re-deriving ``not a and b is None`` in prose.

An *arm* is a named group of fields; its first field is the primary — the
arm is "filled" iff the primary is neither ``None`` nor ``False``. Clearing
an arm resets every one of its fields to its model default.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from pydantic import BaseModel

from .types import DefinitionError


def _filled(value: Any) -> bool:
    return value is not None and value is not False


class OneOfViolation(RuntimeError):
    """A handler left two arms of a OneOf group filled — a definition-level
    bug (the group either wanted ``clears=True`` or the handler is wrong)."""


class OneOf:
    """Declared on a pydantic model as a ``ClassVar``::

        class DayPlan(BaseModel):
            coverage: ClassVar[OneOf] = OneOf(
                arms={"meal": ("meal_id", "meal_name"),
                      "eating_out": ("eating_out", "eating_out_where")},
                clears=True)

    (``ClassVar`` keeps pydantic from reading the declaration as a field;
    arm-field existence is verified by the resource checks at import.)
    """

    def __init__(self, *, arms: Mapping[str, Sequence[str] | str],
                 clears: bool = False):
        normalized: dict[str, tuple[str, ...]] = {}
        for arm, fields in arms.items():
            fs = (fields,) if isinstance(fields, str) else tuple(fields)
            if not fs:
                raise DefinitionError(f"OneOf arm {arm!r} names no fields")
            normalized[arm] = fs
        if len(normalized) < 2:
            raise DefinitionError(
                "OneOf takes at least two arms — one arm is not a choice")
        seen: set[str] = set()
        for arm, fs in normalized.items():
            dup = seen & set(fs)
            if dup:
                raise DefinitionError(
                    f"OneOf arm {arm!r} shares field(s) {sorted(dup)} with "
                    "another arm — arms are exclusive by definition")
            seen |= set(fs)
        self.arms = normalized
        self.clears = clears
        self.name = "one_of"

    def __set_name__(self, owner: type, name: str) -> None:
        self.name = name

    def validate_against(self, model_cls: type[BaseModel]) -> None:
        """Import-time check (called from the resource checks): every arm
        field must exist on the model it governs."""
        missing = [f for fs in self.arms.values() for f in fs
                   if f not in model_cls.model_fields]
        if missing:
            raise DefinitionError(
                f"{model_cls.__name__}.{self.name}: OneOf names field(s) "
                f"{missing} that the model does not declare")

    def primary(self, arm: str) -> str:
        return self.arms[arm][0]

    def filled_arm(self, model: BaseModel) -> str | None:
        """The arm currently filled, or None. Post-enforcement there is at
        most one — this is the derived predicate application code uses."""
        for arm, fields in self.arms.items():
            if _filled(getattr(model, fields[0], None)):
                return arm
        return None

    def filled(self, model: BaseModel) -> bool:
        return self.filled_arm(model) is not None

    def to_wire(self) -> dict[str, Any]:
        return {"arms": {arm: list(fs) for arm, fs in self.arms.items()},
                "clears": self.clears}

    def __repr__(self) -> str:  # pragma: no cover - debugging nicety
        return f"<OneOf {self.name} arms={sorted(self.arms)}>"


def groups_of(model_cls: type) -> dict[str, OneOf]:
    """Every OneOf declared on the model (or its bases)."""
    out: dict[str, OneOf] = {}
    for klass in reversed(model_cls.__mro__):
        for name, value in vars(klass).items():
            if isinstance(value, OneOf):
                out[name] = value
    return out


def _default_of(model_cls: type[BaseModel], field: str) -> Any:
    info = model_cls.model_fields[field]
    return info.get_default(call_default_factory=True)


def _item_models(data_cls: type[BaseModel]) -> dict[str, type[BaseModel]]:
    """List-of-model fields on the data model — the one level of nesting
    parts live at, and the one level OneOf enforcement walks."""
    out: dict[str, type[BaseModel]] = {}
    for name, info in data_cls.model_fields.items():
        ann = info.annotation
        args = getattr(ann, "__args__", ())
        origin = getattr(ann, "__origin__", None)
        if origin is list and args and isinstance(args[0], type) \
                and issubclass(args[0], BaseModel):
            out[name] = args[0]
    return out


def enforce(data: BaseModel, before: dict[str, Any] | None) -> None:
    """Post-handler enforcement: apply every declared group on the data
    root and on items of list-of-model fields. ``before`` is the
    pre-handler ``model_dump(mode="json")`` (None ⇒ nothing was previously
    filled, e.g. at create)."""
    for group in groups_of(type(data)).values():
        _apply(group, data, before)
    for field, item_cls in _item_models(type(data)).items():
        groups = list(groups_of(item_cls).values())
        if not groups:
            continue
        items = getattr(data, field) or []
        prev_items = (before or {}).get(field) or []
        for i, item in enumerate(items):
            prev = prev_items[i] if i < len(prev_items) else None
            for group in groups:
                _apply(group, item, prev)


def has_groups(data_cls: type[BaseModel]) -> bool:
    return bool(groups_of(data_cls)) or any(
        groups_of(cls) for cls in _item_models(data_cls).values())


def _apply(group: OneOf, model: BaseModel,
           before: dict[str, Any] | None) -> None:
    filled = [arm for arm in group.arms
              if _filled(getattr(model, group.primary(arm), None))]
    if len(filled) <= 1:
        return
    if group.clears:
        newly = [arm for arm in filled
                 if before is None
                 or not _filled(before.get(group.primary(arm)))]
        if len(newly) == 1:
            keep = newly[0]
            for arm in filled:
                if arm == keep:
                    continue
                for f in group.arms[arm]:
                    setattr(model, f, _default_of(type(model), f))
            return
    raise OneOfViolation(
        f"OneOf {group.name!r}: arms {sorted(filled)} are filled together — "
        "either the handler fills two arms (a bug) or two arms were newly "
        "set in one write (ambiguous even with clears=True)")
