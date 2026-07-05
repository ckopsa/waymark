"""Rendering (design §6): project, then resolve.

v1 claimed render was "a pure projection" while admits functions read other
resources through an engine ctx, defended by a swallowed exception. 2.0
names the two stages:

- ``project`` (``resolved=False``) — pure and total in
  ``(instance, principal, depth, now)``. Guards and acceptance sets that
  declared ``reads=()`` participate; anything that declared reads is
  *skipped*, not attempted-and-swallowed. Project may over-advertise;
  it never errors for lack of a ctx.
- ``resolve`` (``resolved=True``) — the engine's GET path. Every declared
  read participates with an engine-wired ctx, and a failure is a loud
  configuration error, never a silent un-tightening. Resolve only ever
  *tightens* what project rendered.

The wire is identical either way (``waymark: "2"``); each action entry
additionally carries its computed demand class (``"effort"``, design §10),
and draftable actions advertise the draft **sub-resource** (href + status),
never an inlined values blob (design §4).
"""
from __future__ import annotations

from typing import Any, Literal

from ..core.actions import ActionDef
from ..core.demand import demand_class
from ..core.guards import Guard
from ..core.registry import Registry, ResourceDef
from ..core.resource import Resource
from ..core.summary import _SummaryFormatter, render_summary, state_label
from ..core.types import Allow, Ctx, Deny

FORMAT_VERSION = "2"

Probe = (
    tuple[Literal["available"], None, None]
    | tuple[Literal["unavailable"], Deny, Guard]
    | tuple[Literal["hidden"], Deny, Guard]
)


def make_etag(kind: str, id: str, version: int) -> str:
    return f'W/"{kind}-{id}-v{version}"'


async def probe_transition(defn: ActionDef, instance: Resource, ctx: Ctx,
                           *, resolved: bool = True) -> Probe:
    probe_ctx = _as_probe(ctx)
    for g in defn.guards:
        if g.reads and not resolved:
            continue  # project(): ctx-dependent availability is resolve's job
        verdict, denier = await g.evaluate(instance, None, probe_ctx)
        if isinstance(verdict, Deny):
            state = "hidden" if denier.hide else "unavailable"
            return state, verdict, denier  # first Deny wins
    return "available", None, None


async def probe_hidden_only(defn: ActionDef, instance: Resource, ctx: Ctx,
                            *, resolved: bool = True) -> bool:
    """For out-of-state transitions: evaluate only hide-flagged guards."""
    probe_ctx = _as_probe(ctx)
    for g in defn.guards:
        if not g.hide or (g.reads and not resolved):
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
               _invoker=ctx._invoker, _reader=ctx._reader, _finder=ctx._finder)


def _draft_advert(defn: ActionDef, rdef: ResourceDef, href: str,
                  instance: Resource,
                  draft_row: dict[str, Any] | None) -> dict[str, Any]:
    """The draft sub-resource advert (design §4): a link with status, never
    an inlined blob — GET the envelope for values/revs/authors."""
    policy = defn.draft_policy
    advert: dict[str, Any] = {"href": f"{href}/draft", "kind": "draft"}
    if policy is not None and policy.shared:
        advert["shared"] = True
    if policy is not None and policy.live:
        from .drafts import PROTOCOL

        advert["collab"] = {"href": f"{href}/draft/collab",
                            "protocol": PROTOCOL}
    if draft_row is not None:
        advert["exists"] = True
        advert["saved_at"] = draft_row["saved_at"].isoformat()
        # a draft outlived by newer edits must reconcile, not silently
        # restore over them
        advert["stale"] = draft_row["base_version"] != instance.version
    return advert


def _action_entry(defn: ActionDef, rdef: ResourceDef, href: str,
                  admitted: dict[str, list[Any]],
                  instance: Resource,
                  draft_row: dict[str, Any] | None) -> dict[str, Any]:
    entry: dict[str, Any] = {"method": "POST", "href": href}
    schema: dict[str, Any] | None = None
    if defn.input is not None:
        schema = _admits_schema(rdef.action_schemas[defn.name][0], admitted)
        if defn.prefill:
            schema = _prefill_schema(schema, defn, instance)
        entry["input"] = schema
    entry["effect"] = defn.effect.to_wire()
    entry["safety"] = defn.safety.to_wire()
    entry["effort"] = demand_class(defn, schema)
    if defn.draft and defn.place is None:
        # placed drafts advertise per part (each part is its own draft)
        entry["draft"] = _draft_advert(defn, rdef, href, instance, draft_row)
    if defn.display:
        entry["display"] = dict(defn.display)
    return entry


def _prefill_schema(schema: dict[str, Any], defn: ActionDef,
                    instance: Resource) -> dict[str, Any]:
    """Editing is not re-authoring: prefilled fields carry the document's
    current values as schema ``default``s, so forms open filled in and agents
    see the current values without a second read."""
    props = schema.get("properties", {})
    if not props:
        return schema
    dump = instance.data.model_dump(mode="json")
    filled = {f: {**props[f], "default": dump[f]}
              for f in defn.prefill if f in props and dump.get(f) is not None}
    if not filled:
        return schema
    return {**schema, "properties": {**props, **filled}}


async def _admitted_values(defn: ActionDef, instance: Resource, ctx: Ctx,
                           *, resolved: bool) -> dict[str, list[Any]]:
    """Each guard's declared acceptance set, intersected across guards
    sharing a field.

    Project (``resolved=False``): only ``reads=()`` sets participate —
    skipping is a *declared* decision, not a swallowed exception. Resolve:
    every set participates and failures propagate loudly (the engine wired
    the ctx precisely because the guard declared its reads)."""
    admitted: dict[str, list[Any]] = {}
    for g in defn.guards:
        for leaf in g.iter_leaves():
            if leaf.admits is None:
                continue
            if leaf.reads and not resolved:
                continue
            values = await leaf.admitted(instance, ctx)
            if values is None:
                continue
            fld = leaf.admits[0]
            if fld in admitted:
                admitted[fld] = [v for v in admitted[fld] if v in values]
            else:
                admitted[fld] = values
    return admitted


def _admits_schema(schema: dict[str, Any],
                   admitted: dict[str, list[Any]]) -> dict[str, Any]:
    """Fold acceptance sets into the advertised input schema as enums — the
    form never offers a value the server already knows it will refuse."""
    props = schema.get("properties", {})
    if props and admitted:
        schema = {**schema,
                  "properties": {**props, **{f: {**props[f], "enum": vals}
                                             for f, vals in admitted.items()
                                             if f in props}}}
    return schema


def _strip_unscoped(actions: dict[str, Any]) -> dict[str, Any]:
    """Top-level entries with ``{item.*}`` picker params: drop the templated
    params (only a placed part can resolve them). Pure — the originals feed
    the parts binding."""
    out = {}
    for name, entry in actions.items():
        schema = entry.get("input")
        if schema and schema.get("properties"):
            stripped = _strip_item_params(schema["properties"])
            if stripped != schema["properties"]:
                entry = {**entry, "input": {**schema, "properties": stripped}}
        out[name] = entry
    return out


def _strip_item_params(props: dict[str, Any]) -> dict[str, Any]:
    out = dict(props)
    for fname, prop in props.items():
        params = (prop.get("x-display") or {}).get("params")
        if not params or not any(_is_item_template(v) for v in params.values()):
            continue
        kept = {k: v for k, v in params.items() if not _is_item_template(v)}
        out[fname] = {**prop, "x-display": {**prop["x-display"], "params": kept}}
    return out


def _is_item_template(value: Any) -> bool:
    return isinstance(value, str) and "{item." in value


def _bind_part_entry(base_entry: dict[str, Any], key: str, key_value: Any,
                     item: dict[str, Any], defn: ActionDef,
                     rdef: ResourceDef, instance: Resource,
                     drafts: dict[Any, dict[str, Any]] | None,
                     base: str) -> dict[str, Any]:
    """The per-item projection of an action entry: the scope key becomes a
    ``const`` (the user never re-picks what they clicked), picker params
    templated over the item (``{item.theme}``) resolve to its values, and a
    draftable placed action adverts *this part's* draft."""
    entry = dict(base_entry)
    schema = entry.get("input")
    if schema:
        props = dict(schema.get("properties", {}))
        if key in props:
            bound = {k: v for k, v in props[key].items() if k != "enum"}
            bound["const"] = key_value
            props[key] = bound
        for fname, prop in list(props.items()):
            display = prop.get("x-display") or {}
            params = display.get("params")
            if not params:
                continue
            resolved = {k: _resolve_item_param(v, item) for k, v in params.items()}
            if resolved != params:
                props[fname] = {**prop, "x-display": {**display, "params": resolved}}
        entry["input"] = {**schema, "properties": props}
        entry["effort"] = demand_class(defn, entry["input"])
    if defn.draft:
        from .drafts import draft_href

        part_key = str(key_value)
        row = (drafts or {}).get((defn.name, part_key))
        href = f"{base}/{rdef.plural}/{instance.id}/-/{defn.name}"
        advert = _draft_advert(defn, rdef, href, instance, row)
        advert["href"] = draft_href(base, rdef, instance.id, defn.name, part_key)
        if "collab" in advert:
            advert["collab"]["href"] += f"?part={part_key}"
        entry["draft"] = advert
    return entry


def _resolve_item_param(value: Any, item: dict[str, Any]) -> Any:
    """``"{item.theme}"`` → the item's value; only whole-string placeholders
    are supported (params reach the wire as query values, not prose)."""
    if _is_item_template(value) and value.startswith("{item.") \
            and value.endswith("}"):
        return item.get(value[len("{item."):-1])
    return value


def _scoped_parts(rdef: ResourceDef, instance: Resource,
                  actions: dict[str, Any],
                  admitted_by_action: dict[str, dict[str, list[Any]]],
                  drafts: dict[Any, dict[str, Any]] | None,
                  base: str) -> dict[str, Any]:
    """The ``parts`` namespace: placed actions re-rendered per data item.

    An action appears on an item iff the item's key value is inside the
    action's acceptance set for the key field (no set → every item), so
    per-item availability falls out of the same declarations that tighten
    the schema. Top-level ``actions`` stays complete — parts is a
    refinement, not a replacement.
    """
    parts: dict[str, Any] = {}
    for name, defn in rdef.machine.actions.items():
        if defn.place is None or name not in actions:
            continue
        array, key = defn.place.array, defn.place.key
        items = getattr(instance.data, array, None) or []
        group = parts.setdefault(array, {"key": key, "items": [
            {"key": it.model_dump(mode="json")[key], "actions": {}}
            for it in items]})
        admitted = admitted_by_action.get(name, {}).get(key)
        for item, entry in zip(items, group["items"]):
            if admitted is not None and entry["key"] not in admitted \
                    and str(entry["key"]) not in {str(a) for a in admitted}:
                continue
            entry["actions"][name] = _bind_part_entry(
                actions[name], key, entry["key"], item.model_dump(mode="json"),
                defn, rdef, instance, drafts, base)
    for group in parts.values():
        group["items"] = [e for e in group["items"] if e["actions"]]
    return {k: v for k, v in parts.items() if v["items"]}


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
    # prose is human-facing: humanized labels; tokens stay in the
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
    drafts: dict[Any, dict[str, Any]] | None = None,
    resolved: bool = True,
) -> dict[str, Any]:
    """Render the resource envelope.

    ``drafts`` maps ``(action, part_key)`` → stored draft row, already
    audience-filtered by the caller (loading is the engine's job — render
    stays storage-free). ``resolved=False`` is ``project``: pure, total,
    reads-declaring refinements skipped by declaration.
    """
    self_href = f"{base}/{rdef.plural}/{instance.id}"
    actions: dict[str, Any] = {}
    unavailable: dict[str, Any] = {}
    admitted_by_action: dict[str, dict[str, list[Any]]] = {}

    for defn in rdef.machine.actions.values():
        if defn.bulk:
            continue  # collection-level affordance
        if instance.state in defn.from_:
            status, deny, denier = await probe_transition(
                defn, instance, ctx, resolved=resolved)
            if status == "available":
                admitted = await _admitted_values(defn, instance, ctx,
                                                  resolved=resolved)
                admitted_by_action[defn.name] = admitted
                actions[defn.name] = _action_entry(
                    defn, rdef, f"{self_href}/-/{defn.name}", admitted,
                    instance, (drafts or {}).get((defn.name, "")))
            elif status == "unavailable":
                unavailable[defn.name] = _unavailable_entry(defn, deny, denier, instance)
            # hidden: appears in neither map
        else:
            if await probe_hidden_only(defn, instance, ctx, resolved=resolved):
                continue
            unavailable[defn.name] = _out_of_state_entry(defn, instance.state)

    doc: dict[str, Any] = {
        "waymark": FORMAT_VERSION,
        "kind": rdef.kind,
        "self": self_href,
        "state": instance.state,
        "summary": render_summary(rdef.summary_template, instance),
        "data": instance.data.model_dump(mode="json"),
        "actions": _strip_unscoped(actions),
        "unavailable": unavailable,
        # placed actions re-rendered per data item; omitted at depth=summary
        # (top-level actions stay the complete truth, so agents lose nothing).
        # Parts bind BEFORE the strip: {item.*} params resolve per item there,
        # and only the unplaced rendering drops them as unresolvable.
        **({"parts": parts} if depth != "summary"
           and (parts := _scoped_parts(rdef, instance, actions,
                                       admitted_by_action, drafts, base)) else {}),
        "links": render_links(instance, rdef, base=base, self_href=self_href),
        # depth=summary is the agent default: presentation hints are
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


async def project(instance: Resource, rdef: ResourceDef, *, ctx: Ctx,
                  depth: str = "full", base: str = "/api") -> dict[str, Any]:
    """The pure, total stage: no storage, no reads, never fails for lack of
    a ctx. Resolve (the engine's GET) only ever tightens this."""
    return await render(instance, rdef, ctx=ctx, depth=depth, base=base,
                        resolved=False)


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
    resolved: bool = True,
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
        doc = await render(item, rdef, ctx=ctx, depth="summary", base=base,
                           resolved=resolved)
        if not rdef.cls.row_affordances:
            doc["actions"] = None  # explicitly unknown, distinct from {} = none
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
