"""The UI at the site root (engine.landing()): the same generic client the
``{base}/-/ui`` route serves, with the API base injected so the page stops
deriving it from its own URL. Mounting is the app's choice; the ``/-/ui``
route keeps working either way.
"""
from __future__ import annotations

import os

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark6
from waymark6.server.bus import InProcessBus
from waymark6.server.engine import header_principal
from waymark6.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


@pytest.fixture
async def env():
    engine = waymark6.Engine(resources=[], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    app.include_router(engine.landing())
    client = AsyncClient(transport=ASGITransport(app=app), base_url="http://t")
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_root_serves_the_client_with_the_base_injected(env):
    engine, client = env
    res = await client.get("/")
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/html")
    assert '<head><script>window.WAYMARK_BASE = "/api";</script>' in res.text
    # the client consumes the injection (falls back to path-derivation
    # when served at {base}/-/ui, where nothing is injected)
    assert "window.WAYMARK_BASE ??" in res.text


async def test_ui_route_still_serves_uninjected(env):
    engine, client = env
    res = await client.get("/api/-/ui")
    assert res.status_code == 200
    assert "window.WAYMARK_BASE = " not in res.text.split("</head>")[0]
