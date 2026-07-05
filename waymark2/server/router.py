"""The generated routes: uniform per resource; applications add none.

2.0 draft routes (design §4): the draft is a sub-resource with an envelope.

  GET    …/-/{action}/draft[?part=K]        → draft envelope (always 200)
  PUT    …/-/{action}/draft[?part=K]        → save (partial merge)
  POST   …/-/{action}/draft[?part=K]        → save (envelope `save` action)
  POST   …/-/{action}/draft/-/discard[?part=K]
  DELETE …/-/{action}/draft[?part=K]        → discard
  WS     …/-/{action}/draft/collab[?part=K] → waymark-relay/2

Every save lands through DraftStore.save_fields — the same code as the
relay's drain. Room lifecycle (consumed/discarded) crosses workers on the
bus via CollabRooms.close.
"""
from __future__ import annotations

import functools
import json
from typing import Any, Callable

from fastapi import APIRouter, Request, Response, WebSocket

from .. import FORMAT_VERSION, MEDIA_TYPE
from ..core.registry import ResourceDef
from ..core.types import Principal
from .drafts import audience_of, render_draft
from .invoke import InvokeResult
from .problems import PROBLEM_MEDIA_TYPE, NotFound, Problem, SchemaInvalid


def problem_response(exc: Problem) -> Response:
    return Response(content=json.dumps(exc.to_dict(), default=str),
                    status_code=exc.status, media_type=PROBLEM_MEDIA_TYPE)


def _wire(fn: Callable) -> Callable:
    """Problems become problem+json without needing app-level handlers, so
    ``app.include_router(engine.router)`` is the whole integration."""

    @functools.wraps(fn)
    async def wrapper(*args: Any, **kwargs: Any) -> Response:
        try:
            return await fn(*args, **kwargs)
        except Problem as exc:
            return problem_response(exc)

    return wrapper


def _doc_response(result: InvokeResult, headers: dict[str, str] | None = None) -> Response:
    return Response(content=result.body, status_code=result.status,
                    media_type=result.media_type, headers=headers)


def parse_query(rdef: ResourceDef, params: Any) -> dict[str, Any]:
    """Validate query params against the generated query schema; comma
    lists become IN filters where the field declares the In op."""
    from ..core.resource import FilterOp

    props = rdef.query_schema["properties"]
    parsed: dict[str, Any] = {}
    errors: dict[str, list[str]] = {}
    fspec = rdef.cls.filterable
    for name, raw in params.items():
        if name in ("depth", "dry_run", "part"):
            continue
        schema = props.get(name)
        if schema is None:
            errors[name] = ["unknown query parameter"]
            continue
        try:
            value: Any = raw
            if schema.get("type") == "integer":
                value = int(raw)
                if "maximum" in schema and value > schema["maximum"]:
                    raise ValueError(f"must be ≤ {schema['maximum']}")
                if "minimum" in schema and value < schema["minimum"]:
                    raise ValueError(f"must be ≥ {schema['minimum']}")
            elif schema.get("type") == "number":
                value = float(raw)
            elif "enum" in schema:
                ops = fspec.fields.get(name) if fspec else None
                if "," in raw and ops and ops & FilterOp.IN:
                    value = raw.split(",")
                    bad = [v for v in value if v not in schema["enum"]]
                    if bad:
                        raise ValueError(f"invalid values: {bad}")
                elif raw not in schema["enum"]:
                    raise ValueError(f"must be one of {schema['enum']}")
            parsed[name] = value
        except ValueError as e:
            errors[name] = [str(e)]
    if errors:
        raise SchemaInvalid("Invalid query parameters.", errors=errors)
    return parsed


def split_query(rdef: ResourceDef, parsed: dict[str, Any]) -> tuple[dict, str | None, int, int]:
    filters = {k: v for k, v in parsed.items()
               if k not in ("sort", "page[size]", "page[number]")}
    sort = parsed.get("sort")
    if sort is None and rdef.cls.sortable is not None:
        sort = rdef.cls.sortable.default
    return filters, sort, parsed.get("page[size]", 25), parsed.get("page[number]", 1)


def build_router(engine: Any) -> APIRouter:
    router = APIRouter()
    registry = engine.registry
    base = engine.base_path

    def rdef_or_404(plural: str) -> ResourceDef:
        rdef = registry.by_plural(plural)
        if rdef is None:
            raise NotFound(f"No collection {plural!r}.")
        return rdef

    @router.get("/.well-known/waymark")
    @_wire
    async def wellknown() -> Response:
        doc = {
            "waymark": FORMAT_VERSION,
            "media_type": MEDIA_TYPE,
            "kinds": registry.kinds(),
            "collections": {rdef.kind: f"{base}/{rdef.plural}"
                            for rdef in registry.defs()},
            "schemas": f"{base}/schemas/{{name}}",
            "events": f"{base}/-/events",
            "ui": f"{base}/-/ui",
            "locales": ["en"],
            "profiles": {rdef.kind: sorted(rdef.cls.profiles)
                         for rdef in registry.defs() if rdef.cls.profiles},
        }
        return Response(content=json.dumps(doc), media_type="application/json")

    @router.get("/schemas/{name}")
    @_wire
    async def schema(name: str) -> Response:
        found = registry.schema(name)
        if found is None:
            raise NotFound(f"No schema {name!r}.")
        return Response(content=found[1], media_type="application/schema+json")

    @router.get("/-/ui")
    async def ui() -> Response:
        # the generic human client: consumes only the envelope
        from pathlib import Path

        html = (Path(__file__).parent / "static" / "ui.html").read_text()
        return Response(content=html, media_type="text/html")

    @router.get("/-/events")
    async def firehose(request: Request) -> Any:
        """The workspace stream. ``?actor=`` narrows it to one principal's
        transitions — the supervision affordance: follow what an agent (or
        anyone) is doing, as they do it."""
        from .events import sse_response, sse_stream

        kinds_param = request.query_params.get("kinds")
        kinds = (frozenset(k.strip() for k in kinds_param.split(",") if k.strip())
                 if kinds_param else None)
        sub = engine.dispatcher.subscribe(
            kinds=kinds, actor=request.query_params.get("actor") or None,
            paused=bool(request.headers.get("Last-Event-ID")))
        return sse_response(sse_stream(
            engine.dispatcher, sub, registry, base,
            last_event_id=request.headers.get("Last-Event-ID")))

    @router.get("/{plural}/{id}/-/events")
    async def resource_events(plural: str, id: str, request: Request) -> Any:
        from .events import sse_response, sse_stream

        rdef = registry.by_plural(plural)
        if rdef is None:
            return problem_response(NotFound(f"No collection {plural!r}."))
        sub = engine.dispatcher.subscribe(
            resource=(rdef.kind, id),
            paused=bool(request.headers.get("Last-Event-ID")))
        return sse_response(sse_stream(
            engine.dispatcher, sub, registry, base,
            last_event_id=request.headers.get("Last-Event-ID")))

    @router.get("/{plural}")
    @_wire
    async def collection(plural: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        parsed = parse_query(rdef, request.query_params)
        filters, sort, page_size, page_number = split_query(rdef, parsed)
        async with engine.storage.session() as s:
            items, total = await engine.storage.query(
                s, rdef.kind, filters=filters, sort=sort,
                page_size=page_size, page_number=page_number)
            facets = None
            if rdef.cls.filterable and "state" in rdef.cls.filterable.fields:
                facets = {"state": await engine.storage.facets(s, rdef.kind, "state")}
            ctx = engine.invoker._ctx(principal, s, mode="probe")
            from .render import render_collection

            doc = await render_collection(
                rdef, items, ctx=ctx, total=total, page_size=page_size,
                page_number=page_number, applied_query=parsed, base=base,
                facets=facets)
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE)

    @router.post("/{plural}")
    @_wire
    async def create(plural: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        result = await engine.invoker.create(
            rdef.kind, body, principal=principal,
            idempotency_key=request.headers.get("Idempotency-Key"),
            dry_run=request.query_params.get("dry_run") in ("1", "true"))
        return _doc_response(result)

    @router.get("/{plural}/{id}")
    @_wire
    async def resource(plural: str, id: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        depth = request.query_params.get("depth", "full")
        headers: dict[str, str] = {}
        known = {"summary", "full"} | {f"expanded:{p}" for p in rdef.cls.profiles}
        if depth not in known:
            depth = "full"
            headers["Waymark-Depth"] = "full"
        async with engine.storage.session() as s:
            instance = await engine.storage.load(s, rdef.kind, id)
            if instance is None:
                raise NotFound(f"No {rdef.kind} {id!r}.")
            ctx = engine.invoker._ctx(principal, s, mode="probe")
            doc = await engine.render_with_depth(s, instance, rdef, ctx=ctx,
                                                 depth=depth)
        headers["ETag"] = doc["meta"]["etag"]
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE, headers=headers)

    @router.post("/{plural}/-/{action}")
    @_wire
    async def bulk(plural: str, action: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        result = await engine.invoker.bulk(
            rdef.kind, action, body, principal=principal,
            idempotency_key=request.headers.get("Idempotency-Key"))
        return _doc_response(result)

    # ── the draft sub-resource (design §4) ──────────────────────────────
    def _draft_defn(rdef: Any, action: str) -> Any:
        defn = rdef.machine.actions.get(action)
        if defn is None or not defn.draft:
            raise NotFound(f"{rdef.kind} has no draftable action {action!r}.")
        return defn

    def _part_key(defn: Any, request: Any) -> str:
        part = request.query_params.get("part", "")
        if defn.place is None and part:
            raise SchemaInvalid(
                f"Action {defn.name!r} is not placed; it has no parts.",
                errors={"part": ["action is not placed"]})
        return part

    async def _draft_envelope(s: Any, rdef: Any, defn: Any, id: str,
                              part_key: str, principal: Principal) -> dict:
        instance = await engine.storage.load(s, rdef.kind, id)
        if instance is None:
            raise NotFound(f"No {rdef.kind} {id!r}.")
        row = await engine.draft_store.load(
            s, rdef, defn, id, part_key, audience_of(defn, principal))
        return render_draft(rdef, defn, instance, row, base=base,
                            part_key=part_key)

    async def _save_draft(plural: str, id: str, action: str,
                          request: Request) -> Response:
        """The one write path: partial merge through DraftStore.save_fields —
        the same code the relay's drain runs. Values are stored as-is (a
        draft may be invalid mid-edit); full validation happens on invoke."""
        rdef = rdef_or_404(plural)
        defn = _draft_defn(rdef, action)
        part_key = _part_key(defn, request)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        if not isinstance(body, dict):
            raise SchemaInvalid("A draft body must be a JSON object.",
                                errors={"_root": ["expected an object"]})
        known = set(rdef.action_schemas[action][0].get("properties", {}))
        unknown = set(body) - known
        if unknown:
            raise SchemaInvalid(
                f"Draft contains fields the action does not take: "
                f"{sorted(unknown)}.",
                errors={f: ["unknown field"] for f in sorted(unknown)})
        async with engine.storage.session() as s:
            instance = await engine.storage.load(s, rdef.kind, id)
            if instance is None:
                raise NotFound(f"No {rdef.kind} {id!r}.")
            now = engine.invoker.clock()
            saved = await engine.draft_store.save_fields(
                s, rdef=rdef, defn=defn, resource_id=id, part_key=part_key,
                audience=audience_of(defn, principal), fields=body,
                base_version=instance.version, actor=principal, at=now)
            doc = render_draft(rdef, defn, instance, {
                **saved, "base_version": instance.version},
                base=base, part_key=part_key)
        if defn.collab:
            # clients that don't speak the channel still land in the same
            # shared draft; the room (on every worker) sees each field
            from .collab import actor_of

            for field, value in body.items():
                frame = {"type": "update", "field": field, "value": value,
                         "rev": saved["revs"][field],
                         "actor": actor_of(principal),
                         "saved_at": now.isoformat()}
                await engine.collab.broadcast(
                    (rdef.kind, id, action, part_key), frame)
                await engine.draft_store.announce(
                    rdef=rdef, defn=defn, resource_id=id, part_key=part_key,
                    frame=frame)
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE)

    router.put("/{plural}/{id}/-/{action}/draft")(_wire(_save_draft))
    router.post("/{plural}/{id}/-/{action}/draft")(_wire(_save_draft))

    @router.get("/{plural}/{id}/-/{action}/draft")
    @_wire
    async def get_draft(plural: str, id: str, action: str,
                        request: Request) -> Response:
        """The draft's current truth as an envelope — clients fetch this on
        form open rather than trusting a document rendered before typing
        began. An absent row is an empty open draft, not a 204."""
        rdef = rdef_or_404(plural)
        defn = _draft_defn(rdef, action)
        part_key = _part_key(defn, request)
        principal = await engine.resolve_principal(request)
        async with engine.storage.session() as s:
            doc = await _draft_envelope(s, rdef, defn, id, part_key, principal)
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE,
                        headers={"ETag": doc["meta"]["etag"]})

    async def _discard_draft(plural: str, id: str, action: str,
                             request: Request) -> Response:
        rdef = rdef_or_404(plural)
        defn = _draft_defn(rdef, action)
        part_key = _part_key(defn, request)
        principal = await engine.resolve_principal(request)
        async with engine.storage.session() as s:
            await engine.draft_store.discard(
                s, rdef, defn, id, part_key, audience_of(defn, principal))
        if defn.collab:
            await engine.collab.close((rdef.kind, id, action, part_key),
                                      "discarded")
        return Response(status_code=204)

    router.delete("/{plural}/{id}/-/{action}/draft")(_wire(_discard_draft))
    router.post("/{plural}/{id}/-/{action}/draft/-/discard")(_wire(_discard_draft))

    @router.websocket("/{plural}/{id}/-/{action}/draft/collab")
    async def draft_collab(websocket: WebSocket, plural: str, id: str,
                           action: str) -> None:
        """waymark-relay/2: a channel whose every accepted update drains
        into the shared draft. Joining requires the action to render in
        ``actions`` for this principal (checked in serve)."""
        from .collab import serve

        rdef = registry.by_plural(plural)
        defn = rdef.machine.actions.get(action) if rdef else None
        if rdef is None or defn is None or not defn.collab:
            await websocket.close(code=4404)
            return
        part_key = websocket.query_params.get("part", "")
        principal = await engine.resolve_principal(websocket)
        await serve(engine, websocket, rdef, defn, id, part_key, principal)

    @router.post("/{plural}/{id}/-/{action}")
    @_wire
    async def act(plural: str, id: str, action: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        dry_run = request.query_params.get("dry_run") in ("1", "true")
        result = await engine.invoker.invoke(
            rdef.kind, id, action, body, principal=principal,
            if_match=request.headers.get("If-Match"),
            idempotency_key=request.headers.get("Idempotency-Key"),
            dry_run=dry_run)
        if result.consumed_draft is not None:
            # the invoke consumed the draft (invoker decided, from the
            # declaration); the room — on every worker — is over
            consumed_action, part_key = result.consumed_draft
            await engine.collab.close(
                (rdef.kind, id, consumed_action, part_key), "consumed")
        return _doc_response(result)

    return router


async def _json_body(request: Request) -> dict[str, Any] | None:
    raw = await request.body()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        raise SchemaInvalid("Request body is not valid JSON.",
                            errors={"_root": ["invalid JSON"]}) from None
