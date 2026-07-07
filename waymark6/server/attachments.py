"""Attachments (design E5): metadata is a resource, bytes live behind a
blob store, upload is a two-phase transition.

The mapping series' only outright fail, three apps deep: timer-sequenced
S3 uploads, base64 files round-tripping through JSON responses, and a
"soft delete" that permanently destroyed the bytes. Here the attachment
row is ordinary Waymark — audited transitions, guards, visibility — and
the bytes ride two dedicated routes (``PUT/GET …/bytes``) against a
declared :class:`BlobStore`. Deletion semantics are the engine's to
declare (``blob_retention="purge" | "keep"``), so what happens to the
bytes is a reviewed decision, not a handler's accident.
"""
from __future__ import annotations

import hashlib
from enum import StrEnum
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from ..core.actions import Edit, action
from ..core.resource import Resource, filterable
from ..core.touches import Creates
from ..core.types import Acknowledged, Ctx, Principal, Safety
from .consumers import LogConsumer
from .external import system_only

BYTES_ACTOR = Principal(id="attachment-bytes", type="system",
                        display="Attachment bytes")


@runtime_checkable
class BlobStore(Protocol):
    """Where an attachment's bytes live, keyed by attachment id. The
    metadata resource is the truth about them; this is only storage."""

    async def put(self, id: str, data: bytes) -> None: ...

    async def get(self, id: str) -> bytes | None: ...

    async def delete(self, id: str) -> None: ...

    async def copy(self, src: str, dst: str) -> None: ...


class MemoryBlobStore:
    """The dev/test store (the dev-principal precedent): bytes die with
    the process. Production wires a real store."""

    def __init__(self) -> None:
        self._blobs: dict[str, bytes] = {}

    async def put(self, id: str, data: bytes) -> None:
        self._blobs[id] = data

    async def get(self, id: str) -> bytes | None:
        return self._blobs.get(id)

    async def delete(self, id: str) -> None:
        self._blobs.pop(id, None)

    async def copy(self, src: str, dst: str) -> None:
        # a purged source has nothing to copy; dst stays absent (design E8)
        data = await self.get(src)
        if data is not None:
            await self.put(dst, data)


class FileBlobStore:
    """Bytes as files under one root, named by attachment id."""

    def __init__(self, root: str | Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, id: str) -> Path:
        safe = "".join(c for c in id if c.isalnum() or c in "-_")
        return self.root / safe

    async def put(self, id: str, data: bytes) -> None:
        self._path(id).write_bytes(data)

    async def get(self, id: str) -> bytes | None:
        p = self._path(id)
        return p.read_bytes() if p.exists() else None

    async def delete(self, id: str) -> None:
        self._path(id).unlink(missing_ok=True)

    async def copy(self, src: str, dst: str) -> None:
        # a purged source has nothing to copy; dst stays absent (design E8)
        data = await self.get(src)
        if data is not None:
            await self.put(dst, data)


class AttachmentState(StrEnum):
    RESERVED = "reserved"    # metadata exists; bytes not yet received
    UPLOADED = "uploaded"
    REMOVED = "removed"


class AttachmentData(BaseModel):
    resource_kind: str = Field(min_length=1, max_length=64,
                               description="The kind of the resource this "
                                           "file supports")
    resource_id: str = Field(min_length=1, max_length=64,
                             json_schema_extra={"x-display": {"raw": True}})
    name: str = Field(min_length=1, max_length=160,
                      description="The file's human name (shown, downloaded)")
    mime: str = Field(max_length=100, pattern=r"^[\w.+-]+/[\w.+-]+$",
                      description="Content type served back on download")
    # stamped by the bytes route's system transition; never supplied by hand
    size: int | None = Field(default=None, ge=0,
                             json_schema_extra={"x-display": {"raw": True}})
    sha256: str | None = Field(default=None, max_length=64,
                               json_schema_extra={"x-display": {"raw": True}})
    notes: str | None = Field(default=None, max_length=240)
    # set by the duplicate handler, never by a form: which attachment's
    # bytes this one carries (design E8 — evidence follows the carve-out)
    copied_from: str | None = Field(
        default=None, max_length=64,
        json_schema_extra={"x-display": {"hidden": True}})


class AttachmentCreate(AttachmentData):
    """Reserve: name the target and the file; the bytes come by PUT."""

    size: SkipJsonSchema[int | None] = None
    sha256: SkipJsonSchema[str | None] = None
    # SkipJsonSchema hides the field from the rendered form while keeping
    # it in model_fields, so the duplicate handler's ctx.create may set it
    copied_from: SkipJsonSchema[str | None] = None


class UploadedInput(BaseModel):
    size: int = Field(ge=0)
    sha256: str = Field(min_length=64, max_length=64,
                        pattern=r"^[0-9a-f]{64}$")


class DuplicateInput(BaseModel):
    """The new target: where the copy's evidence should live."""

    resource_kind: str = Field(min_length=1, max_length=64)
    resource_id: str = Field(min_length=1, max_length=64,
                             json_schema_extra={"x-display": {"raw": True}})


class Attachment(Resource):
    kind = "attachment"
    State = AttachmentState
    Data = AttachmentData
    Create = AttachmentCreate

    initial = AttachmentState.RESERVED
    terminal = {AttachmentState.REMOVED}

    summary = "{data.name} · {data.mime} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        resource_kind=filterable.Eq,
        resource_id=filterable.Eq,
    )

    display = {"title": "Attachment — {data.name}"}

    label_template = "{data.name}"

    async def on_create(self, ctx: Ctx) -> None:
        # an attachment to nothing is the `event_id`-into-the-void scar;
        # refuse it at the source
        from .problems import GuardRefused

        try:
            target = await ctx.read(self.data.resource_kind,
                                    self.data.resource_id)
        except Exception:
            target = None
        if target is None:
            raise GuardRefused(
                f"No {self.data.resource_kind} {self.data.resource_id!r} to "
                "attach to — create the resource first.",
                action_attempted="create")

    @action(from_=AttachmentState.RESERVED, to=AttachmentState.UPLOADED,
            input=UploadedInput, guards=[system_only],
            edit=Edit(prefill=("size", "sha256"), fence=False,
                      unfenced_reason="Written once by the bytes route at "
                                      "upload; there is no human form to "
                                      "clobber."),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Recorded by the bytes route once the store "
                              "accepted the upload; nothing is lost.")),
            display=dict(label="Mark uploaded", order=5))
    async def mark_uploaded(self, inp: UploadedInput, ctx: Ctx) -> None:
        self.data.size = inp.size
        self.data.sha256 = inp.sha256

    @action(from_=AttachmentState.UPLOADED, to=AttachmentState.UPLOADED,
            input=DuplicateInput,
            touches=(Creates("attachment"),),
            edit=Edit(fence=False,
                      unfenced_reason="Not an edit: the input names a NEW "
                                      "target for the copy, so the form is "
                                      "deliberately blank and there is no "
                                      "snapshot to fence."),
            safety=Safety(idempotent=False, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Duplication mints a new attachment; the "
                              "original is untouched.")),
            display=dict(label="Duplicate for"))
    async def duplicate(self, inp: DuplicateInput, ctx: Ctx) -> None:
        # the metadata half of a carve-out's evidence (design E8): the new
        # row rides this invocation's txn + correlation; its on_create
        # dangling-target check refuses a copy onto nothing. The bytes
        # follow post-commit via BlobCopier.
        await ctx.create("attachment", {
            "resource_kind": inp.resource_kind,
            "resource_id": inp.resource_id,
            "name": self.data.name,
            "mime": self.data.mime,
            "notes": self.data.notes,
            "copied_from": self.id,
        })

    @action(from_={AttachmentState.RESERVED, AttachmentState.UPLOADED},
            to=AttachmentState.REMOVED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The attachment disappears from its "
                                      "resource; under a purging retention "
                                      "policy the bytes are deleted for "
                                      "good."),
            display=dict(label="Remove", style="danger", order=9))
    async def remove(self, inp: None, ctx: Ctx) -> None:
        pass


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class BlobJanitor(LogConsumer):
    """Purging retention rides the log (design E5): every ``remove`` —
    whatever path invoked it (router, ctx.invoke, an approval's run) —
    purges after commit, durably behind a cursor. The metadata row stays
    as the audited record; only the bytes go."""

    consumer = "blob-purge"
    kinds = frozenset({"attachment"})

    async def handle(self, t: Any) -> None:
        if t.action == "remove" and self.engine.blobs is not None:
            await self.engine.blobs.delete(t.resource_id)


class BlobCopier(LogConsumer):
    """Duplication's byte half rides the log (design E5/E8): the metadata
    copy is the handler's ``Creates("attachment")``; the bytes follow
    post-commit, durably behind a cursor — never mid-transaction, so an
    aborted carve-out leaves no orphaned blob to compensate. A purged
    source has nothing to copy: the copy honestly stays ``reserved``."""

    consumer = "blob-copy"
    kinds = frozenset({"attachment"})

    async def handle(self, t: Any) -> None:
        if t.action != "create" or self.engine.blobs is None:
            return
        storage = self.engine.storage
        async with storage.session() as s:
            copy = await storage.load(s, "attachment", t.resource_id)
        if copy is None or copy.state != "reserved" \
                or not copy.data.copied_from:
            return
        src_id = copy.data.copied_from
        await self.engine.blobs.copy(src_id, t.resource_id)
        data = await self.engine.blobs.get(t.resource_id)
        if data is None:
            return  # source bytes purged; reserved is the honest state
        async with storage.session() as s:
            src = await storage.load(s, "attachment", src_id)
        if src is not None and src.data.size is not None \
                and src.data.sha256 is not None:
            # the copy preserves bytes: the source's measurements are truth
            size, sha = src.data.size, src.data.sha256
        else:
            size, sha = len(data), sha256_hex(data)
        from .invoke import make_etag

        await self.engine.invoker.invoke(
            "attachment", t.resource_id, "mark_uploaded",
            {"size": size, "sha256": sha},
            principal=BYTES_ACTOR,
            if_match=make_etag("attachment", t.resource_id, copy.version),
            correlation_id=t.correlation_id)
