"""Typed references (design §2): ``Ref["meal"]`` is one declaration that
retires three v1 heuristics.

A ``Ref`` field generates the wire ``x-display`` reference hint (data
render), the picker widget (input render), the collection label behavior,
and gives the dangling-ref conformance check a declaration to trust instead
of a ``{kind}_id`` naming convention. Raw ids on the wire must say so in the
type (``RefField(raw=True)``) — the acknowledgment lives in the declaration,
not a parallel waiver channel.

Usage::

    class DayEntry(BaseModel):
        meal_id: Ref["meal"] | None = RefField(
            default=None, label="meal_name",
            pick=Query(state="on_list", theme="{item.theme}"))
"""
from __future__ import annotations

import typing
from dataclasses import dataclass
from typing import Annotated, Any

from pydantic import BaseModel, Field
from pydantic.fields import FieldInfo


@dataclass(frozen=True)
class RefMeta:
    """Annotated metadata carrying the referenced kind."""

    kind: str


class Ref:
    """``Ref["meal"]`` → ``Annotated[str, RefMeta("meal")]``."""

    def __class_getitem__(cls, kind: str) -> Any:
        if not isinstance(kind, str):
            raise TypeError(f"Ref[...] takes a kind token string, got {kind!r}")
        return Annotated[str, RefMeta(kind)]


class Query(dict):
    """Picker narrowing params; values may template over ``{item.*}`` when
    the action is part-scoped."""

    def __init__(self, **params: str):
        super().__init__(params)


def RefField(
    default: Any = ...,
    *,
    label: str | None = None,
    pick: Query | dict[str, str] | None = None,
    raw: bool = False,
    hidden: bool = False,
    **kwargs: Any,
) -> Any:
    """Field options for a ``Ref``-typed field.

    - ``label`` — sibling field holding the human label (denormalized name);
      generic clients show it and drop its own column.
    - ``pick`` — collection query params narrowing the picker.
    - ``raw`` — deliberate raw-id display (the acknowledged escape hatch).
    - ``hidden`` — machine-only plumbing, dropped from human display.
    """
    opts: dict[str, Any] = {}
    if label:
        opts["label_field"] = label
    if pick:
        opts["params"] = dict(pick)
    if raw:
        opts["raw"] = True
    if hidden:
        opts["hidden"] = True
    extra = kwargs.pop("json_schema_extra", None) or {}
    extra["x-ref-opts"] = opts
    return Field(default, json_schema_extra=extra, **kwargs)


def ref_meta(field_info: FieldInfo) -> RefMeta | None:
    """Extract the RefMeta from a model field, wherever pydantic put it.

    Top-level ``Ref["k"]`` lands in ``FieldInfo.metadata``; inside a union
    (``Ref["k"] | None``) it stays on the Annotated arg of the annotation.
    """
    for m in field_info.metadata:
        if isinstance(m, RefMeta):
            return m
    ann = field_info.annotation
    for arg in typing.get_args(ann):
        for m in getattr(arg, "__metadata__", ()):
            if isinstance(m, RefMeta):
                return m
    return None


def ref_opts(field_info: FieldInfo) -> dict[str, Any]:
    extra = field_info.json_schema_extra
    if isinstance(extra, dict):
        opts = extra.get("x-ref-opts")
        if isinstance(opts, dict):
            return opts
    return {}


def ref_display(field_info: FieldInfo) -> dict[str, Any] | None:
    """The ``x-display`` hint a Ref field emits, or None for non-Ref fields."""
    meta = ref_meta(field_info)
    if meta is None:
        return None
    opts = ref_opts(field_info)
    if opts.get("hidden"):
        return {"hidden": True}
    if opts.get("raw"):
        return {"raw": True}
    out: dict[str, Any] = {"widget": "resource", "kind": meta.kind}
    if opts.get("label_field"):
        out["label_field"] = opts["label_field"]
    if opts.get("params"):
        out["params"] = dict(opts["params"])
    return out


def model_refs(model: type[BaseModel]) -> dict[str, RefMeta]:
    """All Ref-typed fields of a model, by field name."""
    out: dict[str, RefMeta] = {}
    for name, f in model.model_fields.items():
        meta = ref_meta(f)
        if meta is not None:
            out[name] = meta
    return out
