"""Attachments (design E5): metadata is a resource, bytes ride dedicated
routes against a declared BlobStore, and deletion follows the engine's
declared retention. Replaces base64-through-JSON transports and the
"soft delete" that destroyed bytes.
"""
from __future__ import annotations

import hashlib
import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark5
from waymark5 import Ctx, Resource, Safety, action
from waymark5.server.attachments import MemoryBlobStore
from waymark5.server.bus import InProcessBus
from waymark5.server.engine import header_principal
from waymark5.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

PDF = b"%PDF-1.4 pretend statement bytes"


class DocState(StrEnum):
    OPEN = "open"


class DocData(BaseModel):
    title: str = Field(min_length=1, max_length=120)


class Doc(Resource):
    kind = "doc"
    State = DocState
    Data = DocData
    initial = DocState.OPEN
    terminal: set = set()
    summary = "{data.title} · {state.label}"

    @action(from_=DocState.OPEN, to=DocState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass


def _env_factory(retention: str):
    @pytest.fixture
    async def env():
        blobs = MemoryBlobStore()
        engine = waymark5.Engine(resources=[Doc], storage=TEST_DSN,
                                 principal=header_principal, services=None,
                                 bus=InProcessBus(), blobs=blobs,
                                 blob_retention=retention)
        await engine.storage.drop_all()
        await engine.startup()
        app = FastAPI()
        app.include_router(engine.router, prefix="/api")
        client = AsyncClient(transport=ASGITransport(app=app),
                             base_url="http://t",
                             headers={"X-Principal-Id": "nora"})
        try:
            yield engine, client, blobs
        finally:
            await client.aclose()
            await engine.shutdown()
    return env


env = _env_factory("purge")
env_keep = _env_factory("keep")


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _reserved(client) -> dict:
    doc = (await _post(client, "/api/docs", {"title": "July wire"})).json()
    res = await _post(client, "/api/attachments", {
        "resource_kind": "doc", "resource_id": doc["self"].rsplit("/", 1)[-1],
        "name": "statement.pdf", "mime": "application/pdf"})
    assert res.status_code == 201, res.text
    return res.json()


async def test_reserve_upload_download_roundtrip(env):
    """Two-phase upload: reserve is a create, the bytes PUT stamps
    size/sha via a system transition, download serves the declared mime."""
    engine, client, blobs = env
    att = await _reserved(client)
    assert att["state"] == "reserved"

    res = await client.put(f"{att['self']}/bytes", content=PDF)
    assert res.status_code == 200, res.text
    doc = res.json()
    assert doc["state"] == "uploaded"
    assert doc["data"]["size"] == len(PDF)
    assert doc["data"]["sha256"] == hashlib.sha256(PDF).hexdigest()

    att_id = att["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "attachment", att_id)
    assert last.action == "mark_uploaded"
    assert last.actor_type == "system"

    got = await client.get(f"{att['self']}/bytes")
    assert got.status_code == 200
    assert got.content == PDF
    assert got.headers["content-type"].startswith("application/pdf")

    # bytes are written once; replacing means a fresh reservation
    again = await client.put(f"{att['self']}/bytes", content=b"other")
    assert again.status_code == 409, again.text


async def test_dangling_target_is_refused_at_create(env):
    engine, client, blobs = env
    res = await _post(client, "/api/attachments", {
        "resource_kind": "doc", "resource_id": "nope",
        "name": "x.pdf", "mime": "application/pdf"})
    assert res.status_code == 409, res.text
    assert "create the resource first" in res.json()["detail"]


async def test_bytes_of_unuploaded_attachments_404(env):
    engine, client, blobs = env
    att = await _reserved(client)
    assert (await client.get(f"{att['self']}/bytes")).status_code == 404


async def _eventually(check, *, timeout=5.0):
    import asyncio

    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        if await check():
            return
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError("condition not reached in time")
        await asyncio.sleep(0.05)


async def test_purge_retention_deletes_bytes_on_remove(env):
    """Purging rides the log (design E5): the janitor deletes after
    commit, whatever path invoked the remove."""
    engine, client, blobs = env
    att = await _reserved(client)
    await client.put(f"{att['self']}/bytes", content=PDF)
    att_id = att["self"].rsplit("/", 1)[-1]

    res = await _post(client, f"{att['self']}/-/remove")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "removed"

    async def purged():
        return await blobs.get(att_id) is None
    await _eventually(purged)
    assert (await client.get(att["self"])).json()["state"] == "removed", \
        "the metadata row remains as the audited record"


async def test_keep_retention_retains_bytes(env_keep):
    engine, client, blobs = env_keep
    att = await _reserved(client)
    await client.put(f"{att['self']}/bytes", content=PDF)
    att_id = att["self"].rsplit("/", 1)[-1]
    await _post(client, f"{att['self']}/-/remove")
    import asyncio

    await asyncio.sleep(0.3)  # long enough for a janitor that shouldn't run
    assert await blobs.get(att_id) == PDF, "keep means kept"


async def _uploaded(client) -> dict:
    att = await _reserved(client)
    res = await client.put(f"{att['self']}/bytes", content=PDF)
    assert res.status_code == 200, res.text
    return res.json() | {"self": att["self"]}


async def _second_doc(client) -> str:
    doc = (await _post(client, "/api/docs", {"title": "Carved child"})).json()
    return doc["self"].rsplit("/", 1)[-1]


async def _attachments_of(client, target_id: str) -> list[dict]:
    listing = (await client.get(
        f"/api/attachments?resource_kind=doc&resource_id={target_id}")).json()
    return listing["data"]["items"]


async def test_duplicate_follows_the_child(env):
    """Duplication (design E8): the metadata copy rides the duplicate
    invocation's txn + correlation; the bytes follow post-commit via the
    blob-copy consumer, stamped by the system actor."""
    engine, client, blobs = env
    att = await _uploaded(client)
    att_id = att["self"].rsplit("/", 1)[-1]
    child_id = await _second_doc(client)

    res = await _post(client, f"{att['self']}/-/duplicate",
                      {"resource_kind": "doc", "resource_id": child_id})
    assert res.status_code == 200, res.text

    items = await _attachments_of(client, child_id)
    assert len(items) == 1
    copy = items[0]
    copy_id = copy["self"].rsplit("/", 1)[-1]
    assert copy_id != att_id

    async def uploaded():
        return (await client.get(copy["self"])).json()["state"] == "uploaded"
    await _eventually(uploaded)

    doc = (await client.get(copy["self"])).json()
    assert doc["data"]["size"] == len(PDF)
    assert doc["data"]["sha256"] == hashlib.sha256(PDF).hexdigest()
    got = await client.get(f"{copy['self']}/bytes")
    assert got.status_code == 200
    assert got.content == PDF

    # the copy's create shares the duplicate invocation's correlation;
    # its mark_uploaded is the system actor's, like any upload
    async with engine.storage.session() as s:
        dup = await engine.storage.last_transition(s, "attachment", att_id)
        assert dup.action == "duplicate"
        story = await engine.storage.transitions_by_correlation(
            s, dup.correlation_id)
        marked = await engine.storage.last_transition(s, "attachment",
                                                      copy_id)
    assert any(t.kind == "attachment" and t.resource_id == copy_id
               and t.action == "create" for t in story)
    assert marked.action == "mark_uploaded"
    assert marked.actor_type == "system"


async def test_purging_the_original_leaves_the_copy_intact(env):
    """The copy owns its bytes: removing the original under purging
    retention deletes only the original's blob."""
    engine, client, blobs = env
    att = await _uploaded(client)
    att_id = att["self"].rsplit("/", 1)[-1]
    child_id = await _second_doc(client)
    await _post(client, f"{att['self']}/-/duplicate",
                {"resource_kind": "doc", "resource_id": child_id})
    copy = (await _attachments_of(client, child_id))[0]
    copy_id = copy["self"].rsplit("/", 1)[-1]

    async def copy_uploaded():
        return await blobs.get(copy_id) is not None
    await _eventually(copy_uploaded)

    await _post(client, f"{att['self']}/-/remove")

    async def original_purged():
        return await blobs.get(att_id) is None
    await _eventually(original_purged)
    assert await blobs.get(copy_id) == PDF


async def test_duplicate_onto_dangling_target_refuses(env):
    """The copy is an ordinary create: its on_create dangling-target check
    aborts the whole duplicate invocation — nothing is minted."""
    engine, client, blobs = env
    att = await _uploaded(client)
    res = await _post(client, f"{att['self']}/-/duplicate",
                      {"resource_kind": "doc", "resource_id": "nope"})
    assert res.status_code == 409, res.text
    assert "create the resource first" in res.json()["detail"]
    assert await _attachments_of(client, "nope") == []


async def test_duplicate_of_purged_bytes_stays_reserved(env):
    """A source whose bytes are gone has nothing to copy: the copy stays
    honestly reserved, with no bytes minted from nowhere."""
    engine, client, blobs = env
    att = await _uploaded(client)
    att_id = att["self"].rsplit("/", 1)[-1]
    await blobs.delete(att_id)  # the purged-source shape, metadata intact
    child_id = await _second_doc(client)

    res = await _post(client, f"{att['self']}/-/duplicate",
                      {"resource_kind": "doc", "resource_id": child_id})
    assert res.status_code == 200, res.text
    copy = (await _attachments_of(client, child_id))[0]
    copy_id = copy["self"].rsplit("/", 1)[-1]
    assert copy["state"] == "reserved"

    import asyncio

    await asyncio.sleep(0.4)  # long enough for a copier that must not act
    assert (await client.get(copy["self"])).json()["state"] == "reserved"
    assert await blobs.get(copy_id) is None


async def test_attachments_are_queryable_by_target(env):
    engine, client, blobs = env
    att = await _reserved(client)
    target_id = att["data"]["resource_id"]
    listing = (await client.get(
        f"/api/attachments?resource_kind=doc&resource_id={target_id}")).json()
    assert listing["data"]["total"] == 1
    assert listing["data"]["items"][0]["self"] == att["self"]
