"""OpenAPI overlay (§13): FastAPI's /docs shows every action with its real
input schema — derived from the registry, like everything else."""
from __future__ import annotations

from typing import Any

from .. import MEDIA_TYPE
from ..core.registry import Registry

_DOC_RESPONSE = {
    "200": {
        "description": "The post-transition resource document",
        "content": {MEDIA_TYPE: {"schema": {"type": "object"}}},
    },
    "409": {"description": "Guard refused (detail == the advertised reason)"},
    "412": {"description": "Version conflict; body carries a fresh document"},
    "422": {"description": "Schema-invalid input with per-field errors"},
    "428": {"description": "Idempotency-Key required"},
}


def overlay_paths(registry: Registry, base: str = "/api") -> dict[str, Any]:
    paths: dict[str, Any] = {}
    for rdef in registry.defs():
        col = f"{base}/{rdef.plural}"
        paths[col] = {
            "get": {
                "tags": [rdef.kind],
                "summary": f"Query {rdef.plural}",
                "parameters": [
                    {"name": name, "in": "query", "required": False,
                     "schema": schema}
                    for name, schema in rdef.query_schema["properties"].items()
                ],
                "responses": {"200": {"description": f"{rdef.kind} collection"}},
            },
            "post": {
                "tags": [rdef.kind],
                "summary": f"Create a {rdef.kind} (initial-state transition)",
                "requestBody": {"content": {"application/json": {
                    "schema": rdef.extra["create_schema"]}}},
                "responses": {"201": {"description": "The new resource document"}},
            },
        }
        paths[f"{col}/{{id}}"] = {
            "get": {
                "tags": [rdef.kind],
                "summary": f"Fetch a {rdef.kind} document",
                "parameters": [
                    {"name": "id", "in": "path", "required": True,
                     "schema": {"type": "string"}},
                    {"name": "depth", "in": "query", "required": False,
                     "schema": {"type": "string",
                                "enum": ["summary", "full"] +
                                        [f"expanded:{p}" for p in rdef.cls.profiles]}},
                ],
                "responses": {"200": {"description": "The resource document"}},
            },
        }
        for name, defn in rdef.machine.actions.items():
            op: dict[str, Any] = {
                "tags": [rdef.kind],
                "summary": defn.display.get("label", name),
                "description": (
                    f"Transition: {', '.join(sorted(defn.from_))} → {defn.to}. "
                    f"Safety: idempotent={defn.safety.idempotent}, "
                    f"reversible={defn.safety.reversible}, "
                    f"confirm={defn.safety.confirm}."),
                "parameters": [
                    {"name": "dry_run", "in": "query", "required": False,
                     "schema": {"type": "string", "enum": ["1"]}},
                ],
                "responses": _DOC_RESPONSE,
            }
            if defn.input is not None:
                op["requestBody"] = {"content": {"application/json": {
                    "schema": rdef.action_schemas[name][0]}}}
            if defn.bulk:
                paths[f"{col}/-/{name}"] = {"post": {
                    **op, "summary": f"{op['summary']} (bulk)"}}
            else:
                paths[f"{col}/{{id}}/-/{name}"] = {"post": {
                    **op,
                    "parameters": op["parameters"] + [
                        {"name": "id", "in": "path", "required": True,
                         "schema": {"type": "string"}}],
                }}
        paths[f"{col}/{{id}}/-/events"] = {"get": {
            "tags": [rdef.kind], "summary": "Per-resource transition stream (SSE)",
            "responses": {"200": {"description": "text/event-stream"}}}}
    paths[f"{base}/-/events"] = {"get": {
        "tags": ["events"], "summary": "Workspace transition firehose (SSE)",
        "parameters": [{"name": "kinds", "in": "query", "required": False,
                        "schema": {"type": "string"}}],
        "responses": {"200": {"description": "text/event-stream"}}}}
    return paths


def install(app: Any, registry: Registry, base: str = "/api") -> None:
    """Replace FastAPI's schema generator with one that merges the overlay."""
    from fastapi.openapi.utils import get_openapi

    def custom() -> dict[str, Any]:
        if app.openapi_schema:
            return app.openapi_schema
        schema = get_openapi(title=app.title, version=app.version,
                             routes=app.routes)
        # the generic /{plural} routes are noise next to the real surface
        schema["paths"] = {p: v for p, v in schema.get("paths", {}).items()
                           if "{plural}" not in p}
        schema["paths"].update(overlay_paths(registry, base))
        app.openapi_schema = schema
        return schema

    app.openapi = custom
