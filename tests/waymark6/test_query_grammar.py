"""The query grammar is spec (design §7): hrefs are authoritative, clients
merge parameters through one property-tested ``merge_params``, unknown
parameters are Problems, and advertised collection hrefs round-trip.
"""
from __future__ import annotations

import os
import uuid
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from hypothesis import given, settings
from hypothesis import strategies as st

import waymark6
from waymark6.client import merge_params
from waymark6.server.bus import InProcessBus
from waymark6.server.engine import header_principal
from waymark6.testing import per_worker_dsn

from tests.waymark6.mealplan6.meal import Meal

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

_key = st.text(alphabet="abcdefghij_", min_size=1, max_size=8)
_val = st.text(alphabet="abcdefghij0123456789,", min_size=1, max_size=12)


@settings(max_examples=200, deadline=None)
@given(existing=st.dictionaries(_key, _val, max_size=4),
       added=st.dictionaries(_key, _val, min_size=1, max_size=4))
def test_merge_params_preserves_the_hrefs_own_query(existing, added):
    """The 10d5278 property: no key the href carried may be silently lost —
    it survives verbatim unless the caller explicitly overrides it."""
    from urllib.parse import urlencode

    href = "/api/plans" + ("?" + urlencode(existing) if existing else "")
    merged = merge_params(href, **added)
    got = {k: v[-1] for k, v in parse_qs(urlsplit(merged).query).items()}
    for k, v in existing.items():
        assert got[k] == added.get(k, v)
    for k, v in added.items():
        assert got[k] == v


def test_merge_params_skips_none_and_keeps_href_identity():
    assert merge_params("/api/plans?state=active", depth=None) \
        == "/api/plans?state=active"


@pytest.fixture
async def env():
    engine = waymark6.Engine(
        resources=[Meal], storage=TEST_DSN,
        principal=header_principal, services=None, bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(
        transport=ASGITransport(app=app), base_url="http://t",
        headers={"X-Principal-Id": "dana", "X-Principal-Type": "human"})
    try:
        yield client
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_unknown_query_params_are_problems(env):
    resp = await env.get("/api/meals?flavor=spicy")
    assert resp.status_code == 422
    problem = resp.json()
    assert problem["errors"]["flavor"] == ["unknown query parameter"]


async def test_collection_self_href_round_trips(env):
    for name, themes in (("Tacos", ["mexican"]), ("Brisket", ["bbq"]),
                         ("Fajitas", ["mexican", "american"])):
        resp = await env.post(
            "/api/meals", json={"name": name, "themes": themes},
            headers={"Idempotency-Key": uuid.uuid4().hex})
        assert resp.status_code == 201, resp.text

    first = (await env.get("/api/meals?themes=mexican,bbq&state=suggested")).json()
    names = {i["data"]["name"] for i in first["data"]["items"]}
    assert names == {"Tacos", "Brisket", "Fajitas"}

    # the self href is what a client re-reads its filters from: fetching it
    # verbatim must apply the same filters (parse ∘ serialize fixpoint)
    again = (await env.get(first["self"])).json()
    assert {i["data"]["name"] for i in again["data"]["items"]} == names
    assert again["self"] == first["self"]
