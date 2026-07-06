"""Demand classes (design §10): every action's effort, computed and rendered.

Every input field is a demand the server makes of the human. The class is
derived from the declaration and rendered on the wire (``"effort"``), so
clients, agents, and checks share one vocabulary:

- ``assent``      — empty or const-only input: one click.
- ``selection``   — every field enumerable (enum / const / boolean /
                    acceptance set / picker): choosing, not typing.
- ``recall``      — open but format-constrained typing.
- ``composition`` — unbounded text; drafts are the floor here.

(``traversal`` — links and summaries — is below the action layer.)
"""
from __future__ import annotations

from typing import Any

from .actions import ActionDef

ASSENT = "assent"
SELECTION = "selection"
RECALL = "recall"
COMPOSITION = "composition"


def _widget(prop: dict[str, Any]) -> str | None:
    return (prop.get("x-display") or {}).get("widget")


def _resolve(schema: dict[str, Any], prop: dict[str, Any]) -> dict[str, Any]:
    """Follow one level of local $ref / single-element allOf (pydantic wraps
    enums this way)."""
    if len(prop.get("allOf", ())) == 1:
        prop = {**prop, **prop["allOf"][0]}
    ref = prop.get("$ref", "")
    if ref.startswith("#/$defs/"):
        target = (schema.get("$defs") or {}).get(ref.rsplit("/", 1)[-1], {})
        prop = {**target, **{k: v for k, v in prop.items() if k != "$ref"}}
    return prop


def _variants(prop: dict[str, Any]) -> list[dict[str, Any]]:
    """anyOf/oneOf branches minus null, else the property itself."""
    branches = prop.get("anyOf") or prop.get("oneOf")
    if not branches:
        return [prop]
    out = [b for b in branches if b.get("type") != "null"]
    return out or [prop]


def demand_class(defn: ActionDef, schema: dict[str, Any] | None) -> str:
    """The demand class of an action, from its rendered input schema and its
    guards' declared acceptance sets."""
    if defn.input is None or schema is None:
        return ASSENT
    props: dict[str, Any] = schema.get("properties") or {}
    if not props:
        return ASSENT

    accepted_fields: set[str] = set()
    for guard in defn.guards:
        for g in guard.iter_leaves():
            if g.accepts is not None:
                # a Relation closes all its judged fields (design §5)
                accepted_fields.update(
                    g.judges if g.is_relation else (g.judges[0],))
    key_field = defn.place.key if defn.place else None

    worst = ASSENT
    order = {ASSENT: 0, SELECTION: 1, RECALL: 2, COMPOSITION: 3}
    for name, raw in props.items():
        prop = _resolve(schema, raw)
        if "const" in prop or name == key_field:
            continue  # pre-bound: no demand
        cls = _field_class(schema, name, prop, accepted_fields)
        if order[cls] > order[worst]:
            worst = cls
    return worst


def _field_class(schema: dict[str, Any], name: str, prop: dict[str, Any],
                 accepted_fields: set[str]) -> str:
    if _widget(prop) == "prose":
        return COMPOSITION
    if "enum" in prop or "const" in prop:
        return SELECTION
    if name in accepted_fields or _widget(prop) == "resource":
        return SELECTION
    for v in _variants(prop):
        v = _resolve(schema, v)
        t = v.get("type")
        if t == "boolean" or "enum" in v or "const" in v:
            continue
        if t == "string":
            max_len = v.get("maxLength")
            if v.get("format") or (max_len is not None and max_len < 280):
                return RECALL
            return COMPOSITION if _widget(prop) == "prose" else RECALL
        if t in ("number", "integer"):
            return RECALL
        if t in ("array", "object"):
            return RECALL
    return SELECTION
