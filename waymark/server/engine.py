"""Engine wiring (§13): resources + storage + principal + services → router."""
from __future__ import annotations

import inspect
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, Callable, Sequence

from fastapi import Request

from ..core.registry import Registry
from ..core.resource import Resource
from ..core.types import Ctx, Principal
from .invoke import Invoker
from .render import render
from .router import build_router
from .storage.postgres import PostgresStorage


def header_principal(request: Request) -> Principal:
    """Default dev principal: trusts X-Principal-* headers, falling back to
    principal-* query params (browsers cannot set headers on a WebSocket
    upgrade, so the collab seam needs the query form). Replace with a real
    auth dependency in production (§15: authentication is upstream)."""

    def get(header: str, param: str) -> str | None:
        return request.headers.get(header) or request.query_params.get(param)

    pid = get("X-Principal-Id", "principal-id")
    if not pid:
        return Principal.anonymous()
    ptype = get("X-Principal-Type", "principal-type") or "human"
    roles = frozenset(r.strip() for r in
                      (get("X-Principal-Roles", "principal-roles") or "")
                      .split(",") if r.strip())
    return Principal(id=pid, type=ptype,  # type: ignore[arg-type]
                     roles=roles,
                     display=get("X-Principal-Display",
                                 "principal-display") or pid)


class Engine:
    def __init__(
        self,
        *,
        resources: Sequence[type[Resource]],
        storage: PostgresStorage | str | Any,
        principal: Callable[[Request], Any] = header_principal,
        services: Any = None,
        base_path: str = "/api",
        clock: Callable[[], datetime] | None = None,
    ):
        from .jobs import Job

        self.registry = Registry()
        for cls in resources:
            self.registry.register(cls)
        if "job" not in self.registry:  # deferred bulk lands on job resources
            self.registry.register(Job)
        # cross-resource usability checks need every kind known (§10.1)
        from ..core import checks
        checks.check_opaque_refs(self.registry)
        self.storage = (storage if isinstance(storage, PostgresStorage)
                        else PostgresStorage(storage, self.registry))
        self.services = services
        self.principal = principal
        self.base_path = base_path
        self.invoker = Invoker(registry=self.registry, storage=self.storage,
                               services=services, base=base_path, clock=clock)
        self.dispatcher: Any = None  # events dispatcher, attached at startup
        from .collab import CollabRooms

        self.collab = CollabRooms(self)  # live rooms for collab drafts
        self.router = build_router(self)

    async def resolve_principal(self, request: Request) -> Principal:
        result = self.principal(request)
        if inspect.isawaitable(result):
            result = await result
        return result

    async def render_with_depth(self, s: Any, instance: Resource, rdef: Any, *,
                                ctx: Ctx, depth: str) -> dict[str, Any]:
        """Depth negotiation (§4.1): resolve the named embedding profile.

        Embeds load batched — one query per embedded relation across the
        document (``load_many``), never per link."""
        embeds: dict[str, Any] | None = None
        if depth.startswith("expanded:"):
            prof = rdef.cls.profiles.get(depth.partition(":")[2])
            if prof is not None:
                embeds = {}
                for rel, target_depth in prof.embed.items():
                    ld = next((l for l in rdef.cls.links if l.rel == rel), None)
                    target_rdef = self.registry.get(ld.kind) if ld else None
                    if ld is None or target_rdef is None:
                        continue
                    from ..core.summary import _SummaryFormatter

                    href = _SummaryFormatter(instance).vformat(ld.href, (), {})
                    target_id = href.rstrip("/").rsplit("/", 1)[-1]
                    targets = await self.storage.load_many(
                        s, ld.kind, [target_id])
                    if targets:
                        embeds[rel] = await render(
                            targets[0], target_rdef, ctx=ctx,
                            depth=target_depth, base=self.base_path)
        drafts = None
        if any(d.draft for d in rdef.machine.actions.values()):
            drafts = await self.storage.load_drafts(
                s, rdef.kind, instance.id, ctx.principal.id)
            if any(d.collab for d in rdef.machine.actions.values()):
                # collab drafts are shared: stored under the "*" sentinel and
                # rendered for every principal — collaborators are looking at
                # the same half-written effort
                from .collab import SHARED

                shared = await self.storage.load_drafts(
                    s, rdef.kind, instance.id, SHARED)
                for name, d in rdef.machine.actions.items():
                    if d.collab:
                        drafts.pop(name, None)
                        if name in shared:
                            drafts[name] = shared[name]
        return await render(instance, rdef, ctx=ctx, depth=depth,
                            base=self.base_path, embeds=embeds, drafts=drafts)

    async def startup(self) -> None:
        await self.storage.create_all()
        await self.storage.check_state_tokens()
        from .events import Dispatcher

        self.dispatcher = Dispatcher(self.storage, self.registry,
                                     base=self.base_path)
        await self.dispatcher.start()

    async def shutdown(self) -> None:
        if self.dispatcher is not None:
            await self.dispatcher.stop()
        await self.storage.engine.dispose()

    @asynccontextmanager
    async def lifespan(self, app: Any = None):
        await self.startup()
        try:
            yield
        finally:
            await self.shutdown()
