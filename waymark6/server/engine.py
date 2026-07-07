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
        surfaces: Sequence[type] = (),
        storage: PostgresStorage | str | Any,
        principal: Callable[[Request], Any] = header_principal,
        services: Any = None,
        base_path: str = "/api",
        clock: Callable[[], datetime] | None = None,
        bus: Any = None,
        presence: bool = True,
        agent_links: bool = True,
        members: bool = True,
        webhooks: bool = True,
        member_visibility: str = "full",
        attachments: bool = True,
        blobs: Any = None,
        blob_retention: str = "purge",
        derived_tick_interval: float = 30.0,
    ):
        if member_visibility not in ("full", "granted"):
            raise ValueError("member_visibility must be 'full' or 'granted'")
        if blob_retention not in ("purge", "keep"):
            raise ValueError("blob_retention must be 'purge' or 'keep'")
        self.blob_retention = blob_retention
        # "granted" (design §9): human principals see what they own plus
        # what their member/role-held grants say — advertisement,
        # enforcement, and the collection SQL all read the same object.
        # "full" keeps v2's trust model (every human sees everything).
        self.member_visibility = member_visibility
        from .jobs import Job

        self.registry = Registry()
        for cls in resources:
            self.registry.register(cls)
        if "job" not in self.registry:  # deferred bulk lands on job resources
            self.registry.register(Job, engine_owned=True)
        # the law is a resource (design §1): always registered, like job —
        # an engine without a deploy history would be an engine whose
        # meaning changes silently, which 5.0 exists to refuse
        from .definitions import Definition

        if "definition" not in self.registry:
            self.registry.register(Definition, engine_owned=True)
        # kind → current definition revision row id, filled by the boot
        # revise (design §2); engine.current_law() reads it — the seam §3's
        # defined_by anchoring writes through
        self._law: dict[str, str] = {}
        # kinds whose stale-by-definition facts a declared Deferred pushed
        # past boot (design §4): drained by the background backfill task
        self._deferred_backfills: list[tuple[str, Any]] = []
        self._backfill_task: Any = None
        self.agent_links = agent_links
        if agent_links:
            # least-privilege agent access, negotiated as ordinary resources
            from .grants import ApprovalRequest, Grant

            if "grant" not in self.registry:
                self.registry.register(Grant, engine_owned=True)
            if "approval_request" not in self.registry:
                self.registry.register(ApprovalRequest, engine_owned=True)
        if members:
            # identity is a resource (design §9): invite → first-login bind
            # → active, all ordinary audited transitions
            from .members import Member
            from .roles import Role

            if "member" not in self.registry:
                self.registry.register(Member, engine_owned=True)
            # the role registry rides the same flag: roles are the other
            # half of §9's identity surface, and grants' role_registered
            # guard reads it
            if "role" not in self.registry:
                self.registry.register(Role, engine_owned=True)
        if webhooks:
            # the outbox is a product (design §10): subscriptions deliver
            # signed transition JSON to third parties
            from .subscriptions import WebhookSubscription

            if "subscription" not in self.registry:
                self.registry.register(WebhookSubscription, engine_owned=True)
        if attachments:
            # bytes behind the envelope (design E5): metadata is a resource,
            # the blob store is declared. The memory default is the dev
            # resolver precedent — production wires a real store.
            from .attachments import Attachment, MemoryBlobStore

            if "attachment" not in self.registry:
                self.registry.register(Attachment, engine_owned=True)
            self.blobs = blobs if blobs is not None else MemoryBlobStore()
        else:
            self.blobs = blobs
        # cross-resource reference checks need every kind known (design §2)
        from ..core import checks
        checks.check_refs(self.registry)
        checks.check_owns(self.registry)  # ownership edges too (design E4)
        checks.check_touches(self.registry)  # declared touches (design E8)
        checks.check_derived_edges(self.registry)  # derivation inputs (§2)
        # relation predicates (design 6.0 §1): every cited Related edge is
        # indexable on both sides, or the declaration is refused here —
        # invertibility is §2's guarantee, so there is no runtime tier
        checks.check_related(self.registry)
        # cross-kind fact cycles (the inputs-and-identities wave): facts
        # flow parent→child and child→parent now, so two kinds could each
        # derive over the other — the chained recompute terminates only
        # because this graph is a DAG, so a cycle is refused here, named
        checks.check_derived_cycles(self.registry)
        # edge-cited links compile their hrefs from the checked predicate
        # (design 6.0 §1): the target collection filtered by the §3 grammar
        from ..core.related import compile_edge_links

        compile_edge_links(self.registry)
        # compound acts (design §6): seeds, fan-out filters, advanced-input
        # bodies, and effect service ops validate against the assembled
        # registry and this engine's declared services
        checks.check_compounds(self.registry, services)
        # per-field authority + discovery (design §8): authored kinds carry
        # the sync bookkeeping; Discover sweeps query a promoted column
        from .external import check_authored_assembly

        check_authored_assembly(self.registry)
        # the declared backfill deferral (design §4) is validated where the
        # other declarations are — at assembly, not at first stale deploy
        from ..core.derived import Deferred
        from ..core.types import DefinitionError

        for rdef in self.registry.defs():
            declared = rdef.cls.backfill
            if declared is not None and not isinstance(declared, Deferred):
                raise DefinitionError(
                    f"{rdef.kind}: backfill must be a Deferred(...) "
                    "declaration (design §4) or absent")
        # decision surfaces (design 6.0 §4): declared compositions over
        # the assembled registry — validated here, with the other
        # cross-kind checks, and never registered as kinds (no storage,
        # no machine, no rows: every per-kind loop that assumes a table
        # excludes them by construction). Each anchor rdef learns its
        # surfaces so render_links can advertise the named views.
        from ..core.surface import assemble_surfaces

        self.surfaces = assemble_surfaces(self.registry, surfaces)
        for sdef in self.surfaces.values():
            sdef.anchor.extra.setdefault("surfaces", []).append(sdef)
        self.storage = (storage if isinstance(storage, PostgresStorage)
                        else PostgresStorage(storage, self.registry))
        self.services = services
        self.principal = principal
        self.base_path = base_path
        dsn = self.storage.engine.url.render_as_string(hide_password=False)
        self.bus = bus if bus is not None else make_bus(dsn)
        self.draft_store = DraftStore(self.storage, self.bus)
        # distributed rate-limit windows ride the same bus (design §8) —
        # constructed here so its channel registers before bus.start()
        from .bus import RateCoordinator

        self.rate = RateCoordinator(self.bus)
        self.invoker = Invoker(registry=self.registry, storage=self.storage,
                               services=services, base=base_path, clock=clock,
                               draft_store=self.draft_store, rate=self.rate,
                               bus=self.bus)
        # the clock consumer's cadence (design §3): how often the sweep of
        # `next_flip_at <= now` runs; engine.tick(now=...) drives it by hand
        self.derived_tick_interval = derived_tick_interval
        self._tick_task: Any = None
        self.dispatcher: Any = None  # events dispatcher, attached at startup
        # observation-class events (design §3): never stored, at-most-once;
        # presence=False silences them — reads then leave no live trace
        self.presence = presence
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
        if self.member_visibility == "granted" and result is not None \
                and result.type == "human" and result.scope is None:
            # a member's effective view: full over owned, grant union
            # otherwise — computed here, consumed by projection,
            # enforcement, and the collection pushdown alike
            from dataclasses import replace

            from .grants import member_visibility

            vis = await member_visibility(self.storage, result,
                                          self.invoker.clock())
            result = replace(result, visibility=vis)
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
                s, "grant", filters={"token": token}, sort=None,
                page_size=1, page_number=1)
        if not grants:
            return Principal(id="agent-link-unknown", type="agent",
                             display="Unknown agent link", scope=dead_grant())
        grant = grants[0]  # revoked stays scoped: revoked means nothing, everywhere
        vis = None
        approved_by = getattr(grant.data, "approved_by", None)
        if self.member_visibility == "granted" and approved_by \
                and approved_by != "anonymous":
            # delegation is attenuation (design §9): the approver's CURRENT
            # effective view is the ceiling — revoking the approver's own
            # access shrinks the delegate's in the same render
            from .grants import GrantVisibility, member_visibility

            approver = Principal(id=approved_by, type="human")
            ceiling = await member_visibility(self.storage, approver,
                                              self.invoker.clock())
            vis = GrantVisibility(grant, self.invoker.clock(),
                                  ceiling=ceiling)
        return Principal(id=f"agent-link-{grant.id[:8]}", type="agent",
                         display=grant.data.holder_name, scope=grant,
                         visibility=vis)

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
            if audience == audience_of(defn, principal).token:
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
        rollups = None
        from .owns import compute_rollups, has_rollups

        if has_rollups(rdef):
            computed = await compute_rollups(self.storage, s, rdef,
                                             [instance.id])
            rollups = computed.get(instance.id)
        return await render(instance, rdef, ctx=ctx, depth=depth,
                            base=self.base_path, embeds=embeds, drafts=drafts,
                            resolved=True, rollups=rollups)

    async def startup(self) -> None:
        await self.storage.create_all()
        await self.storage.check_state_tokens()
        # deploys are transitions (design §2): fingerprint the registry,
        # revise what changed, write nothing for a plain restart. This
        # runs before every other startup consumer so definition rows
        # exist before anything anchors to them — the sweep's cancels,
        # the cascades, and every write's defined_by (§3)
        from .definitions import revise_definitions

        self._law, stale = await revise_definitions(self)
        # stale-by-definition truth is unbootable (design §4): the revise
        # named the facts whose semantic surface changed — unioned with
        # any durable backfill_pending marker a crashed prior boot left
        # on the current revision row — and this runs HERE: after the
        # revise, before the dispatcher, consumers, and clock task start,
        # so nothing downstream can deliver or sweep a materialized value
        # the current law disagrees with. A declared Deferred trades the
        # held door for honest marking: the facts are stamped recomputing
        # (un-advertised from the query surface, meta.recomputing on
        # every envelope) and a background task drains them after
        # startup. Either way the marker is settled — an ordinary
        # transition on the definition row — only after the recompute
        # commits, so a crash anywhere in the window re-detects the debt
        # on the next boot and re-runs the backfill idempotently.
        from ..core.derived import Deferred
        from .definitions import settle_backfill

        self._deferred_backfills = []
        for kind in sorted(stale):
            rdef = self.registry[kind]
            declared = rdef.cls.backfill
            if isinstance(declared, Deferred):
                rdef.recomputing = stale[kind]
                self._deferred_backfills.append((kind, declared))
            else:
                await self.invoker.derived.backfill(kind)
                await settle_backfill(self.invoker, self._law[kind])
        from .events import Dispatcher

        # construct (registers its bus channel) before bus.start() LISTENs
        self.dispatcher = Dispatcher(self.storage, self.registry,
                                     base=self.base_path, bus=self.bus)
        await self.bus.start()
        await self.dispatcher.start()
        # affordance-changing transitions re-run the collab join gate
        # (design §2): lapsed principals lose their sockets now
        self.collab.start_regate(self.dispatcher)
        if "subscription" in self.registry:
            from .subscriptions import WebhookDeliverer

            self.webhooks = WebhookDeliverer(self)
            self.webhooks.start(self.dispatcher)
        # declared cascades ride the log (design E4); a no-edge registry
        # starts nothing
        from .owns import CascadeRunner

        self.cascades = CascadeRunner(self)
        self.cascades.start(self.dispatcher)
        # purging retention rides the log (design E5): one consumer covers
        # every invoke path, after commit, durably
        self.blob_janitor = None
        if "attachment" in self.registry and self.blob_retention == "purge":
            from .attachments import BlobJanitor

            self.blob_janitor = BlobJanitor(self)
            self.blob_janitor.start(self.dispatcher)
        # duplication's byte half rides the log too (design E5/E8): runs
        # whenever attachments are on, whatever the retention
        self.blob_copier = None
        if "attachment" in self.registry:
            from .attachments import BlobCopier

            self.blob_copier = BlobCopier(self)
            self.blob_copier.start(self.dispatcher)
        # a queued/running job at boot has no live task — its worker died;
        # cancel it honestly (design E6; jobs are per-process this wave)
        from .jobs import sweep_orphan_jobs

        await sweep_orphan_jobs(self)
        # the clock is a publisher (design §3): a background sweep flips
        # Clock-input derivations whose time has come — started only when
        # a registered kind declares one. Declared discovery sweeps
        # (design §8) ride the same task: the clock consumer is THE
        # scheduled evaluator, never a parallel one
        from .external import has_discovery

        if self.invoker.derived.clocked_kinds or has_discovery(self.registry):
            import asyncio

            self._tick_task = asyncio.create_task(
                self._derived_ticks(), name="waymark6-clock")
        # the declared deferrals catch up in the background (design §4),
        # on the same task discipline as the clock sweep: started last,
        # cancelled at shutdown. Until a kind drains, its stale facts stay
        # marked — honestly un-filterable rather than quietly mixed-law —
        # and a crashed drain leaves the durable backfill_pending marker
        # unsettled, so the next boot restores the mark and resumes.
        if self._deferred_backfills:
            import asyncio

            self._backfill_task = asyncio.create_task(
                self._drain_backfills(), name="waymark6-backfill")

    def current_law(self, kind: str) -> str | None:
        """The definition revision id currently governing ``kind`` (the
        ``__registry__`` token names the deploy as a whole). Cached at
        boot by the §2 revise — a dict read, cheap enough to sit on every
        write path (§3's ``defined_by`` anchor reads it there). None
        before startup or for an unregistered kind."""
        return self._law.get(kind)

    async def tick(self, now: Any = None) -> int:
        """Run one clock sweep (design §3) — tests drive time by hand;
        production rides the background task. Declared Discover sweeps
        (design §8) run on the same tick, each at its own declared
        interval. Returns the derivation flip count."""
        now = now or self.invoker.clock()
        flips = await self.invoker.derived.tick(now)
        from .external import run_discovery

        await run_discovery(self, now)
        return flips

    async def _drain_backfills(self) -> None:
        """The Deferred catch-up (design §4): recompute each marked kind
        in its declared batches, settle its durable marker, then unmark
        it — the facts return to the query surface and
        ``meta.recomputing`` disappears, kind by kind. A failure leaves
        the kind marked (stale truth stays un-served) and its
        ``backfill_pending`` marker in place; the next boot re-detects
        the debt from the marker — a fresh revise is not required — and
        resumes the drain."""
        import logging

        from .definitions import settle_backfill

        for kind, declared in self._deferred_backfills:
            try:
                await self.invoker.derived.backfill(
                    kind, batch=declared.batch, pause=declared.pause)
                await settle_backfill(self.invoker, self._law[kind])
            except Exception:  # noqa: BLE001 — marked is honest; log and go on
                logging.getLogger("waymark6.derived").exception(
                    "deferred backfill of %s failed; its facts stay "
                    "marked recomputing", kind)
                continue
            self.registry[kind].recomputing = ()

    async def _derived_ticks(self) -> None:
        import asyncio
        import logging

        while True:
            await asyncio.sleep(self.derived_tick_interval)
            try:
                await self.tick()
            except Exception:  # noqa: BLE001 — the sweep must survive
                logging.getLogger("waymark6.derived").exception(
                    "clock sweep failed; will retry next tick")

    async def shutdown(self) -> None:
        import asyncio
        import contextlib

        if self._backfill_task is not None:
            self._backfill_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._backfill_task
            self._backfill_task = None
        if self._tick_task is not None:
            self._tick_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._tick_task
            self._tick_task = None
        if getattr(self, "blob_copier", None) is not None:
            await self.blob_copier.stop()
        if getattr(self, "blob_janitor", None) is not None:
            await self.blob_janitor.stop()
        if getattr(self, "cascades", None) is not None:
            await self.cascades.stop()
        if getattr(self, "webhooks", None) is not None:
            await self.webhooks.stop()
            self.webhooks = None
        await self.collab.stop_regate()
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

    def landing(self) -> Any:
        """A router serving the generic client at the site root.

        ``include_router(engine.landing())`` puts the human entry point at
        ``/`` while the API keeps its prefix — the page is the same file
        ``{base}/-/ui`` serves, with the API base injected so the client
        stops deriving it from its own URL. Mount it or don't; the
        ``/-/ui`` route keeps working either way.
        """
        import json as _json
        from pathlib import Path

        from fastapi import APIRouter
        from fastapi.responses import Response

        router = APIRouter()
        base = self.base_path

        @router.get("/", include_in_schema=False)
        async def landing() -> Response:
            html = (Path(__file__).parent / "static" / "ui.html").read_text()
            inject = ("<script>window.WAYMARK_BASE = "
                      f"{_json.dumps(base)};</script>")
            html = html.replace("<head>", "<head>" + inject, 1)
            return Response(content=html, media_type="text/html")

        return router
