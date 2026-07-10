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

The wire is identical either way (``waymark: "8"``); each action entry
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

FORMAT_VERSION = "8"

Warnings = list[tuple[Deny, Guard]]
Probe = (
    tuple[Literal["available"], None, None, Warnings]
    | tuple[Literal["unavailable"], Deny, Guard, Warnings]
    | tuple[Literal["hidden"], Deny, Guard, Warnings]
)


def make_etag(kind: str, id: str, version: int) -> str:
    return f'W/"{kind}-{id}-v{version}"'


async def probe_transition(defn: ActionDef, instance: Resource, ctx: Ctx,
                           *, resolved: bool = True) -> Probe:
    probe_ctx = _as_probe(ctx)
    warnings: Warnings = []
    for g in defn.guards:
        if g.reads and not resolved:
            continue  # project(): ctx-dependent availability is resolve's job
        verdict, denier = await g.evaluate(instance, None, probe_ctx)
        if isinstance(verdict, Deny):
            if denier.severity == "warning":
                # a warning does not un-advertise the action (design E1);
                # it rides the entry so the client can confirm-with-reason
                warnings.append((verdict, denier))
                continue
            if denier.hide:  # first refusal wins
                return "hidden", verdict, denier, []
            return "unavailable", verdict, denier, []
    return "available", None, None, warnings


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
               _invoker=ctx._invoker, _reader=ctx._reader,
               _finder=ctx._finder, _rate=ctx._rate, _creator=ctx._creator,
               _actor_of=ctx._actor_of, _deferrer=ctx._deferrer,
               _lifecycle=ctx._lifecycle)


def _draft_advert(defn: ActionDef, rdef: ResourceDef, instance: Resource,
                  draft_row: dict[str, Any] | None, *, base: str,
                  part_key: str = "") -> dict[str, Any]:
    """The draft sub-resource advert (design §4): a link with status, never
    an inlined blob — GET the envelope for values/revs/authors. One code
    path for placed and unplaced actions (the binding *is* the part key) —
    v2 emitted the unplaced advert and string-patched the hrefs per part.
    """
    from .drafts import PROTOCOL, draft_href

    policy = defn.draft_policy
    advert: dict[str, Any] = {
        "href": draft_href(base, rdef, instance.id, defn.name, part_key),
        "kind": "draft",
    }
    if policy is not None and policy.shared:
        advert["shared"] = True
    if policy is not None and policy.live:
        collab = (f"{base}/{rdef.plural}/{instance.id}/-/{defn.name}"
                  "/draft/collab")
        if part_key:
            collab += f"?part={part_key}"
        advert["collab"] = {"href": collab, "protocol": PROTOCOL}
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
                  draft_row: dict[str, Any] | None,
                  base: str,
                  warnings: "Warnings | None" = None) -> dict[str, Any]:
    entry: dict[str, Any] = {"method": "POST", "href": href}
    if warnings:
        # advisory guards (design E1): advertised, refusable-with-override —
        # the entry says so up front, the same reason enforcement will give,
        # with the guard's declared remedies riding it like a refusal's
        entry["warnings"] = [
            {"name": g.name,
             "reason": g.render_reason(d, instance),
             **({"remedies": list(g.remedies)} if g.remedies else {})}
            for d, g in warnings]
    schema: dict[str, Any] | None = None
    if defn.input is not None:
        schema = _admits_schema(rdef.action_schemas[defn.name][0], admitted)
        if defn.prefill:
            schema = _prefill_schema(schema, defn, instance)
        schema = _when_schema(schema, defn, instance)
        entry["input"] = schema
    entry["effect"] = defn.effect.to_wire()
    entry["safety"] = defn.safety.to_wire()
    entry["effort"] = demand_class(defn, schema)
    if defn.batch_spec is not None:
        # the batch form (design §7): the same action, N inputs, one
        # report — advertised on the entry, invoked at its own route
        entry["batch"] = {
            "href": f"{href}/batch",
            "atomic": defn.batch_spec.atomic,
            "max_items": defn.batch_spec.max_items,
        }
    if defn.draft and defn.place is None:
        # placed drafts advertise per part (each part is its own draft)
        entry["draft"] = _draft_advert(defn, rdef, instance, draft_row,
                                       base=base)
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


async def _relation_sets(defn: ActionDef, instance: Resource, ctx: Ctx,
                         *, resolved: bool) -> list[tuple[tuple[str, ...], list[tuple]]]:
    """Every relation's admissible-tuple set (design §5), same
    project/resolve discipline as single-field acceptance sets."""
    out: list[tuple[tuple[str, ...], list[tuple]]] = []
    for g in defn.guards:
        for leaf in g.iter_leaves():
            if not leaf.is_relation:
                continue
            if leaf.reads and not resolved:
                continue
            tuples = await leaf.admitted(instance, ctx)
            if tuples is None:
                continue
            out.append((leaf.judges, [tuple(t) for t in tuples]))
    return out


def _when_schema(schema: dict[str, Any], defn: ActionDef,
                 instance: Resource) -> dict[str, Any]:
    """Fold declared When predicates (design §5) into the advertised input
    schema as JSON Schema ``if``/``then``(/``else``) — resource-field
    comparands resolved to this document's current values, exactly as
    accepts= enums fold per resource. Pure in (schema, instance), so
    project and resolve advertise identically. One clause rides the schema
    root; several wrap in ``allOf``."""
    from ..core.when import whens_of

    whens = whens_of(defn.input) if defn.input is not None else {}
    if not whens:
        return schema
    clauses = [c for w in whens.values()
               if (c := w.schema_clause(instance)) is not None]
    if not clauses:
        return schema
    if len(clauses) == 1:
        return {**schema, **clauses[0]}
    return {**schema, "allOf": [*schema.get("allOf", ()), *clauses]}


async def _create_surface(rdef: ResourceDef, ctx: Ctx, *, resolved: bool
                          ) -> tuple[dict[str, list[Any]], Warnings]:
    """The create form's projection (design §10): create-guard ``accepts=``
    sets fold into the advertised create schema exactly as action guards
    fold into action schemas (``_admitted_values``), and warning-severity
    create guards whose checks decide without input ride the entry the way
    action warnings do (design E1).

    A create guard's check grades the input by definition (it runs with
    r=None), so the probe runs a check only when the declaration says
    ``needs_input=False`` — everything else surfaces at dry-run, the same
    boundary ``probe_transition`` draws with ``pending_input``."""
    probe_ctx = _as_probe(ctx)
    admitted: dict[str, list[Any]] = {}
    warnings: Warnings = []
    for top in rdef.cls.create_guards:
        for leaf in top.iter_leaves():
            if leaf.reads and not resolved:
                continue  # project(): ctx-dependent sets are resolve's job
            if leaf.admits is not None:
                values = await leaf.admitted(None, probe_ctx)
                if values is not None:
                    fld = leaf.admits[0]
                    admitted[fld] = ([v for v in admitted[fld] if v in values]
                                     if fld in admitted else values)
            if leaf.check is not None and leaf.severity == "warning" \
                    and getattr(leaf, "declared_needs_input", None) is False:
                verdict, denier = await leaf.evaluate(None, None, probe_ctx)
                if isinstance(verdict, Deny):
                    warnings.append((verdict, denier))
    return admitted, warnings


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
                     base: str,
                     rel_enums: dict[str, list[Any]] | None = None) -> dict[str, Any]:
    """The per-item projection of an action entry: the scope key becomes a
    ``const`` (the user never re-picks what they clicked), picker params
    templated over the item (``{item.theme}``) resolve to its values,
    relation-admitted values for this key become the other judged field's
    ``enum`` (design §5), and a draftable placed action adverts *this
    part's* draft."""
    entry = dict(base_entry)
    schema = entry.get("input")
    if schema:
        props = dict(schema.get("properties", {}))
        if key in props:
            bound = {k: v for k, v in props[key].items() if k != "enum"}
            bound["const"] = key_value
            props[key] = bound
        for fname, vals in (rel_enums or {}).items():
            if fname in props:
                props[fname] = {**props[fname], "enum": vals}
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
        part_key = str(key_value)
        row = (drafts or {}).get((defn.name, part_key))
        entry["draft"] = _draft_advert(defn, rdef, instance, row,
                                       base=base, part_key=part_key)
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
                  relations_by_action: dict[str, list[tuple[tuple[str, ...], list[tuple]]]],
                  drafts: dict[Any, dict[str, Any]] | None,
                  base: str) -> dict[str, Any]:
    """The ``parts`` namespace: placed actions re-rendered per data item.

    An action appears on an item iff the item's key value is inside the
    action's acceptance set for the key field (no set → every item) *and*
    every relation over the key admits at least one tuple for it (design
    §5) — per-item availability falls out of the same declarations that
    tighten the schema. Top-level ``actions`` stays complete — parts is a
    refinement, not a replacement.
    """
    from ..core.groups import groups_of

    parts: dict[str, Any] = {}
    for name, defn in rdef.machine.actions.items():
        if defn.place is None or name not in actions:
            continue
        array, key = defn.place.array, defn.place.key
        items = getattr(instance.data, array, None) or []
        group = parts.setdefault(array, {"key": key, "items": [
            {"key": it.model_dump(mode="json")[key], "actions": {}}
            for it in items]})
        if items:
            one_of = {gname: g.to_wire()
                      for gname, g in groups_of(type(items[0])).items()}
            if one_of:
                group["one_of"] = one_of
        admitted = admitted_by_action.get(name, {}).get(key)
        rel_sets = relations_by_action.get(name, ())
        for item, entry in zip(items, group["items"]):
            if admitted is not None and entry["key"] not in admitted \
                    and str(entry["key"]) not in {str(a) for a in admitted}:
                continue
            rel_enums: dict[str, list[Any]] = {}
            admissible = True
            for fields, tuples in rel_sets:
                if key not in fields:
                    continue
                ki = fields.index(key)
                matching = [t for t in tuples
                            if t[ki] == entry["key"]
                            or str(t[ki]) == str(entry["key"])]
                if not matching:
                    admissible = False  # nothing this item could accept
                    break
                if len(fields) == 2:
                    oi = 1 - ki
                    vals = list(dict.fromkeys(t[oi] for t in matching))
                    other = fields[oi]
                    rel_enums[other] = ([v for v in rel_enums[other] if v in vals]
                                        if other in rel_enums else vals)
            if not admissible:
                continue
            entry["actions"][name] = _bind_part_entry(
                actions[name], key, entry["key"], item.model_dump(mode="json"),
                defn, rdef, instance, drafts, base, rel_enums=rel_enums)
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


def _row_law(instance: Resource, rdef: ResourceDef) -> str | None:
    """The definition revision row id the row's ``law_revision`` stamp
    resolves to (design 7.0 §3) — the kind's current law for an unstamped
    row or an unresolvable stamp (the same fallback the write anchor
    uses, so meta.law and defined_by cannot disagree)."""
    stamp = getattr(instance, "law_revision", None)
    if stamp is not None and stamp != rdef.current_law_revision:
        row_law = (rdef.law_ids or {}).get(stamp)
        if row_law is not None:
            return row_law
    return rdef.current_law


def _adopt_entry(instance: Resource, self_href: str,
                 rdef: ResourceDef) -> dict[str, Any]:
    """The engine-injected ``adopt`` affordance (design 7.0 §3): offered
    exactly when the current law has passed this row by — a same-state
    transition that restamps and recomputes. No ActionDef exists (the
    engine injects it on every kind, like ``create``); the entry is built
    to the same wire shape.

    The consequence names what recomputing actually costs, when it's
    knowable (design §4 follow-up): "your facts recompute" told a reader
    nothing they could act on — which facts, and whether recomputing one
    currently unblocks a gated action, are exactly the two things
    ``rdef.recomputing``/``recomputing_blocks`` already know statically
    and dynamically. Falls back to the general sentence only when no
    kind-wide Deferred backfill is in flight (no more specific claim to
    make honestly)."""
    from ..core.types import Effect, Safety

    consequence = ("This row moves to the current revision of its kind's "
                  "law and its facts recompute under it.")
    if rdef.recomputing:
        facts = ", ".join(rdef.recomputing)
        consequence = (f"This row moves to the current revision of its "
                       f"kind's law and recomputes: {facts}.")
        blocked = sorted({name for fact in rdef.recomputing
                          for name in rdef.recomputing_blocks.get(fact, ())})
        if blocked:
            consequence += f" Currently blocks: {', '.join(blocked)}."

    return {
        "method": "POST",
        "href": f"{self_href}/-/adopt",
        "effect": Effect(to=instance.state).to_wire(),
        "safety": Safety(idempotent=True, reversible=False, confirm=True,
                         consequence=consequence).to_wire(),
        "effort": "assent",
        "display": {"label": "Adopt the current law",
                    "description": "The law moved on while this row lived "
                                   "under an older revision; adopting "
                                   "restamps it and recomputes its facts "
                                   "(design 7.0 §3)."},
    }


def _empty_required_admission(defn: ActionDef, rdef: ResourceDef,
                              admitted: dict[str, list[Any]]) -> str | None:
    """The first required input field whose guard-declared accepts= set came
    back empty — no row on this document currently qualifies, so the unbound
    action has nothing valid to offer no matter what the client submits.
    Fields with no accepts= guard never appear in ``admitted`` (design
    §_admitted_values), so unguarded and optional fields are unaffected."""
    if defn.input is None:
        return None
    required = rdef.action_schemas[defn.name][0].get("required", ())
    for field in required:
        if field in admitted and not admitted[field]:
            return field
    return None


def _no_admissible_rows_entry(defn: ActionDef, field: str) -> dict[str, Any]:
    label = (defn.display or {}).get("label", defn.name.replace("_", " "))
    return {"reason": f"No {field.replace('_', ' ')} currently qualifies "
                       f"for '{label}'."}


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
        entry = {"href": base + href if href.startswith("/") else href,
                 "kind": ld.kind,
                 **({"summary": ld.summary} if ld.summary else {})}
        if ld.embed:
            # an invitation to co-present, not a substitute for the href
            entry["embed"] = True
        if ld.badge is not None:
            # scent (§4): the named Data field's current value rides the
            # link; None is absence, not a zero to render
            value = getattr(instance.data, ld.badge, None)
            if value is not None:
                entry["badge"] = value
        links[ld.rel] = entry
    # the surfaces anchored on this kind (design 6.0 §4): each is a named,
    # law-governed view of this resource — advertised as a link so the
    # client discovers the composition instead of assembling it
    for sdef in rdef.extra.get("surfaces", ()) or ():
        links.setdefault(f"surface:{sdef.name}", {
            "href": f"{base}/surfaces/{sdef.name}/{instance.id}",
            "kind": f"surface:{sdef.name}",
            "summary": f"The {sdef.name.replace('-', ' ')} view",
        })
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


def _project_data(data: dict[str, Any], vis: Any, kind: str,
                  id: str | None, owner: str | None = None) -> dict[str, Any]:
    """The data namespace through the principal's visibility (design §1):
    a hidden field is never rendered — absence produced at the source, not
    popped after. Visibility is granted at field granularity: ``clear``
    reveals the field's whole value, nested structure included — sub-keys
    of a list-of-objects field (a plan's days) are not kind-level fields,
    so no grant could ever name them."""
    out: dict[str, Any] = {}
    for key, value in data.items():
        mode = vis.field(kind, key, id, owner)
        if mode == "clear":
            out[key] = value
        elif mode == "hashed":
            out[key] = vis.hash(value)
        # hidden: the field does not exist for this principal
    return out


def _scope_unavailable_entry() -> dict[str, Any]:
    from .grants import SCOPE_REASON

    return {"reason": SCOPE_REASON,
            "remedies": ["grant.request_access"]}


def _render_display(instance: Resource, rdef: ResourceDef) -> dict[str, Any]:
    from ..core.groups import groups_of

    declared = dict(rdef.cls.display)
    display: dict[str, Any] = {}
    title = declared.pop("title", None)
    if title:
        display["title"] = _SummaryFormatter(instance).vformat(title, (), {})
    state_display = declared.pop("state", {}) or {}
    states_map = state_display if all(isinstance(v, dict) for v in state_display.values()) else {}
    display["state"] = {"label": state_label(instance.state),
                       **states_map.get(instance.state, {})}
    one_of = {name: g.to_wire()
              for name, g in groups_of(type(instance.data)).items()}
    if one_of:
        display["one_of"] = one_of
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
    rollups: dict[str, int] | None = None,
) -> dict[str, Any]:
    """Render the resource envelope.

    ``drafts`` maps ``(action, part_key)`` → stored draft row, already
    audience-filtered by the caller (loading is the engine's job — render
    stays storage-free). ``resolved=False`` is ``project``: pure, total,
    reads-declaring refinements skipped by declaration.
    """
    from .grants import NEGOTIATION_REASON, visibility_of

    vis = visibility_of(ctx.principal, ctx.now)
    raw_data = instance.data.model_dump(mode="json")

    self_href = f"{base}/{rdef.plural}/{instance.id}"
    actions: dict[str, Any] = {}
    unavailable: dict[str, Any] = {}
    admitted_by_action: dict[str, dict[str, list[Any]]] = {}
    relations_by_action: dict[str, list[tuple[tuple[str, ...], list[tuple]]]] = {}

    for defn in rdef.machine.actions.values():
        if defn.bulk:
            continue  # collection-level affordance
        # visibility gates the projection (design §1): an ungranted action
        # is not probed, not offered, and honestly unavailable with the
        # remedy naming the fix — decided here, not redacted after
        mode = ("open" if vis.full
                else vis.action(rdef.kind, defn.name, instance.id, raw_data,
                                instance.owner))
        if mode == "none":
            unavailable[defn.name] = _scope_unavailable_entry()
            continue
        if mode == "negotiation":
            unavailable[defn.name] = {"reason": NEGOTIATION_REASON}
            continue
        if instance.state in defn.from_:
            status, deny, denier, warnings = await probe_transition(
                defn, instance, ctx, resolved=resolved)
            if status == "available":
                admitted = await _admitted_values(defn, instance, ctx,
                                                  resolved=resolved)
                admitted_by_action[defn.name] = admitted
                # a guard's accepts= can pass the coarse probe (it only sees
                # inp=None) yet admit no row at all — an unbound action with
                # a required, guard-emptied field has nothing valid to
                # submit, so it belongs in unavailable, not actions (design
                # §1: don't offer what the server already knows it'll refuse)
                empty_field = _empty_required_admission(defn, rdef, admitted)
                if empty_field is not None:
                    unavailable[defn.name] = _no_admissible_rows_entry(
                        defn, empty_field)
                else:
                    relations_by_action[defn.name] = await _relation_sets(
                        defn, instance, ctx, resolved=resolved)
                    entry = _action_entry(
                        defn, rdef, f"{self_href}/-/{defn.name}", admitted,
                        instance, (drafts or {}).get((defn.name, "")), base,
                        warnings)
                    if mode == "approval":
                        entry["access"] = "approval"
                    actions[defn.name] = entry
            elif status == "unavailable":
                unavailable[defn.name] = _unavailable_entry(defn, deny, denier, instance)
            # hidden: appears in neither map
        else:
            if await probe_hidden_only(defn, instance, ctx, resolved=resolved):
                continue
            unavailable[defn.name] = _out_of_state_entry(defn, instance.state)

    # the engine-injected adopt (design 7.0 §3): advertised exactly when
    # the current law has passed this row by — never on terminal rows (the
    # finished keep their law) and never over a machine that declares its
    # own `adopt` (the app owns its vocabulary)
    _stamp = getattr(instance, "law_revision", None)
    if (vis.full and _stamp is not None
            and rdef.current_law_revision is not None
            and _stamp < rdef.current_law_revision
            and instance.state not in rdef.machine.terminal
            and "adopt" not in rdef.machine.actions):
        actions.setdefault("adopt", _adopt_entry(instance, self_href, rdef))

    # the agent's own negotiation surface renders in the clear — it is the
    # agent's to read; everything else projects through the visibility
    own = (None if vis.full
           else vis.negotiation_actions(rdef.kind, instance.id, raw_data))
    if vis.full or own is not None:
        data = raw_data
        summary = render_summary(rdef.summary_template, instance)
    else:
        data = _project_data(raw_data, vis, rdef.kind, instance.id,
                             instance.owner)
        summary = (render_summary(rdef.summary_template, instance)
                   if vis.summary_clear(rdef.kind, instance.id,
                                        instance.owner)
                   else f"{rdef.kind.replace('_', ' ')} (scoped view)")

    links = render_links(instance, rdef, base=base, self_href=self_href)
    if not vis.full:
        # link summaries could out-say the projection; keep them structural
        links = {rel: {**{k: v for k, v in link.items() if k != "embedded"},
                       **({"summary": link.get("kind", rel)}
                          if "summary" in link else {})}
                 for rel, link in links.items() if link}

    doc: dict[str, Any] = {
        "waymark": FORMAT_VERSION,
        "kind": rdef.kind,
        "self": self_href,
        "state": instance.state,
        "summary": summary,
        "data": data,
        "actions": _strip_unscoped(actions),
        "unavailable": unavailable,
        # placed actions re-rendered per data item; omitted at depth=summary
        # (top-level actions stay the complete truth, so agents lose nothing).
        # Parts bind BEFORE the strip: {item.*} params resolve per item there,
        # and only the unplaced rendering drops them as unresolvable.
        # Parts inherit visibility for free (design §1): they re-render only
        # actions that projected into `actions` above.
        **({"parts": parts} if depth != "summary"
           and (parts := _scoped_parts(rdef, instance, actions,
                                       admitted_by_action,
                                       relations_by_action, drafts, base)) else {}),
        "links": links,
        # declared rollups (design E4): derived domain truth, kept apart
        # from stored data. Scoped views drop them — a count over children
        # the principal may not see is a leak (the display-drop precedent).
        **({"rollups": rollups} if rollups is not None and vis.full else {}),
        # depth=summary is the agent default: presentation hints are
        # quarantined display-only payload, so agents don't pay for them.
        # A scoped view drops display entirely — its templates render over
        # fields the projection may not admit.
        **({"display": _render_display(instance, rdef)}
           if depth != "summary" and vis.full else {}),
        "meta": {
            "version": instance.version,
            "etag": make_etag(rdef.kind, instance.id, instance.version),
            **({"updated_at": instance.updated_at.isoformat()}
               if instance.updated_at else {}),
            **({"owner": instance.owner} if instance.owner else {}),
            # the law (design 7.0 §3): the definition revision id governing
            # THIS ROW — the row's ``law_revision`` stamp resolved to its
            # revision row id, varying within a collection when a pilot or
            # grandfathered population is live; the kind's current law for
            # an unstamped row. A client — or a follower reading a
            # transcript — correlates what it saw with the law that
            # produced it. Additive: absent before the first revise
            # (pre-law), and a v4 client ignores it.
            **({"law": row_law} if (row_law := _row_law(instance, rdef))
               else {}),
            # the same law's revision NUMBER — the human spelling ("rev
            # N") beside the machine id, the row's stamp itself, so no
            # client resolves the deploy history just to say which rev
            **({"law_revision": row_rev}
               if (row_rev := (getattr(instance, "law_revision", None)
                               or rdef.current_law_revision)) else {}),
            # facts catching up with a redefinition (design §4): the value
            # in data was materialized under a superseded law and a
            # declared Deferred is recomputing it — served as data,
            # honestly marked, never as filterable truth
            **({"recomputing": list(rdef.recomputing)}
               if rdef.recomputing else {}),
            # what recomputing actually costs (design §4 follow-up): the
            # static fact→action index, filtered to the facts currently
            # stale — additive sibling to "recomputing", same shape rule
            **({"recomputing_blocks": blocks} if (blocks := {
                    fact: list(rdef.recomputing_blocks[fact])
                    for fact in rdef.recomputing
                    if rdef.recomputing_blocks.get(fact)
                }) else {}),
        },
    }
    if embeds and vis.full:
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
    rollups_by_id: dict[str, dict[str, int]] | None = None,
    rows_none: bool = False,
) -> dict[str, Any]:
    from urllib.parse import urlencode

    # In filters parse to lists; on the wire they are comma strings, and the
    # self href (what a client re-reads its own filters from) must say so
    applied_query = {k: (",".join(str(x) for x in v)
                         if isinstance(v, (list, tuple)) else v)
                     for k, v in (applied_query or {}).items() if v is not None}

    def page_href(number: int) -> str:
        params = {**applied_query, "page[size]": page_size, "page[number]": number}
        return f"{base}/{rdef.plural}?{urlencode(params)}"

    self_href = page_href(page_number) if applied_query or page_number > 1 \
        else f"{base}/{rdef.plural}"

    rendered_items: list[dict[str, Any]] = []
    if not rows_none:
        for item in items:
            doc = await render(item, rdef, ctx=ctx, depth="summary",
                               base=base, resolved=resolved,
                               rollups=(rollups_by_id or {}).get(item.id))
            if not rdef.cls.row_affordances:
                doc["actions"] = None  # explicitly unknown, distinct from {} = none
                doc["unavailable"] = None
            rendered_items.append(doc)

    from .grants import visibility_of

    vis = visibility_of(ctx.principal, ctx.now)

    filters_desc = ", ".join(f"{k}={v}" for k, v in applied_query.items()
                             if k not in ("sort", "page[size]",
                                          "page[number]", "rows"))
    summary = (f"{rdef.plural.capitalize()} · {total} total" if rows_none
               else f"{rdef.plural.capitalize()} · {len(items)} of {total} "
                    "shown")
    if filters_desc:
        summary = f"{summary} · filtered: {filters_desc}"
    if not vis.full:
        summary = f"{rdef.kind} collection (scoped view)"

    query_schema = rdef.query_schema
    if rdef.recomputing:
        # the un-advertising half of design §4's Deferred: a fact still
        # catching up with its redefinition is dropped from the query
        # surface — every parameter it minted — the Service-down honesty
        # applied to truth. parse_query refuses the same names, so the
        # advertisement and the enforcement cannot disagree.
        from ..core.schemas import field_params

        dropped = {p for fact in rdef.recomputing
                   for p in field_params(fact)}
        props = {k: v for k, v in query_schema["properties"].items()
                 if k not in dropped}
        # sort is blocked with the filters (design §4): ordering a
        # collection by a column half old-law, half new ranks rows by
        # two laws at once — both spellings leave the enum, and
        # parse_query refuses them with the same Problem
        sort_prop = props.get("sort")
        if sort_prop and "enum" in sort_prop:
            blocked = {s for f in rdef.recomputing for s in (f, f"-{f}")}
            trimmed = {**sort_prop,
                       "enum": [v for v in sort_prop["enum"]
                                if v not in blocked]}
            if str(trimmed.get("default", "")).removeprefix("-") \
                    in rdef.recomputing:
                trimmed.pop("default", None)
            props["sort"] = trimmed
        query_schema = {**query_schema, "properties": props}
    if facets:
        query_schema = {**query_schema, "properties": dict(query_schema["properties"])}
        for fname, counts in facets.items():
            if fname in query_schema["properties"]:
                prop = {**query_schema["properties"][fname], "x-facets": counts}
                if "enum" not in prop:
                    # a dynamic vocabulary (faceted, not statically enumerable):
                    # the observed values are the choices, refreshed per render
                    prop["enum"] = sorted(counts)
                query_schema["properties"][fname] = prop

    # the create surface joins projection (design §10): create-guard
    # acceptance sets fold into the advertised schema as enums, and
    # probeable warning guards ride the entry — the same folds the action
    # surface gets, applied to the surface E9 left enforce-only
    create_input = rdef.extra.get("create_schema") or rdef.data_schema
    create_admitted, create_warnings = await _create_surface(
        rdef, ctx, resolved=resolved)
    create_input = _admits_schema(create_input, create_admitted)
    actions: dict[str, Any] = {}
    unavailable: dict[str, Any] = {}

    def place(name: str, entry: dict[str, Any]) -> None:
        """Collection affordances project through the same visibility as
        everything else (design §1) — no `_collection` suffix dispatch."""
        mode = "open" if vis.full else vis.action(rdef.kind, name)
        if mode == "none":
            unavailable[name] = _scope_unavailable_entry()
        elif mode == "approval":
            actions[name] = {**entry, "access": "approval"}
        else:
            actions[name] = entry

    create_entry: dict[str, Any] = {
        "method": "POST",
        "href": f"{base}/{rdef.plural}",
        "input": create_input,
        "effect": {"to": rdef.machine.initial},
        "safety": {"idempotent": False, "reversible": False, "confirm": False},
    }
    if create_warnings:
        # advisory create guards (design E1 via §10): advertised up front,
        # refusable-with-override — the same shape action entries carry
        create_entry["warnings"] = [
            {"name": g.name, "reason": g.render_reason(d, None),
             **({"remedies": list(g.remedies)} if g.remedies else {})}
            for d, g in create_warnings]
    place("create", create_entry)
    actions["query"] = {
        "method": "GET",
        "href": f"{base}/{rdef.plural}",
        "input": query_schema,
        "safety": {"idempotent": True, "reversible": True, "confirm": False},
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
        place(defn.name, entry)

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
            # rows=none (design §9): the envelope without its rows — total
            # and facets come from the same WHERE the rows would; None is
            # "not asked for", distinct from [] = "asked, none matched"
            "items": None if rows_none else rendered_items,
            "total": total,
            "page": {"size": page_size, "number": page_number},
        },
        "actions": actions,
        "unavailable": unavailable,
        "links": links,
        # collections carry the law too (design §3): the same revision id
        # every member envelope carries — and, while a Deferred backfill
        # runs, the same recomputing mark (design §4)
        "meta": {"version": 0, "etag": 'W/"collection"',
                 **({"law": rdef.current_law} if rdef.current_law else {}),
                 **({"law_revision": rdef.current_law_revision}
                    if rdef.current_law_revision else {}),
                 **({"recomputing": list(rdef.recomputing)}
                    if rdef.recomputing else {}),
                 **({"recomputing_blocks": blocks} if (blocks := {
                        fact: list(rdef.recomputing_blocks[fact])
                        for fact in rdef.recomputing
                        if rdef.recomputing_blocks.get(fact)
                    }) else {})},
    }
