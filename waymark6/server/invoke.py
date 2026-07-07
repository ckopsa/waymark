"""The transition algorithm: every write in the application is this.

Ordering note: idempotency replay happens *before* the state check — a
stored replay must win even though the first execution already moved the
state (byte-for-byte replay on retry). Guards, state and version checks run
only for genuinely new requests.

Idempotent actions are additionally *naturally* replayed: if the resource's
latest transition is this same action with this same input digest and the
state matches its outcome, the invocation returns the current document
without re-running the handler — double-invoke is replay-safe by
construction, which the conformance suite verifies by observation.

2.0 (design §4): draft consumption goes through the DraftStore and is
part-key aware; the audience comes from the action's declared DraftPolicy —
this module never chooses a principal string.

4.0 (design §1–§3): every write runs the derivation maintainer — derived
values recompute in the same commit as the causing transition (this
resource's, and its owners' when an ``Owns`` input dirtied), a handler
that assigns one is refused, and the flips publish as derivation-class
events only after the commit lands (``_flip_session``).
"""
from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Callable

from pydantic import BaseModel, ValidationError

from .. import MEDIA_TYPE
from ..core.actions import ActionDef
from ..core.registry import Registry, ResourceDef
from ..core.resource import Resource
from ..core.summary import _SummaryFormatter, render_summary
from ..core.types import Ctx, Deny, Principal
from .idempotency import IdempotencyStore, body_digest
from .problems import (
    Conflict,
    EffectFailed,
    GuardRefused,
    IdempotencyKeyRequired,
    NotFound,
    Problem,
    SchemaInvalid,
    VersionConflict,
    WarningRefused,
    WrongState,
)
from .render import _out_of_state_entry, make_etag, render
from .storage.postgres import UniqueViolation

log = logging.getLogger("waymark6.jobs")

# job lease ttl (design E6): renewed every item/artifact, so a live run
# never lapses; a dead worker's job frees itself within this window
JOB_LEASE_TTL_SECONDS = 30.0


def _labeled_refs(model_cls: type[BaseModel]) -> dict[str, tuple[str, str]]:
    """Ref fields with a declared label sibling: name → (kind, label_field)."""
    from ..core.refs import ref_meta, ref_opts

    out: dict[str, tuple[str, str]] = {}
    for name, f in model_cls.model_fields.items():
        meta = ref_meta(f)
        if meta is None:
            continue
        label_field = ref_opts(f).get("label_field")
        if label_field:
            out[name] = (meta.kind, label_field)
    return out


def _has_labeled_refs(data_cls: type[BaseModel]) -> bool:
    from ..core.groups import _item_models

    return bool(_labeled_refs(data_cls)) or any(
        _labeled_refs(cls) for cls in _item_models(data_cls).values())


def _label_template(cls: type[Resource]) -> str | None:
    declared = getattr(cls, "label_template", None)
    if declared is not None:
        return declared
    if "name" in cls.Data.model_fields:
        return "{data.name}"
    return None


class _Rollback(Exception):
    """Internal: force a clean transaction abort (atomic batch with
    refusals, dry-run passes) without surfacing an error."""


@dataclass
class _EffectAttempt:
    effect: Any  # a core.compound.ResolvedEffect
    status: str  # succeeded | failed
    message: str | None = None


@dataclass
class _EffectLedger:
    """What a compound act's inline effects did (design §6), keyed by
    correlation id on the invoker until the enclosing entry point knows
    whether the transaction committed — compensation and audit are the
    settle step's."""

    label: str
    attempts: list[_EffectAttempt] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.attempts is None:
            self.attempts = []


@dataclass(frozen=True)
class InvokeResult:
    status: int
    body: bytes
    media_type: str
    doc: dict[str, Any] | None = None
    consumed_draft: tuple[str, str] | None = None  # (action, part_key)


def _to_bytes(doc: dict[str, Any]) -> bytes:
    return json.dumps(doc, separators=(",", ":"), ensure_ascii=False).encode()


def _result(doc: dict[str, Any], status: int = 200,
            consumed_draft: tuple[str, str] | None = None) -> InvokeResult:
    return InvokeResult(status=status, body=_to_bytes(doc),
                        media_type=MEDIA_TYPE, doc=doc,
                        consumed_draft=consumed_draft)


def validation_errors(exc: ValidationError) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for err in exc.errors():
        field = ".".join(str(p) for p in err["loc"]) or "_root"
        out.setdefault(field, []).append(err["msg"])
    return out


class Invoker:
    def __init__(self, *, registry: Registry, storage: Any,
                 services: Any = None, base: str = "/api",
                 clock: Callable[[], datetime] | None = None,
                 draft_store: Any = None, rate: Any = None,
                 bus: Any = None):
        self.registry = registry
        self.storage = storage
        self.idem = IdempotencyStore(storage)
        self.services = services
        self.base = base
        self.clock = clock or (lambda: datetime.now(UTC))
        self.draft_store = draft_store
        self.rate = rate  # the bus-shared rate-limit window (design §8)
        # the derivation maintainer (design §2): the one writer of derived
        # values, invoked wherever a declared input can change
        from .derived import DerivedMaintainer

        self.derived = DerivedMaintainer(registry, storage, base=base,
                                         bus=bus, clock=self.clock)
        self._job_tasks: set[Any] = set()
        # job-lease identity (design E6): one id per worker process, so a
        # multi-worker deployment can tell a live neighbor from a dead one
        self.worker_id = uuid.uuid4().hex[:16]
        # inline compound-effect ledgers (design §6), keyed by
        # correlation_id: filled while the act runs, settled (compensated
        # on abort, audited either way) by the enclosing entry point
        self._effect_ledgers: dict[str, _EffectLedger] = {}

    def _law(self, kind: str) -> str | None:
        """The definition revision currently governing ``kind`` (design
        §3): stamped on the rdef by the boot revise (§2), a dict-free
        attribute read cheap enough to sit on every write path. None
        before the first revise — the pre-law horizon."""
        rdef = self.registry.get(kind)
        return rdef.current_law if rdef is not None else None

    async def _append(self, s: Any, *, kind: str, instance: Any, action: str,
                      from_state: str, principal: Principal,
                      input_digest: str, summary: str, at: datetime,
                      correlation_id: str | None = None,
                      acknowledged: list[str] | None = None) -> Any:
        """THE transition-append choke point (design §3): every write path
        — create, action, batch item, bulk, compound child, authored sync,
        job bookkeeping — lands here, so the ``defined_by`` anchor is the
        invoker's one lookup and no individual path can forget it."""
        return await self.storage.append_transition(
            s, kind=kind, instance=instance, action=action,
            from_state=from_state, principal=principal,
            input_digest=input_digest, summary=summary, at=at,
            correlation_id=correlation_id, acknowledged=acknowledged,
            defined_by=self._law(kind))

    def spawn(self, coro: Any) -> None:
        import asyncio

        task = asyncio.create_task(coro)
        self._job_tasks.add(task)
        task.add_done_callback(self._job_tasks.discard)

    def _ctx(self, principal: Principal, session: Any, *, locale: str = "en",
             correlation_id: str | None = None,
             mode: str = "invoke") -> Ctx:
        return Ctx(principal=principal, now=self.clock(), services=self.services,
                   session=session, locale=locale,
                   correlation_id=correlation_id, mode=mode,  # type: ignore[arg-type]
                   _invoker=self._child_invoke, _reader=self._child_read,
                   _finder=self._child_find, _rate=self.rate,
                   _creator=self._child_create, _actor_of=self._child_actor_of,
                   _deferrer=self._child_defer, _effector=self._child_effects)

    def _flip_session(self):
        """A write transaction with a derivation-flip sink (design §3):
        flips collect while the transaction is open — nested ``ctx.invoke``
        writes share the sink — and publish on the bus only after it
        commits. An abort discards them: a rolled-back write announces
        nothing, exactly like its transition row."""
        from contextlib import asynccontextmanager

        @asynccontextmanager
        async def flip_session():
            async with self.storage.session() as s:
                sink = self.derived.open_sink(s)
                try:
                    yield s
                finally:
                    self.derived.close_sink(s)
            await self.derived.publish(sink)

        return flip_session()

    # ── public entry points ─────────────────────────────────────────────
    async def invoke(self, kind: str, id: str, action_name: str,
                     body: dict[str, Any] | None, *, principal: Principal,
                     if_match: str | None = None,
                     idempotency_key: str | None = None,
                     dry_run: bool = False, locale: str = "en",
                     acknowledged: frozenset[str] = frozenset(),
                     correlation_id: str | None = None) -> InvokeResult:
        cid = correlation_id or uuid.uuid4().hex
        try:
            async with self._flip_session() as s:
                result = await self._invoke_in_session(
                    s, kind, id, action_name, body, principal=principal,
                    if_match=if_match, idempotency_key=idempotency_key,
                    dry_run=dry_run, locale=locale,
                    correlation_id=cid,
                    acknowledged=acknowledged,
                )
        except UniqueViolation as exc:
            # outside the aborted transaction: the conflicting row is
            # queryable again, and the Problem carries the link (design E2)
            await self._settle_effects(cid, committed=False)
            raise await self._conflict(exc, action=action_name) from exc
        except BaseException:
            # a compound's executed effects are compensated when the act
            # aborts, whatever aborted it (design §6)
            await self._settle_effects(cid, committed=False)
            raise
        await self._settle_effects(cid, committed=True)
        return result

    async def create(self, kind: str, body: dict[str, Any] | None, *,
                     principal: Principal, idempotency_key: str | None = None,
                     dry_run: bool = False, locale: str = "en",
                     acknowledged: frozenset[str] = frozenset()) -> InvokeResult:
        try:
            return await self._create_entry(
                kind, body, principal=principal,
                idempotency_key=idempotency_key, dry_run=dry_run,
                locale=locale, acknowledged=acknowledged)
        except UniqueViolation as exc:
            raise await self._conflict(exc, action="create") from exc

    async def _create_entry(self, kind: str, body: dict[str, Any] | None, *,
                            principal: Principal,
                            idempotency_key: str | None,
                            dry_run: bool, locale: str,
                            acknowledged: frozenset[str] = frozenset(),
                            ) -> InvokeResult:
        rdef = self._rdef(kind)
        digest = body_digest(body)
        if dry_run:
            if not rdef.cls.create_guards:
                # schema validation only: no insert, no on_create (its
                # ctx.invoke side effects must not fire), no idempotency key
                # demanded
                self._validate(rdef.extra.get("create_model") or rdef.cls.Data,
                               body, rdef)
                return InvokeResult(status=200, body=_to_bytes({"valid": True}),
                                    media_type="application/json",
                                    doc={"valid": True})
            # declared create guards (design E9): dry-run judges them the
            # way the action path does — refuse-severity raises, pending
            # warnings ride the response body
            async with self.storage.session() as s:
                ctx = self._ctx(principal, s, locale=locale,
                                correlation_id=uuid.uuid4().hex,
                                mode="dry_run")
                data = self._validate(
                    rdef.extra.get("create_model") or rdef.cls.Data, body, rdef)
                _, warned = await self._create_guards(rdef, data, ctx,
                                                      acknowledged)
            doc: dict[str, Any] = {"valid": True}
            if warned:
                doc["warnings"] = [
                    {"name": d.name, "reason": d.render_reason(v, None),
                     **({"remedies": list(d.remedies)} if d.remedies else {})}
                    for v, d in warned]
            return InvokeResult(status=200, body=_to_bytes(doc),
                                media_type="application/json", doc=doc)
        async with self._flip_session() as s:
            if idempotency_key is None:
                raise IdempotencyKeyRequired(
                    "Creating a resource is not idempotent; send an "
                    "Idempotency-Key header.")
            hit = await self.idem.lookup(s, idempotency_key, action="create",
                                         digest=digest)
            if hit:
                return InvokeResult(status=hit.status, body=hit.body,
                                    media_type=hit.media_type)
            ctx = self._ctx(principal, s, locale=locale,
                            correlation_id=uuid.uuid4().hex)
            doc = await self._create_core(s, ctx, rdef, body, digest,
                                          acknowledged=acknowledged)
            result = _result(doc, status=201)
            await self.idem.store(
                s, idempotency_key, kind=kind, action="create", digest=digest,
                status=201, body=result.body, media_type=result.media_type,
                at=ctx.now)
            return result

    async def _create_guards(self, rdef: ResourceDef, data: BaseModel,
                             ctx: Ctx, acknowledged: frozenset[str],
                             ) -> tuple[list[str], list[tuple[Deny, Any]]]:
        """Create-time guards (design E9): judge the validated create input
        with r=None — no instance exists yet. The severity split mirrors
        the action path (design E1): a refuse-severity Deny raises the
        same GuardRefused shape minus the ``resource=`` embed; warnings
        collect for the caller to raise or report. Returns (overridden,
        warned)."""
        overridden: list[str] = []
        warned: list[tuple[Deny, Any]] = []
        for g in rdef.cls.create_guards:
            verdict, denier = await g.evaluate(None, data, ctx)
            if isinstance(verdict, Deny):
                if denier.severity == "warning":
                    if denier.name in acknowledged:
                        overridden.append(denier.name)
                    else:
                        warned.append((verdict, denier))
                    continue
                raise GuardRefused(
                    denier.render_reason(verdict, None),
                    action_attempted="create",
                    errors=verdict.errors,
                    remedies=list(denier.remedies) or None)
        return overridden, warned

    async def _create_core(self, s: Any, ctx: Ctx, rdef: ResourceDef,
                           body: dict[str, Any] | None,
                           digest: str, *,
                           acknowledged: frozenset[str] = frozenset(),
                           ) -> dict[str, Any]:
        """Create inside an open session: one code path whether the caller
        is the router's POST or an approved agent invocation running
        through ``ctx.create`` (design §2: approval capture is a stage on
        the write pipeline, and create is a write — it composes)."""
        data = self._validate(rdef.extra.get("create_model") or rdef.cls.Data,
                              body, rdef)
        # create guards run on the validated input, before any side effect
        # — predecessors, on_create, and the insert all wait (design E9)
        overridden, warned = await self._create_guards(rdef, data, ctx,
                                                       acknowledged)
        if warned:
            raise WarningRefused(
                "Advisory guards require acknowledgment before this action "
                "proceeds.",
                action_attempted="create",
                severity="warning",
                warnings=[{"name": d.name,
                           "reason": d.render_reason(v, None),
                           **({"remedies": list(d.remedies)}
                              if d.remedies else {})}
                          for v, d in warned],
                acknowledge={"header": "Waymark-Acknowledge",
                             "names": sorted(d.name for _, d in warned)})
        instance = rdef.cls(
            id=uuid.uuid4().hex, state=rdef.machine.initial, data=data,
            version=1, created_at=ctx.now, updated_at=ctx.now,
            owner=ctx.principal.id,
        )
        # predecessor refs resolve first (design E7): on_create may read
        # the resolved sibling for carry-forward
        await self._resolve_predecessors(instance, ctx)
        # on_create: initial data that depends on other resources —
        # runs with full ctx (read/find/invoke) before the first insert
        await instance.on_create(ctx)
        await self._maintain_ref_labels(instance, None, ctx)
        # derived values materialize before the first insert (design §2):
        # a row is born already telling its declared truth. Initial
        # computation is not a flip — nothing existed to change from
        await self.derived.materialize(s, instance, rdef, now=ctx.now)
        await self.storage.insert(s, rdef.kind, instance)
        summary = render_summary(rdef.summary_template, instance)
        # the declared create spelling (design §2): one path, one label
        # per kind — the definition kind logs its non-first revisions as
        # `revise`, the deploy transition, without a second create path
        create_action = instance.created_as()
        if create_action not in rdef.cls.create_action_names:
            from ..core.types import DefinitionError

            raise DefinitionError(
                f"{rdef.kind}.created_as() returned {create_action!r}, "
                "which create_action_names does not declare — the log's "
                "vocabulary is declared, never improvised")
        created = await self._append(
            s, kind=rdef.kind, instance=instance, action=create_action,
            from_state="", principal=ctx.principal, input_digest=digest,
            summary=summary, at=ctx.now, correlation_id=ctx.correlation_id,
            acknowledged=sorted(overridden) or None,
        )
        # a new child dirties its owners' Owns-based derivations (design
        # §2) — same commit, cause = this create's transition id
        await self.derived.recompute_owners(s, instance, rdef, None,
                                            now=ctx.now, cause=created.id)
        # template instantiation (design E4): seeds run in the same txn —
        # a parent cannot exist with half its declared children
        await self._seed_owned(s, instance, rdef, ctx)
        if any(spec.child_inputs or spec.related_inputs
               for _, spec in self.derived.specs(rdef.cls)):
            # seeded children updated the ROW's derived values through
            # their own creates; refresh the in-memory instance so the
            # create response doesn't lag its own commit
            await self.derived.materialize(s, instance, rdef, now=ctx.now)
            await self.storage.update_data(s, rdef.kind, instance)
        rollups = None
        from .owns import compute_rollups, has_rollups

        if has_rollups(rdef):
            computed = await compute_rollups(self.storage, s, rdef,
                                             [instance.id])
            rollups = computed.get(instance.id)
        return await render(instance, rdef, ctx=ctx, base=self.base,
                            rollups=rollups)

    async def _child_create(self, resource: Any, body: dict[str, Any] | None,
                            *, ctx: Ctx,
                            acknowledged: frozenset[str] = frozenset(),
                            ) -> dict[str, Any]:
        """ctx.create: child create in the same txn + correlation.

        The enclosing actor's header still does not travel implicitly
        (design E9); ``acknowledged`` carries only what a surface already
        recorded as data — an approval-mode create's stored acknowledgments
        (design §10), reviewed by the approver alongside the input."""
        from ..core.touches import allows_create

        kind = resource if isinstance(resource, str) else resource.kind
        scope = ctx._touch_scope
        if scope is not None and not allows_create(scope[1], kind):
            from ..core.types import DefinitionError

            raise DefinitionError(
                f"action {scope[0]} created a {kind!r} via ctx.create but "
                f"declares no Creates({kind!r}) — a transition's writes may "
                "not out-run its advertisement (design E8)")
        return await self._create_core(ctx.session, ctx, self._rdef(kind),
                                       body, body_digest(body),
                                       acknowledged=acknowledged)

    # ── per-field authority (design §8) ──────────────────────────────────
    async def sync_authored(self, kind: str, id: str,
                            document: dict[str, Any] | None, etag: str, *,
                            principal: Principal | None = None) -> list[str]:
        """The authority's one write path for authored fields (design §8):
        apply the pulled document's *authored subset* to the row as a
        system-actor transition — audit, events, and derivation recompute
        exactly as a Mirror pull gets them. Written and derived fields are
        untouched; a pull that would change a non-authored declared field
        is an adapter/definition error, raised loudly.

        Note on shape: the resource keeps its own domain machine, so this
        write is a same-state transition appended by the engine's write
        tail (``_authored_commit``) rather than a declared ``@action`` —
        an action's ``to=`` names one state and a sync must not move any.
        It shares the flip-session, the maintainer, the log, and the
        outbox with every other write; nothing parallel is stored.

        ``follows=`` fires AFTER the sync's commit, each through the
        single invoker as the system actor: guards run, and a refusal
        leaves the recorded value change standing — logged, never silent.
        Returns the names of the authored fields that changed."""
        from ..core.authored import authored_specs
        from ..core.types import DefinitionError
        from .external import AUTHORED_BOOKKEEPING, SYSTEM_OBSERVER

        principal = principal or SYSTEM_OBSERVER
        rdef = self._rdef(kind)
        specs = authored_specs(rdef.cls.Data)
        if not specs:
            raise DefinitionError(
                f"{kind} declares no authored fields — sync_authored has "
                "nothing it may write (design §8)")
        changed: dict[str, Any] = {}
        async with self._flip_session() as s:
            instance = await self.storage.load(s, kind, id, for_update=True)
            if instance is None:
                raise NotFound(f"No {kind} {id!r}.")
            before = instance.data.model_dump(mode="json")
            data_fields = type(instance.data).model_fields
            for key, value in (document or {}).items():
                if key in specs:
                    if getattr(instance.data, key, None) != value:
                        setattr(instance.data, key, value)
                        changed[key] = value
                elif key in data_fields and key not in AUTHORED_BOOKKEEPING:
                    if before.get(key) != value:
                        raise DefinitionError(
                            f"{kind}: the authority's document changes "
                            f"non-authored field {key!r} — only authored "
                            "fields sync (design §8); scope the adapter or "
                            "declare the field Authored")
            instance.data.external_etag = etag
            instance.data.synced_at = self.clock()
            instance.data.sync_state = "fresh"
            instance.data.theirs = None
            await self._authored_commit(s, rdef, instance, "observe_authored",
                                        principal, before)
        for fname, value in changed.items():
            target = specs[fname].follows.get(value)
            if target is None:
                continue
            try:
                await self.invoke(kind, id, target, None, principal=principal)
            except Problem as exc:
                # the value change is recorded; the declared transition was
                # refused by its own guards — the log tells the whole story
                log.warning(
                    "authored follows: %s/%s %s=%r -> %s refused: %s",
                    kind, id, fname, value, target, exc.detail)
        return sorted(changed)

    async def mark_authored(self, kind: str, id: str, sync_state: str, *,
                            theirs: dict[str, Any] | None = None,
                            etag: str | None = None,
                            principal: Principal | None = None) -> None:
        """Sync bookkeeping for a partially-authored resource (design §8):
        ``stale`` / ``conflicted`` / ``unreachable``, scoped to the
        authority's fields — data, not machine state, so written-field
        actions never wait on it. Audited like Mirror's marks."""
        from ..core.types import DefinitionError
        from .external import SYSTEM_OBSERVER

        if sync_state not in ("stale", "conflicted", "unreachable"):
            raise DefinitionError(
                f"mark_authored: {sync_state!r} is not a sync state — "
                "fresh is the successful sync's to write")
        principal = principal or SYSTEM_OBSERVER
        rdef = self._rdef(kind)
        async with self._flip_session() as s:
            instance = await self.storage.load(s, kind, id, for_update=True)
            if instance is None:
                return
            if instance.data.sync_state == sync_state and theirs is None:
                return  # no audit noise for an unchanged mark
            before = instance.data.model_dump(mode="json")
            instance.data.sync_state = sync_state
            if theirs is not None:
                instance.data.theirs = theirs
            if etag is not None:
                instance.data.external_etag = etag
            await self._authored_commit(s, rdef, instance,
                                        f"mark_{sync_state}", principal,
                                        before)

    async def _authored_commit(self, s: Any, rdef: ResourceDef,
                               instance: Resource, action_name: str,
                               principal: Principal,
                               before: dict[str, Any]) -> None:
        """The write tail every transition gets, for the authority's
        same-state sync writes: maintainer recompute in the same commit,
        version bump, transition row (audit + outbox), flip anchoring,
        owner recompute. One story in the log, like any other write."""
        now = self.clock()
        flips = await self.derived.materialize(s, instance, rdef, now=now)
        instance.version += 1
        instance.updated_at = now
        summary = render_summary(rdef.summary_template, instance)
        transition = await self._append(
            s, kind=rdef.kind, instance=instance, action=action_name,
            from_state=instance.state, principal=principal,
            input_digest="", summary=summary, at=now,
            correlation_id=uuid.uuid4().hex)
        self.derived.record(s, rdef, instance.id, flips,
                            cause=transition.id, at=now)
        await self.storage.save(s, rdef.kind, instance,
                                expected_version=instance.version - 1)
        await self.derived.recompute_owners(s, instance, rdef, before,
                                            now=now, cause=transition.id)

    # ── the algorithm ───────────────────────────────────────────────────
    async def _invoke_in_session(
        self, s: Any, kind: str, id: str, action_name: str,
        body: dict[str, Any] | None, *, principal: Principal,
        if_match: str | None, idempotency_key: str | None, dry_run: bool,
        locale: str, correlation_id: str | None, require_key: bool = True,
        allow_bulk: bool = False, acknowledged: frozenset[str] = frozenset(),
    ) -> InvokeResult:
        rdef = self._rdef(kind)
        defn = rdef.machine.actions.get(action_name)
        if defn is None or (defn.bulk and not allow_bulk):
            raise NotFound(f"{rdef.kind} has no action {action_name!r}.")
        digest = body_digest(body)

        if (not dry_run and require_key and not defn.safety.idempotent
                and idempotency_key is None):
            raise IdempotencyKeyRequired(
                f"Action {action_name!r} is not idempotent; send an "
                "Idempotency-Key header so retries are safe.",
                action_attempted=action_name)
        if not dry_run and idempotency_key is not None:
            hit = await self.idem.lookup(s, idempotency_key, action=action_name,
                                         digest=digest)
            if hit:
                return InvokeResult(status=hit.status, body=hit.body,
                                    media_type=hit.media_type)

        instance = await self.storage.load(s, kind, id, for_update=not dry_run)
        if instance is None:
            raise NotFound(f"No {rdef.kind} {id!r}.")

        ctx = self._ctx(principal, s, locale=locale,
                        correlation_id=correlation_id,
                        mode="dry_run" if dry_run else "invoke")

        if instance.state not in defn.from_:
            natural = await self._natural_replay(s, rdef, instance, defn, digest, ctx)
            if natural is not None:
                return natural
            # concealment holds out-of-state too: what render hides by a
            # hide-flagged guard, the wire must 404, not narrate
            from .render import probe_hidden_only

            if await probe_hidden_only(defn, instance, ctx):
                raise NotFound(f"No {rdef.kind} {id!r}.")
            entry = _out_of_state_entry(defn, instance.state)
            raise WrongState(
                entry["reason"], action_attempted=action_name,
                state=instance.state,
                becomes_available=entry["becomes_available"],
                resource=await render(instance, rdef, ctx=ctx, depth="summary",
                                      base=self.base))

        if defn.safety.fence:
            current = make_etag(rdef.kind, instance.id, instance.version)
            if if_match is None or if_match.strip() != current:
                raise VersionConflict(
                    "The resource changed since you read it. Re-read and retry "
                    "with the current etag.",
                    action_attempted=action_name,
                    resource=await render(instance, rdef, ctx=ctx, depth="summary",
                                          base=self.base))

        inp: BaseModel | None = None
        if defn.input is not None:
            inp = self._validate(defn.input, body, rdef, action=defn)
            # conditional demand (design §5): the declared When predicates
            # judge the validated input against this document — before
            # guards, alongside schema validation, in the same field-keyed
            # 422 shape the schema's if/then already advertised
            from ..core.when import when_errors

            conditional = when_errors(defn.input, inp, instance)
            if conditional:
                raise SchemaInvalid(
                    "Input failed validation.",
                    action_attempted=action_name, errors=conditional)
        elif body:
            raise SchemaInvalid(
                f"Action {action_name!r} takes an empty body.",
                action_attempted=action_name,
                errors={k: ["unexpected field"] for k in body})

        # Natural replay runs BEFORE guards (2.0 ordering): if the latest
        # transition is this same action with this same input digest and the
        # state matches its outcome, the first execution's guards already
        # passed — and re-running them can honestly deny (an acceptance set
        # no longer contains the value the first invoke consumed). Replay is
        # the byte-honest answer, exactly as with idempotency keys.
        if not dry_run and defn.safety.idempotent:
            natural = await self._natural_replay(s, rdef, instance, defn, digest, ctx)
            if natural is not None:
                return natural

        overridden: list[str] = []
        warned: list[tuple[Deny, Any]] = []
        for g in defn.guards:
            verdict, denier = await g.evaluate(instance, inp, ctx)
            if isinstance(verdict, Deny):
                if denier.severity == "warning":
                    # advisory guards (design E1): an acknowledged warning
                    # passes AND lands in the log; unacknowledged collects —
                    # one problem carries every warning, not a drip-feed
                    if denier.name in acknowledged:
                        overridden.append(denier.name)
                    else:
                        warned.append((verdict, denier))
                    continue
                await self._refuse(s, rdef, instance, defn, verdict, denier, ctx)

        if dry_run:
            doc: dict[str, Any] = {"valid": True}
            if warned:
                doc["warnings"] = [
                    {"name": d.name, "reason": d.render_reason(v, instance),
                     **({"remedies": list(d.remedies)} if d.remedies else {})}
                    for v, d in warned]
            return InvokeResult(status=200, body=_to_bytes(doc),
                                media_type="application/json", doc=doc)

        if warned:
            raise WarningRefused(
                "Advisory guards require acknowledgment before this action "
                "proceeds.",
                action_attempted=action_name,
                severity="warning",
                warnings=[{"name": d.name,
                           "reason": d.render_reason(v, instance),
                           **({"remedies": list(d.remedies)}
                              if d.remedies else {})}
                          for v, d in warned],
                acknowledge={"header": "Waymark-Acknowledge",
                             "names": sorted(d.name for _, d in warned)})

        from_state = instance.state
        from ..core import groups as oneof

        needs_before = (oneof.has_groups(type(instance.data))
                        or _has_labeled_refs(type(instance.data))
                        or bool(self.derived.owner_vias(kind))
                        # a related target's write dirties the anchor SET
                        # its old and new join values select (design 6.0
                        # §2) — the inverted predicate needs both halves
                        or self.derived.related_dirties(kind))
        before = instance.data.model_dump(mode="json") if needs_before else None
        derived_before = self.derived.snapshot(instance)
        from ..core.authored import authored_snapshot

        authored_before = authored_snapshot(instance)
        # the handler runs under its action's declared touches (design E8):
        # the same declaration the envelope advertises, enforced here
        ctx._touch_scope = (f"{kind}.{action_name}", defn.touches)
        try:
            await defn.handler(instance, inp, ctx)
        finally:
            ctx._touch_scope = None
        # derived values are engine-computed only (design §1): a handler
        # that assigned one is refused, never silently recomputed over
        self.derived.refuse_tampering(rdef, instance, action_name,
                                      derived_before)
        # authored values are the authority's only (design §8): the same
        # refusal for a handler that assigned one
        for name, old in authored_before.items():
            if getattr(instance.data, name, None) != old:
                from ..core.types import DefinitionError

                raise DefinitionError(
                    f"{kind}.{action_name} assigned authored field {name!r} "
                    "— authored values are the authority's to write (design "
                    "§8); they change only through its sync path")
        # Ref labels are the engine's to maintain (design §4) — before the
        # OneOf pass so a cleared arm clears its label with it
        await self._maintain_ref_labels(instance, before, ctx)
        if oneof.has_groups(type(instance.data)):
            # declared exclusivity (design §5): filling one arm clears the
            # others; filling two is a definition bug and raises here
            oneof.enforce(instance.data, before)
        # the maintainer recomputes this row's derivations in the same
        # commit as the transition (design §2) — the summary, the saved
        # data, and the promoted columns all carry the fresh truth
        flips = await self.derived.materialize(s, instance, rdef,
                                               now=ctx.now)
        instance.state = defn.to
        instance.version += 1
        instance.updated_at = ctx.now
        summary = render_summary(rdef.summary_template, instance)
        transition = await self._append(
            s, kind=kind, instance=instance, action=action_name,
            from_state=from_state, principal=principal, input_digest=digest,
            summary=summary, at=ctx.now, correlation_id=ctx.correlation_id,
            acknowledged=sorted(overridden) or None,
        )
        # flips are anchored to their cause (design §3): the transition id
        # rides the event, published only after this commit lands
        self.derived.record(s, rdef, instance.id, flips,
                            cause=transition.id, at=ctx.now)
        await self.storage.save(s, kind, instance,
                                expected_version=instance.version - 1)
        # the owned side dirtied its owners' facts (design §2): both the
        # old and new parent when the via ref itself moved
        await self.derived.recompute_owners(s, instance, rdef, before,
                                            now=ctx.now, cause=transition.id)
        consumed: tuple[str, str] | None = None
        if defn.draft and self.draft_store is not None:
            # the effort landed; the draft has served its purpose. The
            # audience is the policy's, the part is the input's — this code
            # chooses neither.
            from .drafts import audience_of, part_key_of

            part_key = part_key_of(defn, body)
            await self.draft_store.consume(
                s, rdef, defn, id, part_key, audience_of(defn, principal))
            consumed = (action_name, part_key)

        drafts = None
        if self.draft_store is not None:
            # engine-equivalent view so the returned document adverts drafts
            from .drafts import audience_of as _aud

            rows = await self.storage.load_drafts(s, kind, id)
            drafts = {}
            for (action, pk, audience), row in rows.items():
                d = rdef.machine.actions.get(action)
                if d is not None and d.draft and audience == _aud(d, principal).token:
                    drafts[(action, pk)] = row
        rollups = None
        from .owns import compute_rollups, has_rollups

        if has_rollups(rdef):
            # the POST response must not out-lag a GET (design E4)
            computed = await compute_rollups(self.storage, s, rdef,
                                             [instance.id])
            rollups = computed.get(instance.id)
        doc = await render(instance, rdef, ctx=ctx, base=self.base,
                           drafts=drafts, rollups=rollups)
        result = _result(doc, consumed_draft=consumed)
        if idempotency_key is not None:
            await self.idem.store(
                s, idempotency_key, kind=kind, action=action_name, digest=digest,
                status=200, body=result.body, media_type=result.media_type,
                at=ctx.now)
        return result

    # ── bulk ────────────────────────────────────────────────────────────
    async def bulk(self, kind: str, action_name: str,
                   body: dict[str, Any] | None, *, principal: Principal,
                   idempotency_key: str | None = None,
                   locale: str = "en",
                   acknowledged: frozenset[str] = frozenset()) -> InvokeResult:
        rdef = self._rdef(kind)
        defn = rdef.machine.actions.get(action_name)
        if defn is None or not defn.bulk:
            raise NotFound(f"{rdef.kind} has no bulk action {action_name!r}.")
        body = body or {}
        ids = body.get("ids")
        if not isinstance(ids, list) or not ids or \
                not all(isinstance(i, str) for i in ids):
            raise SchemaInvalid("Bulk actions require a non-empty `ids` array.",
                                action_attempted=action_name,
                                errors={"ids": ["required, non-empty array of strings"]})
        if len(ids) > defn.max_items:
            raise SchemaInvalid(f"At most {defn.max_items} ids per call.",
                                action_attempted=action_name,
                                errors={"ids": [f"maxItems is {defn.max_items}"]})
        item_body = {k: v for k, v in body.items() if k != "ids"} or None
        digest = body_digest(body)

        if not defn.safety.idempotent and idempotency_key is None:
            raise IdempotencyKeyRequired(
                f"Bulk action {action_name!r} is not idempotent; send an "
                "Idempotency-Key header.", action_attempted=action_name)
        if idempotency_key is not None:
            async with self.storage.session() as s:
                hit = await self.idem.lookup(s, idempotency_key,
                                             action=f"bulk:{action_name}",
                                             digest=digest)
            if hit:
                return InvokeResult(status=hit.status, body=hit.body,
                                    media_type=hit.media_type)

        correlation_id = uuid.uuid4().hex
        if defn.defer_over is not None and len(ids) > defn.defer_over:
            return await self._defer_bulk(rdef, defn, ids, item_body,
                                          principal, correlation_id, locale,
                                          acknowledged)

        if defn.atomic:
            report = await self._bulk_atomic(rdef, defn, ids, item_body,
                                             principal, correlation_id, locale,
                                             acknowledged)
        else:
            report = await self._bulk_partial(rdef, defn, ids, item_body,
                                              principal, correlation_id, locale,
                                              acknowledged)
        doc = {
            "kind": "bulk_report",
            "action": action_name,
            "data": report,
            "links": {"job": None},
        }
        result = _result(doc)
        if idempotency_key is not None:
            async with self.storage.session() as s:
                await self.idem.store(
                    s, idempotency_key, kind=kind, action=f"bulk:{action_name}",
                    digest=digest, status=200, body=result.body,
                    media_type=result.media_type, at=self.clock())
        return result

    async def _bulk_item(self, s: Any, rdef: ResourceDef, defn: ActionDef,
                         id: str, item_body: dict[str, Any] | None,
                         principal: Principal, correlation_id: str,
                         locale: str,
                         acknowledged: frozenset[str] = frozenset()) -> None:
        # bulk actions run the same per-item algorithm; the bulk affordance
        # is just a fan-out (guards evaluated per item)
        try:
            await self._invoke_in_session(
                s, rdef.kind, id, defn.name, item_body, principal=principal,
                if_match=None, idempotency_key=None, dry_run=False,
                locale=locale, correlation_id=correlation_id,
                require_key=False, allow_bulk=True, acknowledged=acknowledged)
        except UniqueViolation as exc:
            # the shared txn is aborted; the href-enriched form needs a
            # fresh session the caller owns — the plain Conflict suffices
            raise Conflict(str(exc), action_attempted=defn.name,
                           fields=list(exc.fields)) from exc

    def _item_href(self, rdef: ResourceDef, id: str) -> str:
        return f"{self.base}/{rdef.plural}/{id}"

    async def _bulk_partial(self, rdef: ResourceDef, defn: ActionDef,
                            ids: list[str], item_body: dict[str, Any] | None,
                            principal: Principal, correlation_id: str,
                            locale: str,
                            acknowledged: frozenset[str] = frozenset(),
                            ) -> dict[str, Any]:
        report = {"succeeded": 0, "refused": 0, "failed": 0, "refusals": []}
        for id in ids:
            try:
                async with self._flip_session() as s:  # one txn per item
                    await self._bulk_item(s, rdef, defn, id, item_body,
                                          principal, correlation_id, locale,
                                          acknowledged)
                await self._settle_effects(correlation_id, committed=True)
                report["succeeded"] += 1
            except (Conflict, GuardRefused, NotFound, VersionConflict,
                    SchemaInvalid) as exc:
                await self._settle_effects(correlation_id, committed=False)
                report["refused"] += 1
                report["refusals"].append(
                    {"self": self._item_href(rdef, id), "reason": exc.detail})
            except Exception:  # noqa: BLE001 - partial-success must not abort
                await self._settle_effects(correlation_id, committed=False)
                report["failed"] += 1
                report["refusals"].append(
                    {"self": self._item_href(rdef, id),
                     "reason": "Internal error while processing this item."})
        return report

    async def _bulk_atomic(self, rdef: ResourceDef, defn: ActionDef,
                           ids: list[str], item_body: dict[str, Any] | None,
                           principal: Principal, correlation_id: str,
                           locale: str,
                           acknowledged: frozenset[str] = frozenset(),
                           ) -> dict[str, Any]:
        try:
            async with self._flip_session() as s:  # one txn for all items
                for id in ids:
                    await self._bulk_item(s, rdef, defn, id, item_body,
                                          principal, correlation_id, locale,
                                          acknowledged)
        except Problem as exc:
            await self._settle_effects(correlation_id, committed=False)
            raise GuardRefused(
                f"Atomic bulk action {defn.name!r} aborted: {exc.detail}",
                action_attempted=defn.name,
                refusals=[{"self": None, "reason": exc.detail}]) from exc
        await self._settle_effects(correlation_id, committed=True)
        return {"succeeded": len(ids), "refused": 0, "failed": 0, "refusals": []}

    async def _defer_bulk(self, rdef: ResourceDef, defn: ActionDef,
                          ids: list[str], item_body: dict[str, Any] | None,
                          principal: Principal, correlation_id: str,
                          locale: str,
                          acknowledged: frozenset[str] = frozenset(),
                          ) -> InvokeResult:
        from .jobs import SYSTEM, JobData

        job_rdef = self._rdef("job")
        async with self.storage.session() as s:
            now = self.clock()
            job = job_rdef.cls(
                id=uuid.uuid4().hex, state=job_rdef.machine.initial,
                data=JobData(action=defn.name, target_kind=rdef.kind,
                             total=len(ids)),
                version=1, created_at=now, updated_at=now)
            await self.storage.insert(s, "job", job)
            await self._append(
                s, kind="job", instance=job, action="create", from_state="",
                principal=SYSTEM, input_digest="", at=now,
                summary=f"Job: {defn.name} on {rdef.kind} · 0/{len(ids)} processed · Queued",
                correlation_id=correlation_id)
            ctx = self._ctx(principal, s, locale=locale,
                            correlation_id=correlation_id, mode="probe")
            doc = await render(job, job_rdef, ctx=ctx, base=self.base)
        self.spawn(self._run_job(job.id, rdef, defn, ids, item_body,
                                 principal, correlation_id, locale,
                                 acknowledged))
        return _result(doc, status=202)

    async def _run_job(self, job_id: str, rdef: ResourceDef, defn: ActionDef,
                       ids: list[str], item_body: dict[str, Any] | None,
                       principal: Principal, correlation_id: str,
                       locale: str,
                       acknowledged: frozenset[str] = frozenset()) -> None:
        if not await self._claim_job_lease(job_id):
            # another live worker holds the lease (design E6)
            log.info("job %s is leased elsewhere; this worker stands down",
                     job_id)
            return
        try:
            await self._invoke_in_session_wrapper("job", job_id, "start",
                                                  correlation_id, locale)
            report = {"succeeded": 0, "refused": 0, "failed": 0, "refusals": []}
            for id in ids:
                await self._renew_job_lease(job_id)
                async with self.storage.session() as s:
                    job = await self.storage.load(s, "job", job_id)
                if job is None or job.state == "cancelled":
                    return
                try:
                    async with self._flip_session() as s:
                        await self._bulk_item(s, rdef, defn, id, item_body,
                                              principal, correlation_id,
                                              locale, acknowledged)
                    await self._settle_effects(correlation_id, committed=True)
                    report["succeeded"] += 1
                except Problem as exc:
                    await self._settle_effects(correlation_id,
                                               committed=False)
                    report["refused"] += 1
                    report["refusals"].append(
                        {"self": self._item_href(rdef, id),
                         "reason": exc.detail})
                except Exception:  # noqa: BLE001
                    await self._settle_effects(correlation_id,
                                               committed=False)
                    report["failed"] += 1
                async with self.storage.session() as s:
                    job = await self.storage.load(s, "job", job_id,
                                                  for_update=True)
                    if job is None or job.state == "cancelled":
                        return
                    job.data.processed += 1
                    job.data.succeeded = report["succeeded"]
                    job.data.refused = report["refused"]
                    job.data.failed = report["failed"]
                    job.data.refusals = [r for r in report["refusals"]]
                    job.version += 1
                    job.updated_at = self.clock()
                    # progress is data, not a state change: no transition row
                    await self.storage.save(s, "job", job,
                                            expected_version=job.version - 1)
            await self._invoke_in_session_wrapper("job", job_id, "finish",
                                                  correlation_id, locale)
        finally:
            await self._release_job_lease(job_id)

    # ── deferred service jobs (design E6) ───────────────────────────────
    async def _child_defer(self, service: Any,
                           artifacts: list[tuple[str, tuple[Any, ...]]], *,
                           action: str, ctx: Ctx) -> str:
        """ctx.defer: the job row rides the handler's transaction (the
        create is the actor's, audited); the runner starts once the row is
        visible — it waits out the enclosing commit."""
        from .jobs import JobData, JobArtifact

        body = JobData(
            action=action, target_kind=f"service:{service.name}",
            total=len(artifacts),
            artifacts=[JobArtifact(name=n) for n, _ in artifacts],
        ).model_dump(mode="json")
        # E6's own declared surface: deferring must not demand a
        # Creates("job") from the handler — exempt by construction
        doc = await self._create_core(ctx.session, ctx, self._rdef("job"),
                                      body, body_digest(body))
        job_id = doc["self"].rsplit("/", 1)[-1]
        self.spawn(self._run_service_job(job_id, service, artifacts,
                                         ctx.correlation_id, ctx.locale))
        return job_id

    async def _run_service_job(self, job_id: str, service: Any,
                               artifacts: list[tuple[str, tuple[Any, ...]]],
                               correlation_id: str | None,
                               locale: str) -> None:
        import asyncio

        from .external import ServiceCallError, ServiceDown

        # the job row commits with the enclosing invocation; wait it out
        for _ in range(100):
            async with self.storage.session() as s:
                if await self.storage.load(s, "job", job_id) is not None:
                    break
            await asyncio.sleep(0.05)
        if not await self._claim_job_lease(job_id):
            # another live worker holds the lease (design E6)
            log.info("job %s is leased elsewhere; this worker stands down",
                     job_id)
            return
        try:
            await self._invoke_in_session_wrapper(
                "job", job_id, "start", correlation_id or uuid.uuid4().hex,
                locale)
            down: str | None = None
            for index, (_name, args) in enumerate(artifacts):
                await self._renew_job_lease(job_id)
                async with self.storage.session() as s:
                    job = await self.storage.load(s, "job", job_id)
                if job is None or job.state == "cancelled":
                    return
                outcome, message = "succeeded", None
                if down is not None:
                    outcome, message = "failed", down
                else:
                    try:
                        await service.call(*args, now=self.clock())
                    except ServiceCallError as exc:
                        # down_on_error=False (design E6): the failure is
                        # this artifact's alone — record its cause, keep
                        # the service up, keep going
                        outcome, message = "failed", exc.cause
                    except ServiceDown as exc:
                        # the declared backoff stands: remaining artifacts
                        # fail honestly instead of hammering a down
                        # service; the triggering artifact keeps the
                        # adapter's own words
                        down = (f"service {service.name} is down; retry at "
                                f"{exc.retry_at.isoformat()}")
                        outcome = "failed"
                        message = getattr(exc, "cause", None) or down
                    except Exception as exc:  # noqa: BLE001 — recorded, never silent
                        outcome, message = "failed", str(exc)[:240]
                async with self.storage.session() as s:
                    job = await self.storage.load(s, "job", job_id,
                                                  for_update=True)
                    if job is None or job.state == "cancelled":
                        return
                    artifact = job.data.artifacts[index]
                    artifact.status = outcome
                    artifact.message = message
                    job.data.processed += 1
                    if outcome == "succeeded":
                        job.data.succeeded += 1
                    else:
                        job.data.failed += 1
                    job.version += 1
                    job.updated_at = self.clock()
                    # progress is data, not a state change: no transition row
                    await self.storage.save(s, "job", job,
                                            expected_version=job.version - 1)
            await self._invoke_in_session_wrapper(
                "job", job_id, "finish", correlation_id or uuid.uuid4().hex,
                locale)
        finally:
            await self._release_job_lease(job_id)

    # ── compound effects (design §6) ─────────────────────────────────────
    def _service(self, name: str) -> Any:
        svc = getattr(self.services, name, None)
        if svc is None:
            from ..core.types import DefinitionError

            raise DefinitionError(
                f"no declared service {name!r} on this engine — a compound "
                "effect invokes a declared Service operation (design §6)")
        return svc

    async def _child_effects(self, label: str, effects: list, *,
                             defer: bool, ctx: Ctx) -> str | None:
        """ctx.run_effects: the declared external writes of a compound act.

        Inline: execute in declaration order inside the act (after the
        resource writes, before the commit), recording a ledger the
        enclosing entry point settles — compensators run in reverse on
        abort, and every attempt lands on a job-resource audit under the
        act's correlation id. Deferred: the E6 job kind is the executor —
        the job row rides the handler's transaction; the runner starts
        once the commit makes it visible."""
        if defer:
            from .jobs import JobArtifact, JobData

            body = JobData(
                action=label, target_kind="compound_effects",
                total=len(effects),
                artifacts=[JobArtifact(name=e.name) for e in effects],
            ).model_dump(mode="json")
            # like ctx.defer (E6): the job row is the engine's own surface,
            # exempt from the act's declared touches by construction
            doc = await self._create_core(ctx.session, ctx,
                                          self._rdef("job"), body,
                                          body_digest(body))
            job_id = doc["self"].rsplit("/", 1)[-1]
            self.spawn(self._run_compound_job(job_id, list(effects),
                                              ctx.correlation_id, ctx.locale))
            return job_id
        ledger = self._effect_ledgers.setdefault(
            ctx.correlation_id, _EffectLedger(label))
        for e in effects:
            try:
                await self._service(e.service).call_op(e.op, *e.args,
                                                       now=self.clock())
            except Exception as exc:
                ledger.attempts.append(
                    _EffectAttempt(e, "failed", str(exc)[:240]))
                raise EffectFailed(
                    f"External effect {e.name} failed during {label}; the "
                    "act was aborted and executed effects were compensated.",
                    action_attempted=label.rsplit(".", 1)[-1],
                    effect=e.name) from exc
            ledger.attempts.append(_EffectAttempt(e, "succeeded"))
        return None

    async def _settle_effects(self, correlation_id: str | None, *,
                              committed: bool) -> None:
        """The other half of the effect contract (design §6): on commit
        the ledger becomes a job-resource audit under the act's
        correlation id; on abort, compensators run first, in reverse
        order over the effects that executed — each attempt audited
        alongside the effects themselves."""
        if correlation_id is None:
            return
        ledger = self._effect_ledgers.pop(correlation_id, None)
        if ledger is None or not ledger.attempts:
            return
        from .jobs import JobArtifact

        artifacts = [JobArtifact(name=a.effect.name, status=a.status,
                                 message=a.message)
                     for a in ledger.attempts]
        if not committed:
            executed = [a.effect for a in ledger.attempts
                        if a.status == "succeeded"]
            for e in reversed(executed):
                name = f"compensate:{e.service}.{e.comp_op}"
                try:
                    await self._service(e.service).call_op(
                        e.comp_op, *e.comp_args, now=self.clock())
                    artifacts.append(JobArtifact(name=name,
                                                 status="succeeded"))
                except Exception as exc:  # noqa: BLE001 — recorded, never silent
                    artifacts.append(JobArtifact(name=name, status="failed",
                                                 message=str(exc)[:240]))
        try:
            await self._record_effect_job(ledger.label, artifacts,
                                          correlation_id)
        except Exception:  # noqa: BLE001 — audit must not mask the act's outcome
            log.exception("failed to record the effect audit for %s",
                          ledger.label)

    async def _record_effect_job(self, label: str, artifacts: list,
                                 correlation_id: str) -> None:
        """Effect attempts are audited the way E6 audits service attempts:
        a job resource — per-attempt artifacts as data, driven queued →
        running → done by the system actor, all under the act's
        correlation id so the log reads as one story."""
        from .jobs import SYSTEM, JobData

        succeeded = sum(1 for a in artifacts if a.status == "succeeded")
        failed = sum(1 for a in artifacts if a.status == "failed")
        body = JobData(action=label, target_kind="compound_effects",
                       total=len(artifacts), processed=len(artifacts),
                       succeeded=succeeded, failed=failed,
                       artifacts=artifacts).model_dump(mode="json")
        async with self._flip_session() as s:
            ctx = self._ctx(SYSTEM, s, correlation_id=correlation_id)
            doc = await self._create_core(s, ctx, self._rdef("job"), body,
                                          body_digest(body))
            job_id = doc["self"].rsplit("/", 1)[-1]
            for step in ("start", "finish"):
                await self._invoke_in_session(
                    s, "job", job_id, step, None, principal=SYSTEM,
                    if_match=None, idempotency_key=None, dry_run=False,
                    locale="en", correlation_id=correlation_id,
                    require_key=False)

    async def _run_compound_job(self, job_id: str, effects: list,
                                correlation_id: str | None,
                                locale: str) -> None:
        """Deferred compound effects on the E6 executor: same leases, same
        artifact bookkeeping — plus reverse-order compensation of the
        effects that executed when a later one fails (the resource writes
        are already committed; compensation is all abort can mean here)."""
        import asyncio

        # the job row commits with the enclosing invocation; wait it out
        for _ in range(100):
            async with self.storage.session() as s:
                if await self.storage.load(s, "job", job_id) is not None:
                    break
            await asyncio.sleep(0.05)
        if not await self._claim_job_lease(job_id):
            log.info("job %s is leased elsewhere; this worker stands down",
                     job_id)
            return
        try:
            await self._invoke_in_session_wrapper(
                "job", job_id, "start", correlation_id or uuid.uuid4().hex,
                locale)
            executed: list[Any] = []
            failure: str | None = None
            for index, e in enumerate(effects):
                await self._renew_job_lease(job_id)
                async with self.storage.session() as s:
                    job = await self.storage.load(s, "job", job_id)
                if job is None or job.state == "cancelled":
                    break
                outcome, message = "succeeded", None
                if failure is not None:
                    outcome = "failed"
                    message = "aborted: an earlier effect failed"
                else:
                    try:
                        await self._service(e.service).call_op(
                            e.op, *e.args, now=self.clock())
                        executed.append(e)
                    except Exception as exc:  # noqa: BLE001
                        failure = str(exc)[:240]
                        outcome, message = "failed", failure
                await self._stamp_artifact(job_id, index, outcome, message)
            if failure is not None:
                from .jobs import JobArtifact

                for e in reversed(executed):
                    name = f"compensate:{e.service}.{e.comp_op}"
                    try:
                        await self._service(e.service).call_op(
                            e.comp_op, *e.comp_args, now=self.clock())
                        art = JobArtifact(name=name, status="succeeded")
                    except Exception as exc:  # noqa: BLE001
                        art = JobArtifact(name=name, status="failed",
                                          message=str(exc)[:240])
                    await self._append_artifact(job_id, art)
            await self._invoke_in_session_wrapper(
                "job", job_id, "finish", correlation_id or uuid.uuid4().hex,
                locale)
        finally:
            await self._release_job_lease(job_id)

    async def _stamp_artifact(self, job_id: str, index: int, outcome: str,
                              message: str | None) -> None:
        async with self.storage.session() as s:
            job = await self.storage.load(s, "job", job_id, for_update=True)
            if job is None:
                return
            artifact = job.data.artifacts[index]
            artifact.status = outcome
            artifact.message = message
            job.data.processed += 1
            if outcome == "succeeded":
                job.data.succeeded += 1
            else:
                job.data.failed += 1
            job.version += 1
            job.updated_at = self.clock()
            # progress is data, not a state change: no transition row
            await self.storage.save(s, "job", job,
                                    expected_version=job.version - 1)

    async def _append_artifact(self, job_id: str, artifact: Any) -> None:
        async with self.storage.session() as s:
            job = await self.storage.load(s, "job", job_id, for_update=True)
            if job is None:
                return
            job.data.artifacts.append(artifact)
            job.data.total += 1
            job.data.processed += 1
            if artifact.status == "succeeded":
                job.data.succeeded += 1
            else:
                job.data.failed += 1
            job.version += 1
            job.updated_at = self.clock()
            await self.storage.save(s, "job", job,
                                    expected_version=job.version - 1)

    # ── batch (design §7): the collection form of the act ───────────────
    async def batch(self, kind: str, id: str, action_name: str,
                    body: dict[str, Any] | None, *, principal: Principal,
                    idempotency_key: str | None = None,
                    dry_run: bool = False, locale: str = "en",
                    acknowledged: frozenset[str] = frozenset()) -> InvokeResult:
        """N inputs → N verdicts, through the same per-item algorithm as a
        single invocation (schema, When, guards). ``atomic=True``: one
        transaction, one correlation id — all items land or none do, and
        a refusal's report still carries every item's verdict.
        ``dry_run``: the full verdict report, always rolled back."""
        rdef = self._rdef(kind)
        defn = rdef.machine.actions.get(action_name)
        if defn is None or defn.batch_spec is None:
            raise NotFound(
                f"{rdef.kind} has no batch action {action_name!r}.")
        body = body or {}
        extras = set(body) - {"items"}
        if extras:
            raise SchemaInvalid(
                "Unexpected fields in batch body.",
                action_attempted=action_name,
                errors={k: ["unexpected field"] for k in sorted(extras)})
        items = body.get("items")
        if not isinstance(items, list) or not items or \
                not all(isinstance(i, dict) for i in items):
            raise SchemaInvalid(
                "Batch invocations require a non-empty `items` array of "
                "input objects.",
                action_attempted=action_name,
                errors={"items": ["required, non-empty array of input "
                                  "objects"]})
        spec = defn.batch_spec
        if len(items) > spec.max_items:
            raise SchemaInvalid(
                f"At most {spec.max_items} items per batch.",
                action_attempted=action_name,
                errors={"items": [f"maxItems is {spec.max_items}"]})
        digest = body_digest(body)
        if not dry_run:
            if not defn.safety.idempotent and idempotency_key is None:
                raise IdempotencyKeyRequired(
                    f"Batch action {action_name!r} is not idempotent; send "
                    "an Idempotency-Key header covering the whole batch.",
                    action_attempted=action_name)
            if idempotency_key is not None:
                async with self.storage.session() as s:
                    hit = await self.idem.lookup(
                        s, idempotency_key, action=f"batch:{action_name}",
                        digest=digest)
                if hit:  # replay the stored report, byte for byte
                    return InvokeResult(status=hit.status, body=hit.body,
                                        media_type=hit.media_type)

        correlation_id = uuid.uuid4().hex
        if dry_run or spec.atomic:
            verdicts, committed = await self._batch_atomic(
                rdef, defn, id, items, principal, correlation_id, locale,
                acknowledged, dry_run=dry_run)
            if dry_run:
                doc: dict[str, Any] = {
                    "valid": all(v["verdict"] == "ok" for v in verdicts),
                    "verdicts": verdicts,
                }
                return InvokeResult(status=200, body=_to_bytes(doc),
                                    media_type="application/json", doc=doc)
            if not committed:
                refused = sum(1 for v in verdicts
                              if v["verdict"] == "refused")
                failed = sum(1 for v in verdicts if v["verdict"] == "failed")
                raise GuardRefused(
                    f"Atomic batch {action_name!r} aborted: {refused} "
                    "item(s) refused; nothing committed.",
                    action_attempted=action_name,
                    report={"succeeded": 0, "refused": refused,
                            "failed": failed},
                    verdicts=verdicts)
            report: dict[str, Any] = {
                "succeeded": len(items), "refused": 0, "failed": 0,
                "refusals": [], "verdicts": verdicts,
            }
        else:
            report = await self._batch_partial(
                rdef, defn, id, items, principal, correlation_id, locale,
                acknowledged)
        doc = {
            "kind": "bulk_report",
            "action": action_name,
            "data": report,
            "links": {"target": {"href": self._item_href(rdef, id),
                                 "kind": rdef.kind}},
        }
        result = _result(doc)
        if idempotency_key is not None:
            async with self.storage.session() as s:
                await self.idem.store(
                    s, idempotency_key, kind=kind,
                    action=f"batch:{action_name}", digest=digest, status=200,
                    body=result.body, media_type=result.media_type,
                    at=self.clock())
        return result

    async def _batch_atomic(self, rdef: ResourceDef, defn: ActionDef,
                            id: str, items: list[dict[str, Any]],
                            principal: Principal, correlation_id: str,
                            locale: str, acknowledged: frozenset[str], *,
                            dry_run: bool) -> tuple[list[dict[str, Any]], bool]:
        """One transaction for all items. Validation of every item
        completes even when commitment doesn't: a refusal is recorded and
        the pass continues, so the report can show all the problems at
        once — then the whole transaction rolls back (design §7)."""
        verdicts: list[dict[str, Any]] = []
        committed = False
        try:
            async with self._flip_session() as s:
                poisoned: str | None = None
                for index, item in enumerate(items):
                    if poisoned is not None:
                        # a storage-level abort poisons the transaction;
                        # later items cannot honestly be judged in it
                        verdicts.append({"index": index, "verdict": "failed",
                                         "reason": poisoned})
                        continue
                    try:
                        await self._invoke_in_session(
                            s, rdef.kind, id, defn.name, item,
                            principal=principal, if_match=None,
                            idempotency_key=None, dry_run=False,
                            locale=locale, correlation_id=correlation_id,
                            require_key=False, acknowledged=acknowledged)
                        verdicts.append({"index": index, "verdict": "ok"})
                    except UniqueViolation as exc:
                        verdicts.append({"index": index, "verdict": "refused",
                                         "reason": str(exc)})
                        poisoned = (f"not evaluated: item {index} aborted "
                                    "the transaction")
                    except Problem as exc:
                        verdicts.append({"index": index, "verdict": "refused",
                                         "reason": exc.detail})
                ok = all(v["verdict"] == "ok" for v in verdicts)
                if dry_run or not ok:
                    raise _Rollback()
                if defn.draft and self.draft_store is not None:
                    # the staged batch landed; the draft has served
                    from .drafts import audience_of

                    await self.draft_store.consume(
                        s, rdef, defn, id, "", audience_of(defn, principal))
                committed = True
        except _Rollback:
            pass
        await self._settle_effects(correlation_id, committed=committed)
        return verdicts, committed

    async def _batch_partial(self, rdef: ResourceDef, defn: ActionDef,
                             id: str, items: list[dict[str, Any]],
                             principal: Principal, correlation_id: str,
                             locale: str,
                             acknowledged: frozenset[str]) -> dict[str, Any]:
        """Per-item commits with partial success — today's bulk shape,
        item-indexed."""
        report: dict[str, Any] = {"succeeded": 0, "refused": 0, "failed": 0,
                                  "refusals": [], "verdicts": []}
        for index, item in enumerate(items):
            try:
                async with self._flip_session() as s:  # one txn per item
                    await self._invoke_in_session(
                        s, rdef.kind, id, defn.name, item,
                        principal=principal, if_match=None,
                        idempotency_key=None, dry_run=False, locale=locale,
                        correlation_id=correlation_id, require_key=False,
                        acknowledged=acknowledged)
                await self._settle_effects(correlation_id, committed=True)
                report["succeeded"] += 1
                report["verdicts"].append({"index": index, "verdict": "ok"})
            except UniqueViolation as exc:
                await self._settle_effects(correlation_id, committed=False)
                report["refused"] += 1
                report["refusals"].append(
                    {"index": index, "self": self._item_href(rdef, id),
                     "reason": str(exc)})
                report["verdicts"].append(
                    {"index": index, "verdict": "refused",
                     "reason": str(exc)})
            except Problem as exc:
                await self._settle_effects(correlation_id, committed=False)
                report["refused"] += 1
                report["refusals"].append(
                    {"index": index, "self": self._item_href(rdef, id),
                     "reason": exc.detail})
                report["verdicts"].append(
                    {"index": index, "verdict": "refused",
                     "reason": exc.detail})
            except Exception:  # noqa: BLE001 — partial-success must not abort
                await self._settle_effects(correlation_id, committed=False)
                report["failed"] += 1
                report["verdicts"].append(
                    {"index": index, "verdict": "failed",
                     "reason": "Internal error while processing this item."})
        if report["refused"] == 0 and report["failed"] == 0 \
                and defn.draft and self.draft_store is not None:
            from .drafts import audience_of

            async with self.storage.session() as s:
                await self.draft_store.consume(
                    s, rdef, defn, id, "", audience_of(defn, principal))
        return report

    # ── job leases (design E6): one live worker per running job ─────────
    async def _claim_job_lease(self, job_id: str) -> bool:
        now = self.clock()
        async with self.storage.session() as s:
            return await self.storage.claim_job_lease(
                s, job_id, self.worker_id,
                now + timedelta(seconds=JOB_LEASE_TTL_SECONDS), now)

    async def _renew_job_lease(self, job_id: str) -> None:
        async with self.storage.session() as s:
            await self.storage.renew_job_lease(
                s, job_id, self.worker_id,
                self.clock() + timedelta(seconds=JOB_LEASE_TTL_SECONDS))

    async def _release_job_lease(self, job_id: str) -> None:
        async with self.storage.session() as s:
            await self.storage.release_job_lease(s, job_id, self.worker_id)

    async def _invoke_in_session_wrapper(self, kind: str, id: str, action: str,
                                         correlation_id: str,
                                         locale: str) -> None:
        from .jobs import SYSTEM

        try:
            async with self._flip_session() as s:
                await self._invoke_in_session(
                    s, kind, id, action, None, principal=SYSTEM,
                    if_match=None, idempotency_key=None, dry_run=False,
                    locale=locale, correlation_id=correlation_id,
                    require_key=False)
        except Problem:
            pass  # e.g. cancelled between items

    # ── helpers ─────────────────────────────────────────────────────────
    async def _conflict(self, exc: "UniqueViolation", *,
                        action: str) -> Conflict:
        """The already-exists Problem, enriched with the conflicting
        resource's href — the recovery the apps hand-built from error
        payloads, made hypermedia (design E2)."""
        rdef = self._rdef(exc.kind)
        existing = None
        try:
            async with self.storage.session() as s:
                rows, _ = await self.storage.query(
                    s, exc.kind, filters=dict(exc.values), sort=None,
                    page_size=1, page_number=1)
            if rows:
                existing = {"href": f"{self.base}/{rdef.plural}/{rows[0].id}",
                            "id": rows[0].id}
        except Exception:  # noqa: BLE001 — the link is garnish, not the refusal
            existing = None
        taken = ", ".join(f"{f}={exc.values.get(f)!r}" for f in exc.fields)
        return Conflict(
            f"A {exc.kind} with the same {' + '.join(exc.fields)} already "
            f"exists ({taken}).",
            action_attempted=action, fields=list(exc.fields),
            existing=existing)

    def _rdef(self, kind: str) -> ResourceDef:
        rdef = self.registry.get(kind)
        if rdef is None:
            raise NotFound(f"Unknown resource kind {kind!r}.")
        return rdef

    async def _seed_owned(self, s: Any, instance: Resource, rdef: ResourceDef,
                          ctx: Ctx) -> None:
        """Declared seeds (design E4): one child per matching source row,
        created through ctx.create — same transaction, same correlation,
        the creating actor's own audit entries."""
        from ..core.owns import owns_of

        for edge in owns_of(rdef.cls):
            if edge.seed is None:
                continue
            fmt = _SummaryFormatter(instance)
            filters = {k: (fmt.vformat(v, (), {})
                           if isinstance(v, str) and "{" in v else v)
                       for k, v in edge.seed.where.items()}
            page = 1
            while True:
                rows, _ = await self.storage.query(
                    s, edge.seed.kind, filters=filters, sort=None,
                    page_size=200, page_number=page)
                for source in rows:
                    dump = source.data.model_dump(mode="json")
                    body = {edge.via: instance.id,
                            **{cf: dump[sf]
                               for cf, sf in edge.seed.copy.items()},
                            **dict(edge.seed.defaults)}
                    await ctx.create(edge.kind, body)
                if len(rows) < 200:
                    return
                page += 1

    async def _resolve_predecessors(self, instance: Resource,
                                    ctx: Ctx) -> None:
        """Predecessor refs (design E7): the latest sibling by declared
        order (within the declared partition) becomes data. A supplied
        value wins; ties on order break toward the newest row."""
        from ..core.refs import ref_meta, ref_predecessor

        data = instance.data
        dump = None
        # the declaration home is Data — a Create model may re-declare the
        # field (to hide it from the form) without carrying the opts
        for fname, f in type(instance).Data.model_fields.items():
            pred = ref_predecessor(f)
            if pred is None or getattr(data, fname, None) is not None:
                continue
            meta = ref_meta(f)
            if dump is None:
                dump = data.model_dump(mode="json")
            filters: dict[str, Any] = {f"{pred.order}_lte": dump[pred.order]}
            if pred.partition is not None:
                filters[pred.partition] = dump[pred.partition]
            siblings, _ = await self.storage.query(
                ctx.session, meta.kind, filters=filters,
                sort=f"-{pred.order}", page_size=1, page_number=1)
            if siblings:
                setattr(data, fname, siblings[0].id)

    async def _maintain_ref_labels(self, instance: Resource,
                                   before: dict[str, Any] | None,
                                   ctx: Ctx) -> None:
        """Denormalized Ref labels are generated, not hand-copied (design
        §4): whenever a write changes a labeled Ref — on the data root or
        inside a list-of-model field (the level parts live at) — the engine
        reads the target and writes its declared label. ``before=None``
        (create) treats every set ref as changed."""
        from ..core.groups import _item_models

        data = instance.data
        await self._labels_for_model(data, before or {}, ctx)
        for field, item_cls in _item_models(type(data)).items():
            if not _labeled_refs(item_cls):
                continue
            prev_items = (before or {}).get(field) or []
            for i, item in enumerate(getattr(data, field) or []):
                prev = prev_items[i] if i < len(prev_items) else {}
                await self._labels_for_model(item, prev, ctx)

    async def _labels_for_model(self, model: BaseModel,
                                before: dict[str, Any], ctx: Ctx) -> None:
        for name, (kind, label_field) in _labeled_refs(type(model)).items():
            new_id = getattr(model, name, None)
            if new_id == before.get(name):
                continue
            if new_id is None:
                setattr(model, label_field, None)
                continue
            target = await ctx.read(kind, str(new_id))
            if target is None:
                continue  # dangling refs are the guards' problem, loudly
            template = _label_template(type(target))
            if template is None:
                continue
            setattr(model, label_field,
                    _SummaryFormatter(target).vformat(template, (), {}))

    def _validate(self, model: type[BaseModel], body: dict[str, Any] | None,
                  rdef: ResourceDef, action: ActionDef | None = None) -> BaseModel:
        body = body or {}
        if not isinstance(body, dict):
            raise SchemaInvalid("Request body must be a JSON object.",
                                errors={"_root": ["expected object"]})
        declared = set(model.model_fields)
        extras = {k for k in body if k not in declared}
        if extras:
            # additionalProperties: false, enforced even though the Pydantic
            # model itself may be lenient
            raise SchemaInvalid(
                "Unexpected fields in input.",
                action_attempted=action.name if action else None,
                errors={k: ["unexpected field"] for k in sorted(extras)})
        # field origins are exclusive (design §1): a derived field is the
        # maintainer's to write — a body that supplies one is refused with
        # the same sentence the schema's readOnly already advertised
        from ..core.derived import derived_specs

        supplied = sorted(k for k in body if k in derived_specs(model))
        if supplied:
            raise SchemaInvalid(
                "Input failed validation.",
                action_attempted=action.name if action else None,
                errors={k: ["read-only: derived — the engine computes this "
                            "value from its declared inputs"]
                        for k in supplied})
        # authored fields are the authority's to write (design §8): a body
        # that supplies one is refused with the sentence the schema's
        # readOnly + x-source: authored already advertised
        from ..core.authored import authored_specs

        aspecs = authored_specs(model)
        supplied = sorted(k for k in body if k in aspecs)
        if supplied:
            raise SchemaInvalid(
                "Input failed validation.",
                action_attempted=action.name if action else None,
                errors={k: [f"read-only: authored — the "
                            f"{aspecs[k].by} authority writes this value"]
                        for k in supplied})
        try:
            inst = model.model_validate(body)
        except ValidationError as exc:
            raise SchemaInvalid(
                "Input failed validation.",
                action_attempted=action.name if action else None,
                errors=validation_errors(exc)) from None
        # closed vocabularies (design §6): the declared member set the
        # schema advertises as items.enum is enforced here, for create and
        # action inputs alike — one declaration, two consumers
        from ..core.vocab import closed_vocab_errors

        vocab_errors = closed_vocab_errors(model, inst)
        if vocab_errors:
            raise SchemaInvalid(
                "Input failed validation.",
                action_attempted=action.name if action else None,
                errors=vocab_errors)
        return inst

    async def _refuse(self, s: Any, rdef: ResourceDef, instance: Resource,
                      defn: ActionDef, deny: Deny, denier: Any, ctx: Ctx) -> None:
        if denier.hide:
            raise NotFound(f"No {rdef.kind} {instance.id!r}.")
        reason = denier.render_reason(deny, instance)
        raise GuardRefused(
            reason,
            action_attempted=defn.name,
            state=instance.state,
            errors=deny.errors,
            remedies=list(denier.remedies) or None,
            becomes_available=denier.becomes_available(deny, instance),
            resource=await render(instance, rdef, ctx=ctx, depth="summary",
                                  base=self.base))

    async def _natural_replay(self, s: Any, rdef: ResourceDef, instance: Resource,
                              defn: ActionDef, digest: str,
                              ctx: Ctx) -> InvokeResult | None:
        """Replay-safety for idempotent actions: if the latest transition is
        this same action with this same input and the state matches its
        outcome, return the current document unchanged."""
        if not defn.safety.idempotent or instance.state != defn.to:
            return None
        last = await self.storage.last_transition(s, rdef.kind, instance.id)
        if last is None or last.action != defn.name or last.input_digest != digest:
            return None
        doc = await render(instance, rdef, ctx=ctx, base=self.base)
        return _result(doc)

    async def _child_read(self, resource: Any, id: str, *, ctx: Ctx) -> Resource | None:
        kind = resource if isinstance(resource, str) else resource.kind
        return await self.storage.load(ctx.session, kind, id)

    async def _child_actor_of(self, resource: Any, id: str, action: str, *,
                              ctx: Ctx) -> str | None:
        """ctx.actor_of: the transition-log read four-eyes guards make (E3)."""
        kind = resource if isinstance(resource, str) else resource.kind
        return await self.storage.transition_actor(ctx.session, kind, id,
                                                   action)

    async def _child_find(self, resource: Any, filters: dict[str, Any], *,
                          sort: str | None, limit: int,
                          ctx: Ctx) -> list[Resource]:
        """ctx.find: the list half of cross-resource reads."""
        kind = resource if isinstance(resource, str) else resource.kind
        items, _total = await self.storage.query(
            ctx.session, kind, filters=filters, sort=sort,
            page_size=limit, page_number=1)
        return items

    async def _child_invoke(self, resource: Any, id: str, action: str,
                            body: dict[str, Any] | None, *, ctx: Ctx,
                            if_match: str | None = None) -> dict[str, Any]:
        """ctx.invoke: child transition in the same txn + correlation."""
        from ..core.touches import allows_advance

        kind = resource if isinstance(resource, str) else resource.kind
        scope = ctx._touch_scope
        if scope is not None and not allows_advance(scope[1], kind, action):
            from ..core.types import DefinitionError

            raise DefinitionError(
                f"action {scope[0]} invoked {kind}.{action} via ctx.invoke "
                f"but declares no Advances({kind!r}, {action!r}) — a "
                "transition's writes may not out-run its advertisement "
                "(design E8)")
        result = await self._invoke_in_session(
            ctx.session, kind, id, action, body, principal=ctx.principal,
            if_match=if_match, idempotency_key=None, dry_run=False,
            locale=ctx.locale, correlation_id=ctx.correlation_id,
            require_key=False,  # the enclosing invocation's txn is the retry unit
        )
        assert result.doc is not None
        return result.doc
