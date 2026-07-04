"""JSON Schema generation (§10.3): generated once at import, cached, byte-stable.

Schemas are self-contained (``$defs`` inlined in place) so they can be
embedded in action affordances; canonical bytes (sorted keys, tight
separators) are what ``/api/schemas/{name}`` serves and what CI diffs.
"""
from __future__ import annotations

import copy
import json
from typing import Any

from pydantic import BaseModel


def _inline_defs(schema: dict[str, Any]) -> dict[str, Any]:
    defs = schema.pop("$defs", {})
    if not defs:
        return schema

    def resolve(node: Any, seen: frozenset[str]) -> Any:
        if isinstance(node, dict):
            ref = node.get("$ref")
            if isinstance(ref, str) and ref.startswith("#/$defs/"):
                name = ref.split("/")[-1]
                if name in seen:  # recursive model: leave the ref (rare; documented)
                    return node
                target = resolve(copy.deepcopy(defs[name]), seen | {name})
                extra = {k: v for k, v in node.items() if k != "$ref"}
                return {**target, **extra}
            return {k: resolve(v, seen) for k, v in node.items()}
        if isinstance(node, list):
            return [resolve(v, seen) for v in node]
        return node

    return resolve(schema, frozenset())


def _close_objects(node: Any) -> Any:
    """Force ``additionalProperties: false`` on object schemas with declared
    properties (§2: submitted bodies validate against exactly this schema)."""
    if isinstance(node, dict):
        out = {k: _close_objects(v) for k, v in node.items()}
        if out.get("type") == "object" and "properties" in out:
            out.setdefault("additionalProperties", False)
        return out
    if isinstance(node, list):
        return [_close_objects(v) for v in node]
    return node


def canonicalize(schema: dict[str, Any]) -> tuple[dict[str, Any], bytes]:
    raw = json.dumps(schema, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return json.loads(raw), raw.encode()


def _humanize(name: str) -> str:
    text = name.replace("_", " ").strip()
    return text[:1].upper() + text[1:] if text else name


def _ensure_field_labels(schema: dict[str, Any],
                         field_display: dict[str, Any] | None) -> dict[str, Any]:
    """Guarantee every top-level form field carries an ``x-display.label`` (§8,
    §10.3), so the label is a server-emitted, localizable presentation string
    rather than something the client synthesizes.

    Precedence: the action's ``field_display`` map, then the field's own
    ``x-display`` (from ``Field(json_schema_extra=…)``), then a title-cased
    default derived from the field name. Only the root object's properties are
    labeled — nested models keep their raw schema.
    """
    if schema.get("type") != "object":
        return schema
    field_display = field_display or {}
    for name, prop in (schema.get("properties") or {}).items():
        if not isinstance(prop, dict):
            continue
        merged = {**prop.get("x-display", {}), **field_display.get(name, {})}
        merged.setdefault("label", prop.get("title") or _humanize(name))
        if prop.get("description"):
            merged.setdefault("help", prop["description"])
        prop["x-display"] = merged
    return schema


def input_schema(model: type[BaseModel],
                 field_display: dict[str, Any] | None = None
                 ) -> tuple[dict[str, Any], bytes]:
    schema = model.model_json_schema(mode="validation")
    schema = _inline_defs(schema)
    schema = _close_objects(schema)
    schema.pop("title", None)
    schema = _ensure_field_labels(schema, field_display)
    return canonicalize(schema)


def data_schema(model: type[BaseModel]) -> tuple[dict[str, Any], bytes]:
    schema = model.model_json_schema(mode="serialization")
    schema = _inline_defs(schema)
    schema.pop("title", None)
    return canonicalize(schema)


def query_schema(resource: type) -> tuple[dict[str, Any], bytes]:
    """The collection ``query`` action's input schema (§5), generated from the
    resource's declared filterable/sortable sets. Maps to query parameters."""
    from .resource import FilterOp

    props: dict[str, Any] = {}
    fspec = resource.filterable
    field_types = _field_types(resource)
    if fspec is not None:
        for fname, ops in fspec.fields.items():
            base = field_types.get(fname, {"type": "string"})
            if fname == "state":
                base = {"type": "string", "enum": [str(s) for s in resource.State]}
            if ops & (FilterOp.EQ | FilterOp.IN):
                props[fname] = {**base,
                                "x-display": {"label": _humanize(fname)}}
            if ops & FilterOp.RANGE:
                num = base if base.get("type") in ("number", "integer") else {"type": "number"}
                props[f"{fname}_gte"] = {
                    **num, "x-display": {"label": f"{_humanize(fname)} ≥"}}
                props[f"{fname}_lte"] = {
                    **num, "x-display": {"label": f"{_humanize(fname)} ≤"}}
            if ops & FilterOp.AFTER:
                pname = fname.removesuffix("_at") + "_after"
                props[pname] = {"type": "string", "format": "date-time",
                                "x-display": {"label": _humanize(pname)}}
    sspec = resource.sortable
    if sspec is not None:
        options = [v for f in sspec.fields for v in (f, f"-{f}")]
        props["sort"] = {"type": "string", "enum": options, "default": sspec.default,
                         "x-display": {"label": "Sort by"}}
    props["page[size]"] = {"type": "integer", "minimum": 1, "maximum": 100,
                           "default": 25, "x-display": {"label": "Page size"}}
    props["page[number]"] = {"type": "integer", "minimum": 1, "default": 1,
                             "x-display": {"label": "Page"}}
    return canonicalize({"type": "object", "properties": props,
                         "additionalProperties": False})


def _field_types(resource: type) -> dict[str, dict[str, Any]]:
    """Base JSON types for Data fields, for typing filter params."""
    schema, _ = data_schema(resource.Data)
    out: dict[str, dict[str, Any]] = {}
    for name, sub in (schema.get("properties") or {}).items():
        if "type" in sub:
            out[name] = {"type": sub["type"]}
            if "format" in sub:
                out[name]["format"] = sub["format"]
        elif "anyOf" in sub:
            for option in sub["anyOf"]:
                if option.get("type") not in (None, "null"):
                    out[name] = {"type": option["type"]}
                    if "format" in option:
                        out[name]["format"] = option["format"]
                    break
    return out
