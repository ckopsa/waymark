"""The world outside the envelope (design §10).

Waymark cannot make third parties speak the format; it can be honest
about the boundary in both directions:

- :class:`Service` — declared egress. A guard that declares
  ``reads=("services.x",)`` stops being a naming convention: the service
  carries its timeout/backoff policy, and while it is down every action
  that declared it (via :func:`service_up`) renders ``unavailable`` with
  ``retry_at``. Most frameworks 500 at invoke; this one un-advertises the
  button.
- :class:`Mirror` — the anti-corruption layer. A resource whose truth
  lives elsewhere, full citizen inside the boundary: envelope, guards,
  single-invoker writes, visibility. Its machine carries the sync states
  (``fresh / stale / conflicted / unreachable``); staleness renders
  (``synced_at`` in data); ``conflicted`` is a state with a ``reconcile``
  action, not a silent last-writer-wins; and external mutations we
  observe arrive as ``observe_external`` transitions by a ``system``
  actor — the audit trail, SSE, and follow all carry changes we didn't
  make.

(The outbox-as-product half — webhook subscriptions — lives in
``subscriptions.py``.)
"""
from __future__ import annotations

from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any, ClassVar, Protocol

from pydantic import BaseModel, Field

from ..core.actions import action
from ..core.guards import Guard
from ..core.resource import Resource
from ..core.types import Acknowledged, Allow, Ctx, Deny, Principal, Safety


class ServiceDown(RuntimeError):
    def __init__(self, name: str, retry_at: datetime,
                 cause: str | None = None):
        super().__init__(f"service {name!r} is down; retry at {retry_at}")
        self.name = name
        self.retry_at = retry_at
        # what the adapter actually raised — job artifacts record it
        # (design E6); the unavailable rendering still says only retry_at
        self.cause = cause


class ServiceCallError(RuntimeError):
    """One call failed against a service declared ``down_on_error=False``:
    the failure belongs to the call, not the service — no outage, no
    backoff, the service stays up (design E6: intake's independent
    sub-imports, where one malformed export is not a Beacon outage)."""

    def __init__(self, name: str, cause: str):
        super().__init__(f"service {name!r} call failed: {cause}")
        self.name = name
        self.cause = cause


class Service:
    """A declared external dependency: name, call policy, honest failure.

    ``call()`` wraps the adapter callable with the declared timeout and
    backoff: a failure marks the service down until ``now + backoff``,
    and every action guarded by :func:`service_up` renders
    ``unavailable`` with that ``retry_at`` until then. Conformance gets a
    stub for free — swap ``handler``; it is the only seam.

    ``down_on_error=False`` declares failures independent (design E6):
    a failed call raises :class:`ServiceCallError` with the adapter's own
    words and the service stays up — for intake-shaped imports where one
    malformed artifact is not an outage.
    """

    def __init__(self, name: str, handler: Any = None, *,
                 timeout: float = 10.0, backoff_seconds: float = 60.0,
                 down_on_error: bool = True):
        self.name = name
        self.handler = handler
        self.timeout = timeout
        self.backoff_seconds = backoff_seconds
        self.down_on_error = down_on_error
        self.down_until: datetime | None = None

    def up(self, now: datetime) -> bool:
        return self.down_until is None or now >= self.down_until

    async def call(self, *args: Any, now: datetime, **kwargs: Any) -> Any:
        import asyncio

        if not self.up(now):
            raise ServiceDown(self.name, self.down_until)
        try:
            out = self.handler(*args, **kwargs)
            if asyncio.iscoroutine(out):
                out = await asyncio.wait_for(out, timeout=self.timeout)
            self.down_until = None
            return out
        except ServiceDown:
            raise
        except Exception as exc:
            cause = f"{type(exc).__name__}: {exc}"[:240]
            if not self.down_on_error:
                # the failure is the call's, not the service's (design E6)
                raise ServiceCallError(self.name, cause) from None
            self.down_until = now + timedelta(seconds=self.backoff_seconds)
            raise ServiceDown(self.name, self.down_until,
                              cause=cause) from None


def service_up(service: Service, *, explain: str | None = None) -> Guard:
    """The declared-dependency guard: while the service is down, actions
    that need it are honestly unavailable with ``retry_at``."""
    async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
        if service.up(ctx.now):
            return Allow()
        return Deny(retry_at=service.down_until)

    return Guard(
        explain=explain or (f"The {service.name} service is unreachable; "
                            "this becomes available when it recovers."),
        check=check, reads=(f"services.{service.name}", "now"),
        name=f"service_up:{service.name}",
    )


# ── Mirrors ──────────────────────────────────────────────────────────────
class MirrorAdapter(Protocol):
    """The external system's shape, reduced to two calls. The envelope
    promises only what these can: no idempotent-replay claim beyond the
    external etag discipline."""

    async def pull(self, external_id: str) -> tuple[dict[str, Any], str]:
        """→ (external document, etag). Raise on unreachable."""
        ...

    async def push(self, external_id: str, document: dict[str, Any],
                   *, etag: str | None) -> str:
        """Write; → new etag. Raise :class:`ExternalConflict` on etag
        mismatch; raise anything else on unreachable."""
        ...


class ExternalConflict(RuntimeError):
    """The external system changed under our push; both truths persist
    until a person reconciles."""

    def __init__(self, document: dict[str, Any], etag: str):
        super().__init__("external document changed")
        self.document = document
        self.etag = etag


class SyncState(StrEnum):
    FRESH = "fresh"
    STALE = "stale"              # feed-fed mirror past its TTL
    CONFLICTED = "conflicted"    # our push met a changed external etag
    UNREACHABLE = "unreachable"  # the adapter failed; stored truth stands


class MirrorMeta(BaseModel):
    """Inherit your mirror's Data model from this: the sync bookkeeping
    is data, so it renders — staleness is visible, never silent."""

    external_id: str = Field(max_length=256, json_schema_extra={
        "x-display": {"raw": True}})
    external_etag: str | None = Field(default=None, json_schema_extra={
        "x-display": {"hidden": True}})
    synced_at: datetime | None = None
    theirs: dict[str, Any] | None = Field(
        default=None,
        description="The external document, when it conflicts with ours")


class ObserveInput(BaseModel):
    document: dict[str, Any]
    etag: str = Field(max_length=256)


class ConflictInput(BaseModel):
    document: dict[str, Any]
    etag: str = Field(max_length=256)


class ReconcileInput(BaseModel):
    keep: str = Field(pattern="^(ours|theirs)$", max_length=6,
                      description="Which truth wins: ours (push it) or "
                                  "theirs (adopt it)")


SYSTEM_OBSERVER = Principal(id="mirror-sync", type="system",
                            display="Mirror sync")

_MIRROR_FIELDS = set(MirrorMeta.model_fields)


async def _is_system(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
    return Allow() if ctx.principal.type == "system" else Deny()


system_only = Guard(
    name="system_only", reads=("principal",), hide=True,
    explain="Sync bookkeeping is the engine's; humans reconcile instead.",
    check=_is_system,
)

_ALL_SYNC = {SyncState.FRESH, SyncState.STALE, SyncState.CONFLICTED,
             SyncState.UNREACHABLE}


class Mirror(Resource):
    """Base class for mirrored resources::

        class GroceryMirror(Mirror):
            kind = "grocery_mirror"
            Data = GroceryMirrorData        # inherits MirrorMeta
            adapter = TodoAdapter(...)      # the anti-corruption seam
            ttl_seconds = 300
            push_on_write = True
            summary = "…"

    The machine *is* the sync machine; domain state, if any, lives in
    data (a mirror's lifecycle belongs to the system it mirrors).
    """

    __waymark_abstract__ = True

    adapter: ClassVar[Any] = None
    ttl_seconds: ClassVar[int] = 300
    push_on_write: ClassVar[bool] = True

    State = SyncState
    initial = SyncState.FRESH
    terminal: ClassVar[set] = set()

    # ── subclass hooks ───────────────────────────────────────────────────
    def export_external(self) -> dict[str, Any]:
        """Our data as the external document (default: everything but the
        sync bookkeeping)."""
        dump = self.data.model_dump(mode="json")
        return {k: v for k, v in dump.items() if k not in _MIRROR_FIELDS}

    def apply_external(self, document: dict[str, Any]) -> None:
        """The external document onto our data (default: matching fields)."""
        for key, value in document.items():
            if key in _MIRROR_FIELDS:
                continue
            if key in type(self.data).model_fields:
                setattr(self.data, key, value)

    # ── sync transitions (system actor; humans reconcile) ───────────────
    @action(from_=_ALL_SYNC, to=SyncState.FRESH, input=ObserveInput,
            guards=[system_only],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Recording what the external system already "
                              "says loses nothing here.")),
            display=dict(label="Observed external change"))
    async def observe_external(self, inp: ObserveInput, ctx: Ctx) -> None:
        """An external mutation we observed — a system-actor transition,
        so audit, SSE, and follow carry changes we didn't make."""
        self.apply_external(inp.document)
        self.data.external_etag = inp.etag
        self.data.synced_at = ctx.now
        self.data.theirs = None

    @action(from_=_ALL_SYNC - {SyncState.CONFLICTED}, to=SyncState.STALE,
            guards=[system_only],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Sync bookkeeping; the next successful pull "
                              "returns the mirror to fresh.")),
            display=dict(label="Mark stale"))
    async def mark_stale(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=_ALL_SYNC - {SyncState.CONFLICTED},
            to=SyncState.UNREACHABLE, guards=[system_only],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Sync bookkeeping; the next successful pull "
                              "returns the mirror to fresh.")),
            display=dict(label="Mark unreachable"))
    async def mark_unreachable(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=_ALL_SYNC, to=SyncState.CONFLICTED, input=ConflictInput,
            guards=[system_only],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Both truths are kept on the resource; "
                              "reconcile decides, deliberately.")),
            display=dict(label="Conflict detected"))
    async def mark_conflicted(self, inp: ConflictInput, ctx: Ctx) -> None:
        self.data.theirs = inp.document
        self.data.external_etag = inp.etag

    @action(from_=SyncState.CONFLICTED, to=SyncState.FRESH,
            input=ReconcileInput,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The losing version of this document "
                                      "is overwritten, here and externally."),
            display=dict(label="Reconcile", style="primary", order=1))
    async def reconcile(self, inp: ReconcileInput, ctx: Ctx) -> None:
        """A person picks the winner — never a silent last-writer-wins."""
        if inp.keep == "theirs":
            if self.data.theirs is not None:
                self.apply_external(self.data.theirs)
        else:
            new_etag = await type(self).adapter.push(
                self.data.external_id, self.export_external(),
                etag=self.data.external_etag)
            self.data.external_etag = new_etag
        self.data.theirs = None
        self.data.synced_at = ctx.now


MIRROR_SYNC_ACTIONS = {"observe_external", "mark_stale", "mark_unreachable",
                       "mark_conflicted", "reconcile"}


async def refresh_mirror(engine: Any, rdef: Any, instance: Any) -> Any:
    """Pull-through refresh on read: past the TTL, ask the adapter.

    - changed → ``observe_external`` (system actor) → fresh
    - unreachable → ``mark_unreachable`` once; stored truth keeps serving
      with its honest ``synced_at``
    - unchanged → nothing is written (an "observed, unchanged" transition
      per TTL would be audit noise)
    """
    cls = rdef.cls
    if cls.adapter is None:
        return instance
    now = engine.invoker.clock()
    synced = instance.data.synced_at
    if synced is not None and (now - synced).total_seconds() < cls.ttl_seconds \
            and instance.state == str(SyncState.FRESH):
        return instance
    if instance.state == str(SyncState.CONFLICTED):
        return instance  # a person's move, not the clock's
    try:
        document, etag = await cls.adapter.pull(instance.data.external_id)
    except Exception:
        if instance.state != str(SyncState.UNREACHABLE):
            await engine.invoker.invoke(
                rdef.kind, instance.id, "mark_unreachable", None,
                principal=SYSTEM_OBSERVER)
            async with engine.storage.session() as s:
                return await engine.storage.load(s, rdef.kind, instance.id)
        return instance
    if etag != instance.data.external_etag \
            or instance.state != str(SyncState.FRESH):
        await engine.invoker.invoke(
            rdef.kind, instance.id, "observe_external",
            {"document": document, "etag": etag},
            principal=SYSTEM_OBSERVER)
        async with engine.storage.session() as s:
            return await engine.storage.load(s, rdef.kind, instance.id)
    return instance


async def push_mirror(engine: Any, rdef: Any, id: str) -> None:
    """After a domain write on a push-on-write mirror: push ours; a
    changed external etag becomes the ``conflicted`` state (both truths
    kept), an unreachable adapter becomes ``unreachable`` — the local
    write stands either way; the sync state tells the truth about the
    gap."""
    cls = rdef.cls
    if cls.adapter is None or not cls.push_on_write:
        return
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, rdef.kind, id)
    if instance is None or instance.state == str(SyncState.CONFLICTED):
        return
    try:
        new_etag = await cls.adapter.push(
            instance.data.external_id, instance.export_external(),
            etag=instance.data.external_etag)
    except ExternalConflict as exc:
        await engine.invoker.invoke(
            rdef.kind, id, "mark_conflicted",
            {"document": exc.document, "etag": exc.etag},
            principal=SYSTEM_OBSERVER)
        return
    except Exception:
        if instance.state != str(SyncState.UNREACHABLE):
            await engine.invoker.invoke(rdef.kind, id, "mark_unreachable",
                                        None, principal=SYSTEM_OBSERVER)
        return
    await engine.invoker.invoke(
        rdef.kind, id, "observe_external",
        {"document": instance.export_external(), "etag": new_etag},
        principal=SYSTEM_OBSERVER)
