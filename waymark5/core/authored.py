"""Authored fields (design §1, §8): authority is per-field.

The entity-list's central strain was split ownership: the CRM's fields
are externally owned and read-only, the crosswalk and budgets are local
and writable — but they are one entity to the user, and 3.0 offered only
whole-resource ``Mirror`` or a satellite-kind composition. §1's
field-origin frame dissolves the dichotomy: a field may declare its
authority, and only that authority's sync path writes it::

    class EntityData(AuthoredMeta):          # server-side sync bookkeeping
        name: str | None = Authored(by="hubspot", default=None,
                                    max_length=80)
        stage: str | None = Authored(by="hubspot", default=None,
                                     max_length=40,
                                     follows={"closedwon": "activate",
                                              "closedlost": "suspend"})
        crosswalk_id: str | None = None       # written — ordinary field

Authored fields join the same per-field discovery path as ``Derived``
(:func:`authored_spec` beside :func:`~.derived.derived_spec`): the
declaration rides the pydantic field, the published schema marks the
field ``readOnly: true`` with ``x-source: "authored"`` and
``x-authority: "<service>"`` (the authority is nameable on the wire, not
just in the module), and the origins
are exclusive and checked at import (``checks.check_authored``) — an
action input model naming an authored field is a
:class:`DefinitionError`, a create body supplying one is refused at
validation, and a handler that assigns one is refused by the engine at
write time, exactly as with derived fields.

They are written ONLY by the authority's sync path
(``Invoker.sync_authored``, riding the Mirror adapter protocol —
``pull(id) → (doc, etag)`` — scoped to the authored subset): external
changes arrive as system-actor transitions, so audit, events, and
derivation recompute come free. ``follows=`` maps an incoming VALUE of
the field to a declared transition name: when a sync changes the field
to that value, the engine invokes the named transition through the
single invoker (system actor, guards run) — a guard refusal leaves the
value change recorded and the transition refused, logged. Transition
names are checked at import.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from pydantic import BaseModel, Field
from pydantic.fields import FieldInfo

from .types import DefinitionError


@dataclass(frozen=True)
class AuthoredSpec:
    """The declaration behind an authored field, carried on the pydantic
    field and consumed by the schema generator, the import checks, the
    validation chokepoint, and the sync path alike (the ``derived_spec``
    precedent, design §1)."""

    by: str
    follows: Mapping[Any, str]


def Authored(*, by: Any, follows: Mapping[Any, str] | None = None,
             default: Any = None, **kwargs: Any) -> Any:
    """Declare an authored Data field. ``by=`` names the authority (a
    service name, or an object carrying ``.name``); ``follows=`` maps an
    incoming value to a declared transition of the resource. The default
    is the authority's to overwrite — an authored field is never written
    inside the boundary."""
    name = by if isinstance(by, str) else getattr(by, "name", None)
    if not name or not isinstance(name, str):
        raise DefinitionError(
            "Authored(by=...) names the authority — a service name string "
            "or an object with a .name")
    spec = AuthoredSpec(by=name, follows=dict(follows or {}))
    user_extra = dict(kwargs.pop("json_schema_extra", None) or {})

    # json_schema_extra as a callable (the Derived precedent, design §1):
    # every schema emission — Data, a Create model inheriting the field,
    # anywhere — carries the origin marks without a second site to forget
    def mark(schema: dict[str, Any]) -> None:
        schema.update(user_extra)
        schema.pop("default", None)  # an authority-owned value offers none
        schema["readOnly"] = True
        schema["x-source"] = "authored"
        # the authority is named on the wire, not just in the module: a
        # client shows WHO owns the value ("hubspot"), not merely that
        # somebody external does
        schema["x-authority"] = spec.by

    mark.__waymark_authored__ = spec  # type: ignore[attr-defined]
    return Field(default, json_schema_extra=mark, **kwargs)


def authored_spec(field_info: FieldInfo) -> AuthoredSpec | None:
    spec = getattr(field_info.json_schema_extra, "__waymark_authored__", None)
    return spec if isinstance(spec, AuthoredSpec) else None


_SPECS_CACHE: dict[type, dict[str, AuthoredSpec]] = {}


def authored_specs(model: type[BaseModel]) -> dict[str, AuthoredSpec]:
    """All authored fields of a model, by field name."""
    cached = _SPECS_CACHE.get(model)
    if cached is None:
        cached = {name: spec for name, f in model.model_fields.items()
                  if (spec := authored_spec(f)) is not None}
        _SPECS_CACHE[model] = cached
    return cached


def has_authored(cls: type) -> bool:
    data = getattr(cls, "Data", None)
    return data is not None and bool(authored_specs(data))


def authored_snapshot(instance: Any) -> dict[str, Any]:
    """The authored values before a handler runs — the tamper witness
    (the ``DerivedMaintainer.snapshot`` discipline applied to §8)."""
    return {name: getattr(instance.data, name, None)
            for name in authored_specs(type(instance).Data)}
