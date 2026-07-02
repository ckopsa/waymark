"""Rendering (§12): a pure projection of (instance, principal, depth, locale, now).

For each declared transition: run guards in probe mode → sort into
``actions`` / ``unavailable`` / hidden. Out-of-state transitions land in
``unavailable`` with a generated reason and ``becomes_available.in_states``
(§3); their non-hide guards are *not* evaluated (they may legitimately depend
on state), but hide-flagged permission guards are, so concealment holds in
every state.
"""
from __future__ import annotations

from typing import Any, Literal

from ..core.actions import ActionDef
from ..core.guards import Guard
from ..core.registry import Registry, ResourceDef
from ..core.resource import Resource
from ..core.summary import _SummaryFormatter, render_summary, state_label
from ..core.types import Allow, Ctx, Deny

FORMAT_VERSION = "1"

Probe = (
    tuple[Literal["available"], None, None]
    | tuple[Literal["unavailable"], Deny, Guard]
    | tuple[Literal["hidden"], Deny, Guard]
)


def make_etag(kind: str, id: str, version: int) -> str:
    return f'W/"{kind}-{id}-v{version}"'


async def probe_transition(defn: ActionDef, instance: Resource, ctx: Ctx) -> Probe:
    probe_ctx = _as_probe(ctx)
    for g in defn.guards:
        verdict, denier = await g.evaluate(instance, None, probe_ctx)
        if isinstance(verdict, Deny):
            state = "hidden" if denier.hide else "unavailable"
            return state, verdict, denier  # first Deny wins
    return "available", None, None


async def probe_hidden_only(defn: ActionDef, instance: Resource, ctx: Ctx) -> bool:
    """For out-of-state transitions: evaluate only hide-flagged guards."""
    probe_ctx = _as_probe(ctx)
    for g in defn.guards:
        if not g.hide:
            continue
        verdict, denier = await g.evaluate(instance, None, probe_ctx)
        if isinstance(verdict, Deny) and denier.hide:
            return True
    return False


def _as_probe(ctx: Ctx) -> Ctx:
    if ctx.mode == "probe":
        return ctx
    return Ctx(principal=ctx.principal, now=ctx.now, services=ctx.services,
               session=ctx.session, locale=ctx.locale,
               correlation_id=ctx.correlation_id, mode="probe",
               _invoker=ctx._invoker, _reader=ctx._reader)


def _action_entry(defn: ActionDef, rdef: ResourceDef, href: str) -> dict[str, Any]:
    entry: dict[str, Any] = {"method": "POST", "href": href}
    if defn.input is not None:
        entry["input"] = rdef.action_schemas[defn.name][0]
    entry["effect"] = defn.effect.to_wire()
    entry["safety"] = defn.safety.to_wire()
    if defn.display:
        entry["display"] = dict(defn.display)
    return entry


def _unavailable_entry(defn: ActionDef, deny: Deny, denier: Guard,
                       instance: Resource) -> dict[str, Any]:
    entry: dict[str, Any] = {"reason": denier.render_reason(deny, instance)}
    hope = denier.becomes_available(deny, instance)
    if hope:
        entry["becomes_available"] = hope
    if denier.remedies:
        entry["remedies"] = list(denier.remedies)
    return entry


def _out_of_state_entry(defn: ActionDef, current_state: str) -> dict[str, Any]:
    states = sorted(defn.from_)
    # prose is human-facing (§3): humanized labels; tokens stay in the
    # structured becomes_available.in_states for machine clients
    quoted = ", ".join(f"'{state_label(s)}'" for s in states)
    return {
        "reason": (f"Not available while {state_label(current_state).lower()}. "
                   f"Becomes available in state {quoted}."),
        "becomes_available": {"in_states": states},
    }


def render_links(instance: Resource, rdef: ResourceDef, *, base: str,
                 self_href: str) -> dict[str, Any]:
    links: dict[str, Any] = {}
    for ld in rdef.cls.links:
        href = _SummaryFormatter(instance).vformat(ld.href, (), {})
        links[ld.rel] = {"href": base + href if href.startswith("/") else href,
                         "kind": ld.kind,
                         **({"summary": ld.summary} if ld.summary else {})}
    links.setdefault("collection", {
        "href": f"{base}/{rdef.plural}",
        "kind": f"{rdef.kind}_collection",
        "summary": f"All {rdef.plural}",
    })
    links.setdefault("events", {
        "href": f"{self_href}/-/events",
        "kind": "event_stream",
        "summary": "Live transitions (SSE)",
    })
    return links


def _render_display(instance: Resource, rdef: ResourceDef) -> dict[str, Any]:
    declared = dict(rdef.cls.display)
    display: dict[str, Any] = {}
    title = declared.pop("title", None)
    if title:
        display["title"] = _SummaryFormatter(instance).vformat(title, (), {})
    state_display = declared.pop("state", {}) or {}
    states_map = state_display if all(isinstance(v, dict) for v in state_display.values()) else {}
    display["state"] = {"label": state_label(instance.state),
                       **states_map.get(instance.state, {})}
    display.update(declared)
    return display


async def render(
    instance: Resource,
    rdef: ResourceDef,
    *,
    ctx: Ctx,
    depth: str = "full",
    base: str = "/api",
    registry: Registry | None = None,
    embeds: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Render the resource envelope (§1). ``embeds`` maps link rel → embedded
    document (already rendered; batching is the caller's job, §12)."""
    self_href = f"{base}/{rdef.plural}/{instance.id}"
    actions: dict[str, Any] = {}
    unavailable: dict[str, Any] = {}

    for defn in rdef.machine.actions.values():
        if defn.bulk:
            continue  # collection-level affordance (§5)
        if instance.state in defn.from_:
            status, deny, denier = await probe_transition(defn, instance, ctx)
            if status == "available":
                actions[defn.name] = _action_entry(defn, rdef, f"{self_href}/-/{defn.name}")
            elif status == "unavailable":
                unavailable[defn.name] = _unavailable_entry(defn, deny, denier, instance)
            # hidden: appears in neither map
        else:
            if await probe_hidden_only(defn, instance, ctx):
                continue
            unavailable[defn.name] = _out_of_state_entry(defn, instance.state)

    doc: dict[str, Any] = {
        "waymark": FORMAT_VERSION,
        "kind": rdef.kind,
        "self": self_href,
        "state": instance.state,
        "summary": render_summary(rdef.summary_template, instance),
        "data": instance.data.model_dump(mode="json"),
        "actions": actions,
        "unavailable": unavailable,
        "links": render_links(instance, rdef, base=base, self_href=self_href),
        # depth=summary is the agent default (§4.1): presentation hints are
        # quarantined display-only payload, so agents don't pay for them
        **({"display": _render_display(instance, rdef)}
           if depth != "summary" else {}),
        "meta": {
            "version": instance.version,
            "etag": make_etag(rdef.kind, instance.id, instance.version),
            **({"updated_at": instance.updated_at.isoformat()}
               if instance.updated_at else {}),
        },
    }
    if embeds:
        for rel, embedded in embeds.items():
            if rel in doc["links"] and embedded is not None:
                doc["links"][rel] = {**doc["links"][rel], "embedded": embedded,
                                     "summary": embedded.get("summary",
                                                             doc["links"][rel].get("summary"))}
    return doc


def _bulk_schema(defn: ActionDef, rdef: ResourceDef) -> dict[str, Any]:
    cached = rdef.extra.setdefault("bulk_schemas", {})
    if defn.name not in cached:
        item_props: dict[str, Any] = {}
        required = ["ids"]
        if defn.input is not None:
            input_schema = rdef.action_schemas[defn.name][0]
            item_props = dict(input_schema.get("properties", {}))
            required += input_schema.get("required", [])
        cached[defn.name] = {
            "type": "object",
            "required": required,
            "properties": {
                "ids": {"type": "array", "items": {"type": "string"},
                        "maxItems": defn.max_items},
                **item_props,
            },
            "additionalProperties": False,
        }
    return cached[defn.name]


async def render_collection(
    rdef: ResourceDef,
    items: list[Resource],
    *,
    ctx: Ctx,
    total: int,
    page_size: int,
    page_number: int,
    applied_query: dict[str, Any] | None = None,
    base: str = "/api",
    facets: dict[str, dict[str, int]] | None = None,
) -> dict[str, Any]:
    from urllib.parse import urlencode

    applied_query = {k: v for k, v in (applied_query or {}).items() if v is not None}

    def page_href(number: int) -> str:
        params = {**applied_query, "page[size]": page_size, "page[number]": number}
        return f"{base}/{rdef.plural}?{urlencode(params)}"

    self_href = page_href(page_number) if applied_query or page_number > 1 \
        else f"{base}/{rdef.plural}"

    rendered_items: list[dict[str, Any]] = []
    for item in items:
        doc = await render(item, rdef, ctx=ctx, depth="summary", base=base)
        if not rdef.cls.row_affordances:
            doc["actions"] = None  # explicitly unknown (§5), distinct from {} = none
            doc["unavailable"] = None
        rendered_items.append(doc)

    filters_desc = ", ".join(f"{k}={v}" for k, v in applied_query.items()
                             if k not in ("sort", "page[size]", "page[number]"))
    summary = f"{rdef.plural.capitalize()} · {len(items)} of {total} shown"
    if filters_desc:
        summary = f"{summary} · filtered: {filters_desc}"

    query_schema = rdef.query_schema
    if facets:
        query_schema = {**query_schema, "properties": dict(query_schema["properties"])}
        for fname, counts in facets.items():
            if fname in query_schema["properties"]:
                query_schema["properties"][fname] = {
                    **query_schema["properties"][fname], "x-facets": counts}

    create_input = rdef.extra.get("create_schema") or rdef.data_schema
    actions: dict[str, Any] = {
        "create": {
            "method": "POST",
            "href": f"{base}/{rdef.plural}",
            "input": create_input,
            "effect": {"to": rdef.machine.initial},
            "safety": {"idempotent": False, "reversible": False, "confirm": False},
        },
        "query": {
            "method": "GET",
            "href": f"{base}/{rdef.plural}",
            "input": query_schema,
            "safety": {"idempotent": True, "reversible": True, "confirm": False},
        },
    }
    for defn in rdef.machine.actions.values():
        if not defn.bulk:
            continue
        entry: dict[str, Any] = {
            "method": "POST",
            "href": f"{base}/{rdef.plural}/-/{defn.name}",
            "input": _bulk_schema(defn, rdef),
            "effect": {**defn.effect.to_wire(), "bulk": True},
            "safety": defn.safety.to_wire(),
        }
        if defn.display:
            entry["display"] = dict(defn.display)
        actions[defn.name] = entry

    last_page = max(1, -(-total // page_size))
    links: dict[str, Any] = {
        "next": ({"href": page_href(page_number + 1), "kind": f"{rdef.kind}_collection",
                  "summary": f"Page {page_number + 1} of {last_page}"}
                 if page_number < last_page else None),
        "prev": ({"href": page_href(page_number - 1), "kind": f"{rdef.kind}_collection",
                  "summary": f"Page {page_number - 1} of {last_page}"}
                 if page_number > 1 else None),
    }

    return {
        "waymark": FORMAT_VERSION,
        "kind": f"{rdef.kind}_collection",
        "self": self_href,
        "state": "ok",
        "summary": summary,
        "data": {
            "items": rendered_items,
            "total": total,
            "page": {"size": page_size, "number": page_number},
        },
        "actions": actions,
        "unavailable": {},
        "links": links,
        "meta": {"version": 0, "etag": 'W/"collection"'},
    }
