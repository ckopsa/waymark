"""Engine wiring: resources + storage + principal + services → router.

2.0 changes: one :class:`Bus` (design §7) feeds SSE wake-ups and collab
relay; draft rows load in a single query and filter by each action's
*declared* audience (no ``"*"`` special-casing threaded through call sites,
design §4); the principal resolver is one interface fed by an opaque
credential per transport (design §11).
"""
from __future__ import annotations

import inspect
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, Callable, Sequence

from fastapi import Request

from ..core.registry import Registry
from ..core.resource import Resource
from ..core.types import Ctx, Principal
from .bus import make_bus
from .drafts import DraftStore, audience_of
from .invoke import Invoker
from .render import render
from .router import build_router
from .storage.postgres import PostgresStorage


def header_principal(request: Any) -> Principal:
    """Default dev resolver: one credential-extraction rule per transport —
    ``X-Principal-*`` headers (HTTP) or ``principal-*`` query params
    (WebSocket upgrades, where browsers cannot set headers). Replace with a
    real auth dependency in production; the interface is the same for both
    transports by construction."""

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
        bus: Any = None,
        presence: bool = True,
        agent_links: bool = True,
    ):
        from .jobs import Job

        self.registry = Registry()
        for cls in resources:
            self.registry.register(cls)
        if "job" not in self.registry:  # deferred bulk lands on job resources
            self.registry.register(Job)
        self.agent_links = agent_links
        if agent_links:
            # least-privilege agent access, negotiated as ordinary resources
            from .grants import AgentGrant, ApprovalRequest

            if "agent_grant" not in self.registry:
                self.registry.register(AgentGrant)
            if "approval_request" not in self.registry:
                self.registry.register(ApprovalRequest)
        # cross-resource reference checks need every kind known (design §2)
        from ..core import checks
        checks.check_refs(self.registry)
        self.storage = (storage if isinstance(storage, PostgresStorage)
                        else PostgresStorage(storage, self.registry))
        self.services = services
        self.principal = principal
        self.base_path = base_path
        dsn = self.storage.engine.url.render_as_string(hide_password=False)
        self.bus = bus if bus is not None else make_bus(dsn)
        self.draft_store = DraftStore(self.storage, self.bus)
        self.invoker = Invoker(registry=self.registry, storage=self.storage,
                               services=services, base=base_path, clock=clock,
                               draft_store=self.draft_store)
        self.dispatcher: Any = None  # events dispatcher, attached at startup
        # ephemeral who-is-looking-at-what (never stored; presence=False to
        # run without it — reads then leave no live trace at all)
        from .events import PresenceHub

        self.presence = PresenceHub(self.bus) if presence else None
        from .collab import CollabRooms

        self.collab = CollabRooms(self)  # live rooms for collab drafts
        self.router = build_router(self)

    async def resolve_principal(self, request: Request) -> Principal:
        token = self._bearer_token(request)
        if token is not None and self.agent_links:
            # a presented token IS the credential — it never falls through
            # to the app resolver, dead or alive
            return await self._token_principal(token)
        result = self.principal(request)
        if inspect.isawaitable(result):
            result = await result
        return result

    @staticmethod
    def _bearer_token(request: Any) -> str | None:
        from .grants import TOKEN_PREFIX

        auth = request.headers.get("Authorization") or ""
        if auth.startswith("Bearer " + TOKEN_PREFIX):
            return auth.removeprefix("Bearer ").strip()
        qp = request.query_params.get("agent-token")
        if qp and qp.startswith(TOKEN_PREFIX):  # WS upgrades can't set headers
            return qp
        return None

    async def _token_principal(self, token: str) -> Principal:
        """An agent-link token resolves to a principal carrying its grant;
        every enforcement decision reads the grant. Unknown tokens carry a
        dead grant — scoped to nothing, never anonymous."""
        from .grants import dead_grant

        async with self.storage.session() as s:
            grants, _ = await self.storage.query(
                s, "agent_grant", filters={"token": token}, sort=None,
                page_size=1, page_number=1)
        if not grants:
            return Principal(id="agent-link-unknown", type="agent",
                             display="Unknown agent link", scope=dead_grant())
        grant = grants[0]  # revoked stays scoped: revoked means nothing, everywhere
        return Principal(id=f"agent-link-{grant.id[:8]}", type="agent",
                         display=grant.data.agent_name, scope=grant)

    async def load_drafts_for(self, s: Any, rdef: Any, instance: Resource,
                              principal: Principal) -> dict[tuple[str, str], dict]:
        """One query; then each action sees exactly the audience its
        DraftPolicy declared. Returns ``(action, part_key) → row``."""
        if not any(d.draft for d in rdef.machine.actions.values()):
            return {}
        rows = await self.storage.load_drafts(s, rdef.kind, instance.id)
        out: dict[tuple[str, str], dict] = {}
        for (action, part_key, audience), row in rows.items():
            defn = rdef.machine.actions.get(action)
            if defn is None or not defn.draft:
                continue
            if audience == audience_of(defn, principal):
                out[(action, part_key)] = row
        return out

    async def render_with_depth(self, s: Any, instance: Resource, rdef: Any, *,
                                ctx: Ctx, depth: str) -> dict[str, Any]:
        """The resolve path (design §6): every declared read participates,
        failures are loud. Depth negotiation resolves the named embedding
        profile; embeds load batched — one query per embedded relation."""
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
        drafts = await self.load_drafts_for(s, rdef, instance, ctx.principal)
        return await render(instance, rdef, ctx=ctx, depth=depth,
                            base=self.base_path, embeds=embeds, drafts=drafts,
                            resolved=True)

    async def startup(self) -> None:
        await self.storage.create_all()
        await self.storage.check_state_tokens()
        from .events import Dispatcher

        # construct (registers its bus channel) before bus.start() LISTENs
        self.dispatcher = Dispatcher(self.storage, self.registry,
                                     base=self.base_path, bus=self.bus)
        await self.bus.start()
        await self.dispatcher.start()

    async def shutdown(self) -> None:
        if self.dispatcher is not None:
            await self.dispatcher.stop()
        await self.bus.stop()
        await self.storage.engine.dispose()

    @asynccontextmanager
    async def lifespan(self, app: Any = None):
        await self.startup()
        try:
            yield
        finally:
            await self.shutdown()
