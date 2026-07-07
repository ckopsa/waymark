"""The role registry (design §9): policy names are resources, and a grant
may only name a role that exists and is active — the "typo'd role name
silently grants nobody" gap closes at the source.
"""
from __future__ import annotations

import os
import uuid

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark6
from waymark6.server.bus import InProcessBus
from waymark6.server.engine import header_principal
from waymark6.testing import per_worker_dsn

from .test_member_visibility import Note

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


@pytest.fixture
async def env():
    engine = waymark6.Engine(resources=[Note], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://t",
                         headers={"X-Principal-Id": "admin",
                                  "X-Principal-Display": "Admin"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json or {},
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _act(client, href, action, body=None):
    doc = (await client.get(href)).json()
    # the fence gate runs before guards; unfenced actions ignore the header
    headers = {"Idempotency-Key": uuid.uuid4().hex,
               "If-Match": doc["meta"]["etag"]}
    return await client.post(f"{href}/-/{action}", json=body, headers=headers)


async def test_role_lifecycle(env):
    """A role is an ordinary resource: create → retire ⇄ reactivate."""
    engine, client = env
    res = await _post(client, "/api/roles",
                      {"name": "reader", "description": "May read titles"})
    assert res.status_code == 201, res.text
    doc = res.json()
    assert doc["state"] == "active"
    assert doc["data"]["name"] == "reader"
    assert "retire" in doc["actions"]

    res = await _act(client, doc["self"], "retire")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "retired"

    res = await _act(client, doc["self"], "reactivate")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "active"


async def test_grant_to_unregistered_role_refused_at_create(env):
    """A grant naming no active role cannot exist even for a moment; the
    refusal teaches the fix (design §9: the silent-grant gap)."""
    engine, client = env
    res = await _post(client, "/api/grants",
                      {"holder_name": "Readers", "holder_kind": "role",
                       "holder_id": "reader"})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert "No active role named reader" in problem["detail"]
    assert "create the role first" in problem["detail"]
    assert "role.create" in (problem.get("remedies") or [])


async def test_grant_amendment_refused_once_role_retired(env):
    """request_access re-judges the holder: a retired role blocks further
    negotiation until reactivated (the guard and on_create share one rule)."""
    engine, client = env
    role = (await _post(client, "/api/roles", {"name": "reader"})).json()
    grant = (await _post(client, "/api/grants",
                         {"holder_name": "Readers", "holder_kind": "role",
                          "holder_id": "reader"})).json()
    assert grant["state"] == "draft"

    res = await _act(client, role["self"], "retire")
    assert res.status_code == 200, res.text

    res = await _act(client, grant["self"], "request_access", {
        "task": "Readers read titles.",
        "requested_fields": {"note": {"title": "clear"}},
        "requested_hours": 2})
    assert res.status_code == 409, res.text
    assert "No active role named reader" in res.json()["detail"]

    await _act(client, role["self"], "reactivate")
    res = await _act(client, grant["self"], "request_access", {
        "task": "Readers read titles.",
        "requested_fields": {"note": {"title": "clear"}},
        "requested_hours": 2})
    assert res.status_code == 200, res.text
