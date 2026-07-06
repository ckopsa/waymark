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
from .grants import (
    AGENT_APPROVAL_ACTIONS,
    AGENT_GRANT_ACTIONS,
    SCOPE_REASON,
    action_mode,
    arg_mode,
)
from .invoke import InvokeResult
from .problems import (
    PROBLEM_MEDIA_TYPE,
    Forbidden,
    NotFound,
    Problem,
    SchemaInvalid,
)


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
            elif schema.get("type") == "string":
                ops = fspec.fields.get(name) if fspec else None
                if "," in raw and ops and ops & FilterOp.IN:
                    value = raw.split(",")
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
        from .pipeline import gate

        try:
            await gate("stream", engine, request)
        except Forbidden as exc:
            return problem_response(exc)
        kinds_param = request.query_params.get("kinds")
        kinds = (frozenset(k.strip() for k in kinds_param.split(",") if k.strip())
                 if kinds_param else None)
        sub = engine.dispatcher.subscribe(
            kinds=kinds, actor=request.query_params.get("actor") or None,
            paused=bool(request.headers.get("Last-Event-ID")))
        return sse_response(sse_stream(
            engine.dispatcher, sub, registry, base,
            last_event_id=request.headers.get("Last-Event-ID")))

    @router.get("/-/presence")
    async def presence(request: Request) -> Any:
        """Observation-class liveness (design §3): ``viewed``/``engaged``
        events as principals GET and fill forms. ``?actor=`` follows one
        principal's navigation. Same dispatcher as transitions — the
        delivery discipline (at-most-once, no ids, no replay) comes from
        the declared event class, not from a parallel hub."""
        from .events import sse_response, sse_stream
        from .pipeline import gate

        if not engine.presence:
            return problem_response(NotFound("Presence is disabled on this engine."))
        try:
            await gate("stream", engine, request)
        except Forbidden as exc:
            return problem_response(exc)
        kinds_param = request.query_params.get("kinds")
        kinds = (frozenset(k.strip() for k in kinds_param.split(",") if k.strip())
                 if kinds_param else None)
        sub = engine.dispatcher.subscribe(
            kinds=kinds, actor=request.query_params.get("actor") or None,
            classes=frozenset({"observation"}))
        return sse_response(sse_stream(engine.dispatcher, sub, registry, base))

    # ── agent-link scope enforcement (design: grants.py) ────────────────
    def _scope(principal: Principal) -> Any:
        return getattr(principal, "scope", None)

    def _now() -> Any:
        return engine.invoker.clock()

    async def _owns_target(grant: Any, rdef: ResourceDef, id: str) -> bool:
        if rdef.kind == "grant":
            return id == grant.id
        if rdef.kind == "approval_request":
            async with engine.storage.session() as s:
                inst = await engine.storage.load(s, "approval_request", id)
            return inst is not None and inst.data.grant_id == grant.id
        return False

    async def _enqueue_approval(request: Request, grant: Any,
                                principal: Principal, rdef: ResourceDef,
                                id: str, action: str,
                                body: dict[str, Any] | None,
                                missing: list[str]) -> Response:
        """An approval-mode invocation becomes a pending approval resource:
        the agent gets its envelope back (202) and a human decides."""
        if action == "create":
            label = "Create"
        else:
            defn = rdef.machine.actions[action]
            label = dict(defn.display).get("label") \
                or action.replace("_", " ").capitalize()
        title = f"{label} on {rdef.kind.replace('_', ' ')}"[:80]
        create_body = {
            "grant_id": grant.id,
            "agent_principal": principal.id,
            "holder_name": grant.data.holder_name,
            "title": title,
            "target_kind": rdef.kind,
            "target_id": id,
            "target_action": action,
            # a create targets the collection; anything else its resource
            "target_href": (f"{base}/{rdef.plural}" if not id
                            else f"{base}/{rdef.plural}/{id}"),
            "target_input": dict(body or {}),
            "missing": missing,
        }
        import uuid as _uuid

        result = await engine.invoker.create(
            "approval_request", create_body, principal=principal,
            idempotency_key=request.headers.get("Idempotency-Key")
            or _uuid.uuid4().hex)
        # the approval envelope rendered under the agent's own visibility —
        # its negotiation surface, already projected (design §1)
        return Response(content=result.body, status_code=202,
                        media_type=MEDIA_TYPE)

    def _check_args(grant: Any, rdef: ResourceDef, action: str,
                    body: dict[str, Any] | None) -> tuple[bool, list[str]]:
        """(needs_approval_because_of_args, missing_required). Present args
        the grant says 'none' raise 422 — outside scope is outside scope."""
        defn = rdef.machine.actions.get(action)
        if defn is None or defn.input is None:
            return False, []
        schema = rdef.action_schemas[action][0]
        required = set(schema.get("required") or [])
        now = _now()
        blocked = sorted(a for a in (body or {})
                         if arg_mode(grant, now, rdef.kind, action, a) == "none")
        if blocked:
            raise SchemaInvalid(
                "Arguments outside this agent link's granted scope.",
                action_attempted=action,
                errors={a: ["argument not granted"] for a in blocked})
        missing = sorted(a for a in required if a not in (body or {})
                         and arg_mode(grant, now, rdef.kind, action, a) == "none")
        arg_approval = any(
            arg_mode(grant, now, rdef.kind, action, a) == "approval"
            for a in (body or {}))
        return arg_approval, missing

    async def _announce(event: str, principal: Principal, kind: str,
                        self_href: str, action: str | None = None,
                        via: str | None = None) -> None:
        """Emit an observation with its name declared at the emitting stage
        (design §3): GETs of navigation routes say ``viewed``; the draft,
        dry-run, and discard stages say ``engaged``. Lookup-class routes
        never call this — plumbing is a route class, not a query flag."""
        if not engine.presence:
            return
        from .events import publish_observation

        await publish_observation(
            engine.bus, event=event,
            actor={"id": principal.id, "type": principal.type,
                   "display": principal.display or principal.id},
            self_href=self_href, kind=kind,
            at=engine.invoker.clock().isoformat(), action=action, via=via)

    @router.get("/{plural}/{id}/-/events")
    async def resource_events(plural: str, id: str, request: Request) -> Any:
        from .events import sse_response, sse_stream

        from .pipeline import gate

        rdef = registry.by_plural(plural)
        if rdef is None:
            return problem_response(NotFound(f"No collection {plural!r}."))
        try:
            await gate("stream", engine, request)
        except Forbidden as exc:
            return problem_response(exc)
        sub = engine.dispatcher.subscribe(
            resource=(rdef.kind, id),
            paused=bool(request.headers.get("Last-Event-ID")))
        return sse_response(sse_stream(
            engine.dispatcher, sub, registry, base,
            last_event_id=request.headers.get("Last-Event-ID")))

    async def _collection_doc(rdef: ResourceDef, request: Request,
                              principal: Principal) -> dict[str, Any]:
        from .grants import visibility_of

        parsed = parse_query(rdef, request.query_params)
        filters, sort, page_size, page_number = split_query(rdef, parsed)
        # visibility pushdown (design §9): owned rows plus granted ids land
        # in the WHERE clause — the count is honest, nothing is post-filtered
        vis = visibility_of(principal, _now())
        restrict = vis.restrict(rdef.kind)
        async with engine.storage.session() as s:
            items, total = await engine.storage.query(
                s, rdef.kind, filters=filters, sort=sort,
                page_size=page_size, page_number=page_number,
                restrict=restrict)
            facets = None
            faceted = [f for f in ("state", *rdef.cls.faceted)
                       if rdef.cls.filterable
                       and f in rdef.cls.filterable.fields]
            if faceted:
                # facet counts carry the same pushdown as the rows (design
                # §9): a restricted principal counts owned + granted, no leak
                facets = {f: await engine.storage.facets(s, rdef.kind, f,
                                                         restrict=restrict)
                          for f in dict.fromkeys(faceted)}
            rollups_by_id = None
            from .owns import compute_rollups, has_rollups

            if has_rollups(rdef):
                rollups_by_id = await compute_rollups(
                    engine.storage, s, rdef, [i.id for i in items])
            ctx = engine.invoker._ctx(principal, s, mode="probe")
            from .render import render_collection

            return await render_collection(
                rdef, items, ctx=ctx, total=total, page_size=page_size,
                page_number=page_number, applied_query=parsed, base=base,
                facets=facets, rollups_by_id=rollups_by_id)

    @router.get("/-/lookup/{plural}")
    @_wire
    async def lookup_collection(plural: str, request: Request) -> Response:
        """The lookup route class (design §3): picker options, facet
        fetches — a client's plumbing, not a principal going somewhere.
        Same document as the collection route; no observation is emitted,
        by *which route was traversed*, not by a query flag."""
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        doc = await _collection_doc(rdef, request, principal)
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE)

    @router.get("/{plural}")
    @_wire
    async def collection(plural: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        doc = await _collection_doc(rdef, request, principal)
        # doc["self"] keeps the applied filters, so a follower lands on
        # the same slice the followed principal is actually looking at
        await _announce("viewed", principal, f"{rdef.kind}_collection",
                        doc["self"])
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE)

    @router.post("/{plural}")
    @_wire
    async def create(plural: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        grant = _scope(principal)
        body = await _json_body(request)
        if grant is not None:
            mode = action_mode(grant, _now(), rdef.kind, "create")
            if mode == "none":
                raise Forbidden(SCOPE_REASON, action_attempted="create",
                                remedies=["grant.request_access"])
            if mode == "approval":
                # approval capture is a stage on the write pipeline, and
                # create is a write — so it composes (design §2). v2
                # answered this path with prose: "not supported yet".
                return await _enqueue_approval(
                    request, grant, principal, rdef, "", "create", body, [])
        result = await engine.invoker.create(
            rdef.kind, body, principal=principal,
            idempotency_key=request.headers.get("Idempotency-Key"),
            dry_run=request.query_params.get("dry_run") in ("1", "true"),
            acknowledged=_acknowledged(request))
        return _doc_response(result)

    async def _resource_doc(rdef: ResourceDef, id: str, request: Request,
                            principal: Principal
                            ) -> tuple[dict[str, Any], dict[str, str]]:
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
        if getattr(rdef.cls, "adapter", None) is not None:
            # a mirror past its TTL asks the outside world first (design
            # §10); an observed change lands as a system-actor transition
            from .external import refresh_mirror

            instance = await refresh_mirror(engine, rdef, instance)
        async with engine.storage.session() as s:
            ctx = engine.invoker._ctx(principal, s, mode="probe")
            doc = await engine.render_with_depth(s, instance, rdef, ctx=ctx,
                                                 depth=depth)
        headers["ETag"] = doc["meta"]["etag"]
        return doc, headers

    @router.get("/-/lookup/{plural}/{id}")
    @_wire
    async def lookup_resource(plural: str, id: str, request: Request) -> Response:
        """Ref-label resolution and other sub-document plumbing — never an
        observation (lookup route class, design §3)."""
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        doc, headers = await _resource_doc(rdef, id, request, principal)
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE, headers=headers)

    @router.get("/{plural}/{id}")
    @_wire
    async def resource(plural: str, id: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        doc, headers = await _resource_doc(rdef, id, request, principal)
        await _announce("viewed", principal, rdef.kind, doc["self"])
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE, headers=headers)

    if "attachment" in registry:
        # bytes behind the envelope (design E5): two dedicated routes; the
        # metadata resource stays the truth, stamped by a system transition
        @router.put("/attachments/{id}/bytes")
        @_wire
        async def put_bytes(id: str, request: Request) -> Response:
            from .attachments import BYTES_ACTOR, sha256_hex
            from .invoke import make_etag
            from .problems import GuardRefused

            principal = await engine.resolve_principal(request)
            rdef = registry["attachment"]
            async with engine.storage.session() as s:
                instance = await engine.storage.load(s, "attachment", id)
            if instance is None:
                raise NotFound(f"No attachment {id!r}.")
            # the uploader must hold the resource: same act gate as any write
            member_vis = getattr(principal, "visibility", None)
            if member_vis is not None and member_vis.action(
                    "attachment", "mark_uploaded", id,
                    owner=instance.owner) == "none":
                raise Forbidden(SCOPE_REASON, action_attempted="upload")
            if instance.state != "reserved":
                raise GuardRefused(
                    "Bytes are written once, into a reserved attachment — "
                    "reserve a new one to replace the file.",
                    action_attempted="upload", state=instance.state)
            data = await request.body()
            await engine.blobs.put(id, data)
            result = await engine.invoker.invoke(
                "attachment", id, "mark_uploaded",
                {"size": len(data), "sha256": sha256_hex(data)},
                principal=BYTES_ACTOR,
                if_match=make_etag("attachment", id, instance.version))
            return _doc_response(result)

        @router.get("/attachments/{id}/bytes")
        @_wire
        async def get_bytes(id: str, request: Request) -> Response:
            principal = await engine.resolve_principal(request)
            async with engine.storage.session() as s:
                instance = await engine.storage.load(s, "attachment", id)
            if instance is None or instance.state != "uploaded":
                raise NotFound(f"No uploaded attachment {id!r}.")
            from .grants import visibility_of

            vis = visibility_of(principal, _now())
            if vis.field("attachment", "name", id, instance.owner) == "hidden":
                raise NotFound(f"No uploaded attachment {id!r}.")
            data = await engine.blobs.get(id)
            if data is None:
                raise NotFound(f"The bytes for attachment {id!r} are gone.")
            return Response(content=data, media_type=instance.data.mime,
                            headers={"Content-Disposition":
                                     f'attachment; filename="{instance.data.name}"'})

    @router.post("/{plural}/-/{action}")
    @_wire
    async def bulk(plural: str, action: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        result = await engine.invoker.bulk(
            rdef.kind, action, body, principal=principal,
            idempotency_key=request.headers.get("Idempotency-Key"),
            acknowledged=_acknowledged(request))
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

    def _draft_scope_gate(principal: Principal, rdef: ResourceDef,
                          action: str) -> None:
        """Drafting is effort toward an action: allowed for open AND
        approval modes (the invoke stays gated), denied for none."""
        grant = _scope(principal)
        if grant is not None \
                and action_mode(grant, _now(), rdef.kind, action) == "none":
            raise Forbidden(SCOPE_REASON, action_attempted=action,
                            remedies=["grant.request_access"])

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
        _draft_scope_gate(principal, rdef, action)
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

    from .pipeline import DRAFT_VERBS

    for method, path in DRAFT_VERBS["save"]:
        router.add_api_route(path, _wire(_save_draft), methods=[method])

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
        _draft_scope_gate(principal, rdef, action)
        async with engine.storage.session() as s:
            doc = await _draft_envelope(s, rdef, defn, id, part_key, principal)
        # a draftable form opens by fetching the draft's truth — the draft
        # stage itself declares the event (design §3): engaged, via form
        await _announce("engaged", principal, rdef.kind,
                        f"{base}/{rdef.plural}/{id}",
                        action=action, via="form")
        return Response(content=json.dumps(doc, default=str),
                        media_type=MEDIA_TYPE,
                        headers={"ETag": doc["meta"]["etag"]})

    async def _discard_draft(plural: str, id: str, action: str,
                             request: Request) -> Response:
        rdef = rdef_or_404(plural)
        defn = _draft_defn(rdef, action)
        part_key = _part_key(defn, request)
        principal = await engine.resolve_principal(request)
        _draft_scope_gate(principal, rdef, action)
        async with engine.storage.session() as s:
            await engine.draft_store.discard(
                s, rdef, defn, id, part_key, audience_of(defn, principal))
        if defn.collab:
            await engine.collab.close((rdef.kind, id, action, part_key),
                                      "discarded")
        await _announce("engaged", principal, rdef.kind,
                        f"{base}/{rdef.plural}/{id}",
                        action=action, via="discard")
        return Response(status_code=204)

    for method, path in DRAFT_VERBS["discard"]:
        router.add_api_route(path, _wire(_discard_draft), methods=[method])

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
        grant = _scope(principal)
        if grant is not None \
                and action_mode(grant, _now(), rdef.kind, action) != "open":
            await websocket.close(code=4403)
            return
        await serve(engine, websocket, rdef, defn, id, part_key, principal)

    @router.post("/{plural}/{id}/-/{action}")
    @_wire
    async def act(plural: str, id: str, action: str, request: Request) -> Response:
        rdef = rdef_or_404(plural)
        principal = await engine.resolve_principal(request)
        body = await _json_body(request)
        dry_run = request.query_params.get("dry_run") in ("1", "true")
        member_vis = getattr(principal, "visibility", None)
        if member_vis is not None and _scope(principal) is None \
                and rdef.machine.actions.get(action) is not None:
            # the SAME MemberVisibility the projector consulted: what did
            # not render as an action cannot be invoked (design §1/§9)
            async with engine.storage.session() as s:
                target = await engine.storage.load(s, rdef.kind, id)
            if target is not None:
                mode = member_vis.action(rdef.kind, action, id,
                                         owner=target.owner)
                if mode == "none":
                    raise Forbidden(SCOPE_REASON, action_attempted=action,
                                    remedies=["grant.request_access"])
                if mode == "approval" and not dry_run:
                    matched = member_vis.approval_grant(rdef.kind, action, id)
                    if matched is not None:
                        return await _enqueue_approval(
                            request, matched, principal, rdef, id, action,
                            body, [])
        grant = _scope(principal)
        if grant is not None and rdef.machine.actions.get(action) is not None:
            own = await _owns_target(grant, rdef, id)
            passthrough = own and (
                (rdef.kind == "grant"
                 and action in AGENT_GRANT_ACTIONS)
                or (rdef.kind == "approval_request"
                    and action in AGENT_APPROVAL_ACTIONS))
            if own and not passthrough:
                raise Forbidden(
                    "Yours to request; a person's to decide.",
                    action_attempted=action)
            if not passthrough:
                # the SAME visibility the projector consulted (design §1) —
                # instance selectors included: what did not render as an
                # action cannot be invoked
                from .grants import GrantVisibility

                mode = GrantVisibility(grant, _now()).action(
                    rdef.kind, action, id)
                if mode in ("none", "negotiation"):
                    raise Forbidden(SCOPE_REASON, action_attempted=action,
                                    remedies=["grant.request_access"])
                arg_approval, missing = _check_args(grant, rdef, action, body)
                if not dry_run and (mode == "approval" or arg_approval
                                    or missing):
                    return await _enqueue_approval(
                        request, grant, principal, rdef, id, action, body,
                        missing)
        if dry_run:
            # blur-time validation is the server-visible fact of "someone is
            # filling this form right now" — the dry-run stage declares it
            await _announce("engaged", principal, rdef.kind,
                            f"{base}/{rdef.plural}/{id}",
                            action=action, via="dry_run")
        result = await engine.invoker.invoke(
            rdef.kind, id, action, body, principal=principal,
            if_match=request.headers.get("If-Match"),
            idempotency_key=request.headers.get("Idempotency-Key"),
            dry_run=dry_run,
            acknowledged=_acknowledged(request))
        if result.consumed_draft is not None:
            # the invoke consumed the draft (invoker decided, from the
            # declaration); the room — on every worker — is over
            consumed_action, part_key = result.consumed_draft
            await engine.collab.close(
                (rdef.kind, id, consumed_action, part_key), "consumed")
        if (not dry_run and result.status < 400
                and getattr(rdef.cls, "adapter", None) is not None):
            from .external import MIRROR_SYNC_ACTIONS, push_mirror

            if action not in MIRROR_SYNC_ACTIONS:
                # a domain write on a push-on-write mirror pushes; a
                # conflict or outage becomes an honest sync state — the
                # local write stands either way
                await push_mirror(engine, rdef, id)
        return _doc_response(result)

    return router


def _acknowledged(request: Request) -> frozenset[str]:
    """Waymark-Acknowledge: comma-separated warning-guard names the caller
    accepts responsibility for (design E1). Recorded on the transition."""
    raw = request.headers.get("Waymark-Acknowledge") or ""
    return frozenset(n.strip() for n in raw.split(",") if n.strip())


async def _json_body(request: Request) -> dict[str, Any] | None:
    raw = await request.body()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        raise SchemaInvalid("Request body is not valid JSON.",
                            errors={"_root": ["invalid JSON"]}) from None
