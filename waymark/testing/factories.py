"""What applications supply to the conformance suite (Part III):

- one ``@state_factory(Resource)`` per resource — returns a persisted
  instance in the requested state, by any means honest;
- ``@example_input(Resource, action)`` where schema-driven generation cannot
  satisfy semantic guards;
- optionally ``set_principals({...})`` to override the default profiles.
"""
from __future__ import annotations

import inspect
from typing import Any, Awaitable, Callable

from ..core.resource import Resource
from ..core.types import Principal

StateFactory = Callable[..., Awaitable[Resource]]
ExampleFn = Callable[..., Any]

_FACTORIES: dict[str, tuple[type[Resource], StateFactory]] = {}
_EXAMPLES: dict[tuple[str, str], ExampleFn] = {}

DEFAULT_PRINCIPALS: dict[str, Principal] = {
    "anonymous": Principal.anonymous(),
    "owner": Principal(id="owner", type="human", display="Owner"),
    "manager": Principal(id="manager", type="human",
                         roles=frozenset(["manager"]), display="Manager"),
    "agent": Principal(id="agent-1", type="agent", display="Agent"),
}

_PRINCIPALS: dict[str, Principal] = dict(DEFAULT_PRINCIPALS)


class SkipState(Exception):
    """Raise from a state factory when a state cannot be honestly produced."""


def state_factory(resource: type[Resource]) -> Callable[[StateFactory], StateFactory]:
    def decorate(fn: StateFactory) -> StateFactory:
        _FACTORIES[resource.kind] = (resource, fn)
        return fn
    return decorate


def example_input(resource: type[Resource], action: str) -> Callable[[ExampleFn], ExampleFn]:
    if action not in resource.__waymark_machine__.actions:
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


def factories() -> dict[str, tuple[type[Resource], StateFactory]]:
    return dict(_FACTORIES)


async def make_state(kind: str, state: str, engine: Any) -> Resource:
    cls, fn = _FACTORIES[kind]
    result = fn(state, engine, engine.services)
    if inspect.isawaitable(result):
        result = await result
    return result


async def example_for(kind: str, action: str, services: Any) -> dict[str, Any] | None:
    fn = _EXAMPLES.get((kind, action))
    if fn is None:
        return None
    result = fn(services)
    if inspect.isawaitable(result):
        result = await result
    return result


def reset() -> None:
    _FACTORIES.clear()
    _EXAMPLES.clear()
    _PRINCIPALS.clear()
    _PRINCIPALS.update(DEFAULT_PRINCIPALS)
    register_builtin_factories()


def register_builtin_factories() -> None:
    """The engine provides the ``job`` resource (§7.4), so the suite provides
    its factory — applications only supply factories for their own resources."""
    import uuid

    from ..server.jobs import SYSTEM, Job, JobData

    @state_factory(Job)
    async def make_job(state: str, engine: Any, services: Any) -> Resource:
        now = engine.invoker.clock()
        job = Job(id=uuid.uuid4().hex, state="queued",
                  data=JobData(action="noop", target_kind="job", total=0),
                  version=1, created_at=now, updated_at=now)
        async with engine.storage.session() as s:
            await engine.storage.insert(s, "job", job)
            await engine.storage.append_transition(
                s, kind="job", instance=job, action="create", from_state="",
                principal=SYSTEM, input_digest="", at=now,
                summary="Job · noop · 0/0 processed · Queued")
        for step in {"queued": [], "running": ["start"],
                     "done": ["start", "finish"], "cancelled": ["cancel"]}[state]:
            await engine.invoker.invoke("job", job.id, step, None,
                                        principal=SYSTEM)
        async with engine.storage.session() as s:
            return await engine.storage.load(s, "job", job.id)


register_builtin_factories()
