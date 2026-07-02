"""The transition algorithm (§11.3): every write in the application is this.

Ordering note: idempotency replay happens *before* the state check — the spec
pseudocode places it after guards, but a stored replay must win even though
the first execution already moved the state (§7.3: byte-for-byte replay on
retry). Guards, state and version checks run only for genuinely new requests.

Idempotent actions are additionally *naturally* replayed: if the resource's
latest transition is this same action with this same input digest and the
state matches its outcome, the invocation returns the current document
without re-running the handler — double-invoke is replay-safe by
construction, which the conformance suite verifies by observation.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Callable

from pydantic import BaseModel, ValidationError

from .. import MEDIA_TYPE
from ..core.actions import ActionDef
from ..core.registry import Registry, ResourceDef
from ..core.resource import Resource
from ..core.summary import render_summary
from ..core.types import Ctx, Deny, Principal
from .idempotency import IdempotencyStore, body_digest
from .problems import (
    GuardRefused,
    IdempotencyKeyRequired,
    NotFound,
    Problem,
    SchemaInvalid,
    VersionConflict,
    WrongState,
)
from .render import _out_of_state_entry, make_etag, render


@dataclass(frozen=True)
class InvokeResult:
    status: int
    body: bytes
    media_type: str
    doc: dict[str, Any] | None = None


def _to_bytes(doc: dict[str, Any]) -> bytes:
    return json.dumps(doc, separators=(",", ":"), ensure_ascii=False).encode()


def _result(doc: dict[str, Any], status: int = 200) -> InvokeResult:
    return InvokeResult(status=status, body=_to_bytes(doc),
                        media_type=MEDIA_TYPE, doc=doc)


def validation_errors(exc: ValidationError) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for err in exc.errors():
        field = ".".join(str(p) for p in err["loc"]) or "_root"
        out.setdefault(field, []).append(err["msg"])
    return out


class Invoker:
    def __init__(self, *, registry: Registry, storage: Any,
                 services: Any = None, base: str = "/api",
                 clock: Callable[[], datetime] | None = None):
        self.registry = registry
        self.storage = storage
        self.idem = IdempotencyStore(storage)
        self.services = services
        self.base = base
        self.clock = clock or (lambda: datetime.now(UTC))
        self._job_tasks: set[Any] = set()

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
                   _invoker=self._child_invoke, _reader=self._child_read)

    # ── public entry points ─────────────────────────────────────────────
    async def invoke(self, kind: str, id: str, action_name: str,
                     body: dict[str, Any] | None, *, principal: Principal,
                     if_match: str | None = None,
                     idempotency_key: str | None = None,
                     dry_run: bool = False, locale: str = "en") -> InvokeResult:
        async with self.storage.session() as s:
            return await self._invoke_in_session(
                s, kind, id, action_name, body, principal=principal,
                if_match=if_match, idempotency_key=idempotency_key,
                dry_run=dry_run, locale=locale,
                correlation_id=uuid.uuid4().hex,
            )

    async def create(self, kind: str, body: dict[str, Any] | None, *,
                     principal: Principal, idempotency_key: str | None = None,
                     locale: str = "en") -> InvokeResult:
        rdef = self._rdef(kind)
        digest = body_digest(body)
        async with self.storage.session() as s:
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
            data = self._validate(rdef.extra.get("create_model") or rdef.cls.Data,
                                  body, rdef)
            instance = rdef.cls(
                id=uuid.uuid4().hex, state=rdef.machine.initial, data=data,
                version=1, created_at=ctx.now, updated_at=ctx.now,
            )
            await self.storage.insert(s, kind, instance)
            summary = render_summary(rdef.summary_template, instance)
            await self.storage.append_transition(
                s, kind=kind, instance=instance, action="create",
                from_state="", principal=principal, input_digest=digest,
                summary=summary, at=ctx.now, correlation_id=ctx.correlation_id,
            )
            doc = await render(instance, rdef, ctx=ctx, base=self.base)
            result = _result(doc, status=201)
            await self.idem.store(
                s, idempotency_key, kind=kind, action="create", digest=digest,
                status=201, body=result.body, media_type=result.media_type,
                at=ctx.now)
            return result

    # ── the algorithm ───────────────────────────────────────────────────
    async def _invoke_in_session(
        self, s: Any, kind: str, id: str, action_name: str,
        body: dict[str, Any] | None, *, principal: Principal,
        if_match: str | None, idempotency_key: str | None, dry_run: bool,
        locale: str, correlation_id: str | None, require_key: bool = True,
        allow_bulk: bool = False,
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
            entry = _out_of_state_entry(defn, instance.state)
            raise WrongState(
                entry["reason"], action_attempted=action_name,
                state=instance.state,
                becomes_available=entry["becomes_available"],
                resource=await render(instance, rdef, ctx=ctx, depth="summary",
                                      base=self.base))

        if defn.safety.requires_if_match:
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
        elif body:
            raise SchemaInvalid(
                f"Action {action_name!r} takes an empty body.",
                action_attempted=action_name,
                errors={k: ["unexpected field"] for k in body})

        for g in defn.guards:
            verdict, denier = await g.evaluate(instance, inp, ctx)
            if isinstance(verdict, Deny):
                await self._refuse(s, rdef, instance, defn, verdict, denier, ctx)

        if dry_run:
            return InvokeResult(status=200, body=_to_bytes({"valid": True}),
                                media_type="application/json",
                                doc={"valid": True})

        if defn.safety.idempotent:
            natural = await self._natural_replay(s, rdef, instance, defn, digest, ctx)
            if natural is not None:
                return natural

        from_state = instance.state
        await defn.handler(instance, inp, ctx)
        instance.state = defn.to
        instance.version += 1
        instance.updated_at = ctx.now
        summary = render_summary(rdef.summary_template, instance)
        await self.storage.append_transition(
            s, kind=kind, instance=instance, action=action_name,
            from_state=from_state, principal=principal, input_digest=digest,
            summary=summary, at=ctx.now, correlation_id=ctx.correlation_id,
        )
        await self.storage.save(s, kind, instance,
                                expected_version=instance.version - 1)

        doc = await render(instance, rdef, ctx=ctx, base=self.base)
        result = _result(doc)
        if idempotency_key is not None:
            await self.idem.store(
                s, idempotency_key, kind=kind, action=action_name, digest=digest,
                status=200, body=result.body, media_type=result.media_type,
                at=ctx.now)
        return result

    # ── bulk (§7.4) ─────────────────────────────────────────────────────
    async def bulk(self, kind: str, action_name: str,
                   body: dict[str, Any] | None, *, principal: Principal,
                   idempotency_key: str | None = None,
                   locale: str = "en") -> InvokeResult:
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
                                          principal, correlation_id, locale)

        if defn.atomic:
            report = await self._bulk_atomic(rdef, defn, ids, item_body,
                                             principal, correlation_id, locale)
        else:
            report = await self._bulk_partial(rdef, defn, ids, item_body,
                                              principal, correlation_id, locale)
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
                         locale: str) -> None:
        # bulk actions run the same per-item algorithm; the bulk affordance
        # is just a fan-out (§5: guards evaluated per item)
        await self._invoke_in_session(
            s, rdef.kind, id, defn.name, item_body, principal=principal,
            if_match=None, idempotency_key=None, dry_run=False,
            locale=locale, correlation_id=correlation_id, require_key=False,
            allow_bulk=True)

    def _item_href(self, rdef: ResourceDef, id: str) -> str:
        return f"{self.base}/{rdef.plural}/{id}"

    async def _bulk_partial(self, rdef: ResourceDef, defn: ActionDef,
                            ids: list[str], item_body: dict[str, Any] | None,
                            principal: Principal, correlation_id: str,
                            locale: str) -> dict[str, Any]:
        report = {"succeeded": 0, "refused": 0, "failed": 0, "refusals": []}
        for id in ids:
            try:
                async with self.storage.session() as s:  # one txn per item
                    await self._bulk_item(s, rdef, defn, id, item_body,
                                          principal, correlation_id, locale)
                report["succeeded"] += 1
            except (GuardRefused, NotFound, VersionConflict, SchemaInvalid) as exc:
                report["refused"] += 1
                report["refusals"].append(
                    {"self": self._item_href(rdef, id), "reason": exc.detail})
            except Exception:  # noqa: BLE001 - partial-success must not abort
                report["failed"] += 1
                report["refusals"].append(
                    {"self": self._item_href(rdef, id),
                     "reason": "Internal error while processing this item."})
        return report

    async def _bulk_atomic(self, rdef: ResourceDef, defn: ActionDef,
                           ids: list[str], item_body: dict[str, Any] | None,
                           principal: Principal, correlation_id: str,
                           locale: str) -> dict[str, Any]:
        try:
            async with self.storage.session() as s:  # one txn for all items
                for id in ids:
                    await self._bulk_item(s, rdef, defn, id, item_body,
                                          principal, correlation_id, locale)
        except Problem as exc:
            raise GuardRefused(
                f"Atomic bulk action {defn.name!r} aborted: {exc.detail}",
                action_attempted=defn.name,
                refusals=[{"self": None, "reason": exc.detail}]) from exc
        return {"succeeded": len(ids), "refused": 0, "failed": 0, "refusals": []}

    async def _defer_bulk(self, rdef: ResourceDef, defn: ActionDef,
                          ids: list[str], item_body: dict[str, Any] | None,
                          principal: Principal, correlation_id: str,
                          locale: str) -> InvokeResult:
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
            await self.storage.append_transition(
                s, kind="job", instance=job, action="create", from_state="",
                principal=SYSTEM, input_digest="", at=now,
                summary=f"Job {job.id} · {defn.name} · 0/{len(ids)} processed · Queued",
                correlation_id=correlation_id)
            ctx = self._ctx(principal, s, locale=locale,
                            correlation_id=correlation_id, mode="probe")
            doc = await render(job, job_rdef, ctx=ctx, base=self.base)
        self.spawn(self._run_job(job.id, rdef, defn, ids, item_body,
                                 principal, correlation_id, locale))
        return _result(doc, status=202)

    async def _run_job(self, job_id: str, rdef: ResourceDef, defn: ActionDef,
                       ids: list[str], item_body: dict[str, Any] | None,
                       principal: Principal, correlation_id: str,
                       locale: str) -> None:
        from .jobs import SYSTEM

        await self._invoke_in_session_wrapper("job", job_id, "start",
                                              correlation_id, locale)
        report = {"succeeded": 0, "refused": 0, "failed": 0, "refusals": []}
        for id in ids:
            async with self.storage.session() as s:
                job = await self.storage.load(s, "job", job_id)
            if job is None or job.state == "cancelled":
                return
            try:
                async with self.storage.session() as s:
                    await self._bulk_item(s, rdef, defn, id, item_body,
                                          principal, correlation_id, locale)
                report["succeeded"] += 1
            except Problem as exc:
                report["refused"] += 1
                report["refusals"].append(
                    {"self": self._item_href(rdef, id), "reason": exc.detail})
            except Exception:  # noqa: BLE001
                report["failed"] += 1
            async with self.storage.session() as s:
                job = await self.storage.load(s, "job", job_id, for_update=True)
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

    async def _invoke_in_session_wrapper(self, kind: str, id: str, action: str,
                                         correlation_id: str,
                                         locale: str) -> None:
        from .jobs import SYSTEM

        try:
            async with self.storage.session() as s:
                await self._invoke_in_session(
                    s, kind, id, action, None, principal=SYSTEM,
                    if_match=None, idempotency_key=None, dry_run=False,
                    locale=locale, correlation_id=correlation_id,
                    require_key=False)
        except Problem:
            pass  # e.g. cancelled between items

    # ── helpers ─────────────────────────────────────────────────────────
    def _rdef(self, kind: str) -> ResourceDef:
        rdef = self.registry.get(kind)
        if rdef is None:
            raise NotFound(f"Unknown resource kind {kind!r}.")
        return rdef

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
        try:
            return model.model_validate(body)
        except ValidationError as exc:
            raise SchemaInvalid(
                "Input failed validation.",
                action_attempted=action.name if action else None,
                errors=validation_errors(exc)) from None

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
        """Replay-safety for idempotent actions (§7.3, Part III safety truth):
        if the latest transition is this same action with this same input and
        the state matches its outcome, return the current document unchanged."""
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

    async def _child_invoke(self, resource: Any, id: str, action: str,
                            body: dict[str, Any] | None, *, ctx: Ctx) -> dict[str, Any]:
        """ctx.invoke (§14): child transition in the same txn + correlation."""
        kind = resource if isinstance(resource, str) else resource.kind
        result = await self._invoke_in_session(
            ctx.session, kind, id, action, body, principal=ctx.principal,
            if_match=None, idempotency_key=None, dry_run=False,
            locale=ctx.locale, correlation_id=ctx.correlation_id,
            require_key=False,  # the enclosing invocation's txn is the retry unit
        )
        assert result.doc is not None
        return result.doc
