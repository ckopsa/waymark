"""What applications supply to the conformance suite — as little as possible.

2.0 (design §9): the suite derives more and asks for less.

- **State factories are the override, not the baseline tax.** With no
  ``@state_factory``, the suite walks the machine's own ``path_to(state)``
  from a schema-synthesized create, filling inputs from each guard's
  declared acceptance set. Register a factory only when a path needs
  semantic setup the declarations can't express (seeded siblings, cross-
  resource prerequisites).
- ``@example_input(Resource, action)`` remains for ``check=``-style guards
  with acknowledged open judgment — the closure rule keeps those rare.
  ``"create"`` is accepted as a pseudo-action for create bodies.
- ``set_principals({...})`` overrides the default profiles.
"""
from __future__ import annotations

import inspect
import uuid
from typing import Any, Awaitable, Callable

from ..core.actions import ActionDef
from ..core.registry import ResourceDef
from ..core.resource import Resource
from ..core.types import Principal

StateFactory = Callable[..., Awaitable[Resource]]
ExampleFn = Callable[..., Any]

_FACTORIES: dict[str, tuple[type[Resource], StateFactory]] = {}
_RESOURCES: dict[str, type[Resource]] = {}
_EXAMPLES: dict[tuple[str, str], ExampleFn] = {}

DEFAULT_PRINCIPALS: dict[str, Principal] = {
    "anonymous": Principal.anonymous(),
    "owner": Principal(id="owner", type="human", display="Owner"),
    "manager": Principal(id="manager", type="human",
                         roles=frozenset(["manager"]), display="Manager"),
    "agent": Principal(id="agent-1", type="agent", display="Agent"),
}

_PRINCIPALS: dict[str, Principal] = dict(DEFAULT_PRINCIPALS)

# the default walker acts as a system principal; role-gated paths need a
# registered factory (the suite's message says so when it happens)
WALKER = Principal(id="waymark9-walker", type="system",
                   display="Conformance walker")


class SkipState(Exception):
    """Raised when a state cannot be honestly produced. Factories raise it
    deliberately; the default walker raises it with the reason and the fix."""


def conformance_resource(resource: type[Resource]) -> type[Resource]:
    """Enroll a resource in the conformance suite with the derived walker as
    its factory. ``@state_factory`` implies enrollment."""
    _RESOURCES[resource.kind] = resource
    return resource


def state_factory(resource: type[Resource]) -> Callable[[StateFactory], StateFactory]:
    _RESOURCES[resource.kind] = resource

    def decorate(fn: StateFactory) -> StateFactory:
        _FACTORIES[resource.kind] = (resource, fn)
        return fn
    return decorate


def example_input(resource: type[Resource], action: str) -> Callable[[ExampleFn], ExampleFn]:
    if action != "create" and action not in resource.__waymark_machine__.actions:
        raise ValueError(f"{resource.kind} has no action {action!r}")

    def decorate(fn: ExampleFn) -> ExampleFn:
        _EXAMPLES[(resource.kind, action)] = fn
        return fn
    return decorate


def set_principals(profiles: dict[str, Principal]) -> None:
    _PRINCIPALS.clear()
    _PRINCIPALS.update(profiles)


def principals() -> dict[str, Principal]:
    return dict(_PRINCIPALS)


def resources() -> dict[str, type[Resource]]:
    return dict(_RESOURCES)


def factories() -> dict[str, tuple[type[Resource], StateFactory]]:
    return dict(_FACTORIES)


async def example_for(kind: str, action: str, services: Any) -> dict[str, Any] | None:
    fn = _EXAMPLES.get((kind, action))
    if fn is None:
        return None
    result = fn(services)
    if inspect.isawaitable(result):
        result = await result
    return result


# ── input synthesis (design §9): schema sample ∩ declared acceptance sets ──
def _schema_sample(schema: dict[str, Any]) -> dict[str, Any]:
    from hypothesis import HealthCheck, find, settings
    from hypothesis_jsonschema import from_schema

    return find(from_schema(schema), lambda _: True,
                settings=settings(database=None, max_examples=50,
                                  suppress_health_check=list(HealthCheck)))


async def synthesize_input(engine: Any, rdef: ResourceDef, defn: ActionDef,
                           instance: Resource | None = None
                           ) -> dict[str, Any] | None:
    """Valid input for an action: a registered example, else a schema sample
    with every guard-graded field replaced by a value from that guard's own
    ``accepts`` set — the declarations that tighten the form also feed the
    suite."""
    if defn.input is None:
        return None
    example = await example_for(rdef.kind, defn.name, engine.services)
    if example is not None:
        return example
    sample = _schema_sample(rdef.action_schemas[defn.name][0])
    if instance is None:
        return sample
    if defn.place is not None:
        items = getattr(instance.data, defn.place.array, None) or []
        if not items:
            raise SkipState(
                f"{rdef.kind}.{defn.name} is placed on data."
                f"{defn.place.array}, which is empty on the walked instance; "
                "register a @state_factory that seeds it")
        # a starting part; the acceptance-set intersection below may narrow
        # it (e.g. a sundays-only action must land on a Sunday part)
        sample[defn.place.key] = items[0].model_dump(mode="json")[defn.place.key]
    async with engine.storage.session() as s:
        ctx = engine.invoker._ctx(WALKER, s, mode="probe")
        # intersect every guard's acceptance set per field — exactly what
        # render does to the advertised enum. Relations are handled after:
        # their accepts is a *tuple* set, not a per-field one.
        admitted_by_field: dict[str, list[Any]] = {}
        relations: list[Any] = []
        for top in defn.guards:
            for g in top.iter_leaves():
                if g.is_relation:
                    if g.relation_admits is not None:
                        relations.append(g)
                    continue
                if g.accepts is None:
                    continue
                field = g.judges[0]
                admitted = await g.admitted(instance, ctx)
                if admitted is None:
                    continue
                if field in admitted_by_field:
                    keep = {str(a) for a in admitted}
                    admitted_by_field[field] = [
                        v for v in admitted_by_field[field] if str(v) in keep]
                else:
                    admitted_by_field[field] = list(admitted)
        for field, admitted in admitted_by_field.items():
            if not admitted:
                raise SkipState(
                    f"{rdef.kind}.{defn.name}: the intersected acceptance "
                    f"set for {field!r} is empty on the walked instance; "
                    "register a @state_factory that satisfies it")
            if str(sample.get(field)) not in {str(a) for a in admitted}:
                sample[field] = admitted[0]
        # relation tuple sets (design §5): overlay one admissible tuple,
        # components in judges order — preferring a tuple that agrees with
        # the single-field sets already applied
        for g in relations:
            allowed = await g.admitted(instance, ctx)
            if allowed is None:
                continue
            if not allowed:
                raise SkipState(
                    f"{rdef.kind}.{defn.name}: relation {g.name!r} admits "
                    "no tuples on the walked instance; register a "
                    "@state_factory that satisfies it")
            ordered = sorted((tuple(a) for a in allowed), key=str)

            def _fits(tup: tuple) -> bool:
                return all(
                    f not in admitted_by_field
                    or str(v) in {str(a) for a in admitted_by_field[f]}
                    for f, v in zip(g.judges, tup))

            chosen = next((t for t in ordered if _fits(t)), ordered[0])
            for f, v in zip(g.judges, chosen):
                sample[f] = v
    return sample


async def walk_to_state(kind: str, state: str, engine: Any) -> Resource:
    """The derived state factory: create via the schema, walk the machine's
    shortest path, inputs from acceptance sets. Honest about its limits —
    every dead end names the registration that fixes it."""
    from ..server.problems import Problem, WarningRefused

    rdef = engine.registry[kind]
    path = rdef.machine.path_to(state)
    if path is None:
        raise SkipState(f"{state!r} is unreachable from "
                        f"{rdef.machine.initial!r} by non-bulk transitions")
    create_body = await example_for(kind, "create", engine.services)
    if create_body is None:
        create_body = _schema_sample(rdef.extra["create_schema"])
    try:
        try:
            result = await engine.invoker.create(
                kind, create_body, principal=WALKER,
                idempotency_key=f"walker-{uuid.uuid4().hex}")
        except WarningRefused as exc:
            # advisory create guards (design E9) are acknowledged on retry,
            # exactly as _walk_invoke does for actions — a warning is not
            # a wall, and the walker is an honest client
            names = frozenset(
                (exc.extras.get("acknowledge") or {}).get("names") or ())
            result = await engine.invoker.create(
                kind, create_body, principal=WALKER,
                idempotency_key=f"walker-{uuid.uuid4().hex}",
                acknowledged=names)
    except Problem as exc:
        raise SkipState(
            f"schema-synthesized create for {kind} was refused "
            f"({exc.status}: {exc.detail}); register "
            f"@example_input({kind}, 'create') or a @state_factory") from exc
    resource_id = result.doc["self"].rsplit("/", 1)[-1]
    for defn in path:
        async with engine.storage.session() as s:
            instance = await engine.storage.load(s, kind, resource_id)
        body = await synthesize_input(engine, rdef, defn, instance)
        try:
            await _walk_invoke(engine, kind, resource_id, defn, body, instance)
        except Problem as exc:
            raise SkipState(
                f"walking {kind} to {state!r}: {defn.name} refused "
                f"({exc.status}: {exc.detail}); register a @state_factory "
                f"or @example_input({kind}, {defn.name!r})") from exc
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, resource_id)


async def _walk_invoke(engine: Any, kind: str, resource_id: str, defn: Any,
                       body: dict[str, Any] | None, instance: Any) -> None:
    """One walker invocation; advisory guards (design E1) are acknowledged
    on retry — the walker is an honest client, and a warning is not a wall."""
    from ..server.problems import WarningRefused

    kwargs = dict(
        principal=WALKER,
        idempotency_key=None if defn.safety.idempotent
        else f"walker-{uuid.uuid4().hex}",
        if_match=(f'W/"{kind}-{resource_id}-v{instance.version}"'
                  if defn.safety.fence else None))
    try:
        await engine.invoker.invoke(kind, resource_id, defn.name, body,
                                    **kwargs)
    except WarningRefused as exc:
        names = frozenset((exc.extras.get("acknowledge") or {}).get("names")
                          or ())
        await engine.invoker.invoke(kind, resource_id, defn.name, body,
                                    acknowledged=names, **kwargs)


def _is_mirror_kind(cls: type) -> bool:
    """A whole-resource :class:`~...server.external.Mirror` (design §10):
    its machine *is* the sync machine, entered only by system-actor
    transitions, and its reads pull through the adapter. The derived walker
    can neither drive those states (they are ``system_only``) nor observe
    them (the read heals them), so a Mirror gets the Mirror-aware factory."""
    from ..server.external import Mirror

    return isinstance(cls, type) and issubclass(cls, Mirror)


async def make_mirror_state(kind: str, state: str, engine: Any) -> Resource:
    """Stage a Mirror in a requested sync state — the harness default for
    Mirror kinds (design §10, 7.x conformance extension). An app need not
    hand-write one per mirror, though a registered ``@state_factory`` still
    wins (see :func:`make_state`).

    A Mirror's sync states (``fresh/stale/conflicted/unreachable``) are
    *un-drivable* by any walker principal — the transitions that enter them
    are ``system_only`` — and *un-observable* by a plain read, since
    pull-through-on-read heals a stale/unreachable mirror to fresh on the
    very GET the representation test makes. So this factory:

    1. mints the mirror (a mint carries only its external identity, design
       §8 Discover);
    2. pulls once through the adapter *directly* (not the read path, so it
       is independent of the suppression seam the suite sets for its own
       reads) and records it via ``observe_external`` — landing ``fresh``
       with the authority's real fields, so the summary orients by a name;
    3. drives to the requested state with ``SYSTEM_OBSERVER`` and the
       declared sync transitions.

    The suite then reads it back with pull-through suppressed (the
    default-off ``_suppress_mirror_refresh`` seam), so observing the mirror
    does not change it.
    """
    from ..server.external import SYSTEM_OBSERVER, SyncState

    rdef = engine.registry[kind]
    cls = rdef.cls
    target = SyncState(state)  # a Mirror's machine states ARE the sync states

    # a read-only mirror never conflicts: nothing local is ever pushed, so
    # the external etag cannot diverge under our write — and ``reconcile``
    # keeping "ours" would push against a boundary declared one-way. Don't
    # fake a state the runtime can never produce.
    if target == SyncState.CONFLICTED \
            and not getattr(cls, "push_on_write", True):
        raise SkipState(
            "a read-only mirror (push_on_write=False) never conflicts — "
            "nothing local is pushed, so the external etag cannot diverge "
            "under our write")

    create_body = await example_for(kind, "create", engine.services)
    if create_body is None:
        create_body = _schema_sample(rdef.extra["create_schema"])
        # a readable external_id so an auto-vivifying adapter yields a
        # readable name (a random schema string could be empty)
        create_body["external_id"] = f"walker-mirror-{uuid.uuid4().hex[:12]}"
    result = await engine.invoker.create(
        kind, create_body, principal=WALKER,
        idempotency_key=f"walker-{uuid.uuid4().hex}")
    rid = result.doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, kind, rid)
    external_id = instance.data.external_id

    try:
        document, etag = await cls.adapter.pull(external_id)
    except Exception as exc:  # noqa: BLE001 — the adapter's failure is the reason
        raise SkipState(
            f"mirror {kind} adapter could not pull {external_id!r} to stage a "
            f"fresh document ({exc}); register a @state_factory that seeds "
            "its adapter, or pin the adapter up for conformance") from exc
    await engine.invoker.invoke(
        kind, rid, "observe_external",
        {"document": document, "etag": etag}, principal=SYSTEM_OBSERVER)

    if target == SyncState.STALE:
        await engine.invoker.invoke(kind, rid, "mark_stale", None,
                                    principal=SYSTEM_OBSERVER)
    elif target == SyncState.UNREACHABLE:
        await engine.invoker.invoke(kind, rid, "mark_unreachable", None,
                                    principal=SYSTEM_OBSERVER)
    elif target == SyncState.CONFLICTED:
        await engine.invoker.invoke(
            kind, rid, "mark_conflicted",
            {"document": document, "etag": etag}, principal=SYSTEM_OBSERVER)
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, rid)


async def make_state(kind: str, state: str, engine: Any) -> Resource:
    if kind in _FACTORIES:
        cls, fn = _FACTORIES[kind]
        result = fn(state, engine, engine.services)
        if inspect.isawaitable(result):
            result = await result
        return result
    if _is_mirror_kind(engine.registry[kind].cls):
        return await make_mirror_state(kind, state, engine)
    return await walk_to_state(kind, state, engine)


def reset() -> None:
    _FACTORIES.clear()
    _RESOURCES.clear()
    _EXAMPLES.clear()
    _PRINCIPALS.clear()
    _PRINCIPALS.update(DEFAULT_PRINCIPALS)
    register_builtin_resources()


def register_builtin_resources() -> None:
    """Engine-provided kinds enroll with the derived walker — create
    examples supply the prose the schemas can't invent."""
    from ..server.definitions import Definition
    from ..server.grants import ApprovalRequest, Grant
    from ..server.jobs import Job

    conformance_resource(Job)
    conformance_resource(Grant)
    conformance_resource(ApprovalRequest)
    # the definition kind is wire-read-only (design §1): its writes are
    # guarded to system actors, which the walker IS — so the derived
    # walker can mint synthetic revisions and reach `superseded`, while
    # the suite's human/agent profiles prove the guard refuses them. The
    # example mints fresh (target_kind, revision) pairs: revisions are
    # declared unique, and boot has already recorded the real revision 1
    conformance_resource(Definition)

    @example_input(Definition, "create")
    def definition_create(services: Any) -> dict[str, Any]:
        return {"target_kind": "meal",
                "revision": 1000 + int(uuid.uuid4().hex[:6], 16),
                "fingerprint_hash": "0" * 64,
                "fingerprint": {"note": "a synthetic revision"},
                "change_summary": "a synthetic revision for the walker"}

    @example_input(Job, "create")
    def job_create(services: Any) -> dict[str, Any]:
        return {"action": "noop", "target_kind": "job", "total": 0}

    @example_input(Grant, "create")
    def grant_create(services: Any) -> dict[str, Any]:
        return {"holder_name": "Robo"}

    @example_input(Grant, "request_access")
    def grant_request(services: Any) -> dict[str, Any]:
        return {"task": "Exercise the conformance suite",
                "requested_actions": {"job": {"start": "open"}},
                "requested_hours": 1}

    @example_input(ApprovalRequest, "create")
    def approval_create(services: Any) -> dict[str, Any]:
        return {"grant_id": "unlinked", "agent_principal": "agent-x",
                "holder_name": "Robo", "title": "Start the demo job",
                "target_kind": "job", "target_id": "missing",
                "target_action": "start",
                "target_href": "/api/jobs/missing", "target_input": {},
                "missing": []}


register_builtin_resources()
