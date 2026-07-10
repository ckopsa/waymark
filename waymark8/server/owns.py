"""Ownership's engine half (design E4): the cascade runner and rollup
computation. Both consume the one ``Owns`` declaration — the runner
drives cascades off the transition log, ``compute_rollups`` feeds the
envelope's ``rollups`` key.

Delivery discipline mirrors the webhook deliverer: the dispatcher is
only a wake signal; the feed is ``transitions_since`` behind a durable
cursor, so an outage drains instead of dropping. Redelivery is a
natural no-op — children are selected by the target action's ``from_``
states, so an already-cascaded child simply doesn't match — and each
child transition rides the parent's correlation id: one story in the
follower narrative.
"""
from __future__ import annotations

import logging
from dataclasses import replace
from typing import Any

from ..core.owns import owns_of
from ..core.types import Principal
from .consumers import LogConsumer

log = logging.getLogger("waymark8.owns")

CASCADE_ACTOR = Principal(id="waymark-cascade", type="system",
                          display="Cascade")


async def compute_rollups(storage: Any, s: Any, rdef: Any,
                          ids: list[str]) -> dict[str, dict[str, Any]]:
    """{parent_id: {rollup_name: aggregate}} — one GROUP BY per declared
    rollup, whatever the page size."""
    out: dict[str, dict[str, Any]] = {i: {} for i in ids}
    for edge in owns_of(rdef.cls):
        for name, rollup in edge.rollups.items():
            counts = await storage.rollup_counts(
                s, edge.kind, edge.via, ids, dict(rollup.filters),
                agg=rollup.agg, of=rollup.of)
            for i in ids:
                out[i][name] = counts.get(i, 0)
    return out


def has_rollups(rdef: Any) -> bool:
    return any(edge.rollups for edge in owns_of(rdef.cls))


class CascadeRunner(LogConsumer):
    """Parent transitions named in an ``Owns.on`` map fan out to owned
    children as system-actor transitions (design E4)."""

    consumer = "cascade"

    def __init__(self, engine: Any):
        super().__init__(engine)
        # (parent_kind, action) → [(child_kind, via, child_action)]
        self.index: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
        for rdef in engine.registry.defs():
            for edge in owns_of(rdef.cls):
                for parent_action, child_action in edge.on.items():
                    self.index.setdefault((rdef.kind, parent_action), []) \
                        .append((edge.kind, edge.via, child_action))
        self.kinds = frozenset(k for k, _ in self.index)

    def start(self, dispatcher: Any) -> None:
        if not self.index:
            return
        super().start(dispatcher)

    async def handle(self, t: Any) -> None:
        for child_kind, via, child_action in self.index.get(
                (t.kind, t.action), ()):
            await self._cascade(t, child_kind, via, child_action)

    async def _cascade(self, t: Any, child_kind: str, via: str,
                       child_action: str) -> None:
        engine = self.engine
        child_defn = engine.registry[child_kind].machine.actions[child_action]
        eligible = sorted(child_defn.from_)
        actor = replace(CASCADE_ACTOR,
                        display=f"Cascade — {t.kind}.{t.action}")
        while True:
            async with engine.storage.session() as s:
                # each sweep re-queries page 1: cascaded children leave the
                # state filter, so the loop terminates when none match
                children, _ = await engine.storage.query(
                    s, child_kind,
                    filters={via: t.resource_id, "state": eligible},
                    sort=None, page_size=200, page_number=1)
            if not children:
                return
            for child in children:
                await engine.invoker.invoke(
                    child_kind, child.id, child_action, None,
                    principal=actor, correlation_id=t.correlation_id)
