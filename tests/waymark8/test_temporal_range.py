"""The range speaks its own type (design 6.0 §3).

5.x advertised every RANGE boundary param as ``{"type": "number"}``
unless the field was already numeric, so a date field's honest btree
comparison was *advertised* as a lie and ``?when_gte=2026-06-01`` was
422'd for not being a float. 6.0 types boundary params by the promoted
column: ``{"type": "string", "format": "date"}`` / ``"date-time"`` for
temporal fields (the ``_after`` param always did this; the range params
join it), numeric unchanged. The router enforces the advertised format
(a malformed boundary is a per-field 422), and the storage layer binds
ISO strings to whatever the promoted column is — text for dates (ISO
order IS date order), timestamptz for date-times.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import AwareDatetime, BaseModel, Field

import waymark8
from waymark8 import Resource, filterable
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "sam", "X-Principal-Display": "Sam"}

from datetime import date  # noqa: E402


class TripState(StrEnum):
    PLANNED = "planned"


class TripData(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    when: date
    happened_at: AwareDatetime
    amount: float = Field(default=0.0, ge=0)


class Trip(Resource):
    kind = "trip"
    State = TripState
    Data = TripData
    initial = TripState.PLANNED
    summary = "{data.name} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            when=filterable.Range,
                            happened_at=filterable.Range,
                            amount=filterable.Range)


@pytest.fixture
async def env():
    engine = waymark8.Engine(resources=[Trip], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _trip(client, name: str, when: str, at: str, amount=0.0) -> dict:
    res = await client.post(
        "/api/trips",
        json={"name": name, "when": when, "happened_at": at,
              "amount": amount},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


def _names(listing) -> set[str]:
    return {i["data"]["name"] for i in listing["data"]["items"]}


# ── the honest advertisement (§3) ────────────────────────────────────────

async def test_query_schema_types_range_params_by_the_promoted_column(env):
    engine, _ = env
    props = engine.registry["trip"].query_schema["properties"]
    for param in ("when_gte", "when_lte"):
        assert props[param]["type"] == "string", param
        assert props[param]["format"] == "date", param
    for param in ("happened_at_gte", "happened_at_lte"):
        assert props[param]["type"] == "string", param
        assert props[param]["format"] == "date-time", param
    # numeric boundaries are unchanged — §3 retypes, it does not reshape
    assert props["amount_gte"]["type"] == "number"
    assert "format" not in props["amount_gte"]


# ── the honest comparison, end to end ────────────────────────────────────

async def test_date_range_filters_end_to_end(env):
    _, client = env
    await _trip(client, "may", "2026-05-15", "2026-05-15T09:00:00Z")
    await _trip(client, "june", "2026-06-10", "2026-06-10T09:00:00Z")
    await _trip(client, "july", "2026-07-01", "2026-07-01T09:00:00Z")

    listing = (await client.get("/api/trips?when_gte=2026-06-01")).json()
    assert _names(listing) == {"june", "july"}

    listing = (await client.get(
        "/api/trips?when_gte=2026-06-01&when_lte=2026-06-30")).json()
    assert _names(listing) == {"june"}

    listing = (await client.get("/api/trips?when_lte=2026-05-31")).json()
    assert _names(listing) == {"may"}


async def test_datetime_range_filters_bind_to_the_timestamptz_column(env):
    """The driver question, settled by observation: the router passes the
    ISO string through and the storage layer coerces it to a datetime for
    the timestamptz promoted column — asyncpg never sees a string bind."""
    _, client = env
    await _trip(client, "early", "2026-06-10", "2026-06-10T08:00:00Z")
    await _trip(client, "late", "2026-06-10", "2026-06-10T18:30:00+00:00")

    listing = (await client.get(
        "/api/trips?happened_at_gte=2026-06-10T12:00:00Z")).json()
    assert _names(listing) == {"late"}

    listing = (await client.get(
        "/api/trips?happened_at_gte=2026-06-10T00:00:00Z"
        "&happened_at_lte=2026-06-10T09:00:00Z")).json()
    assert _names(listing) == {"early"}


async def test_numeric_range_still_works(env):
    _, client = env
    await _trip(client, "cheap", "2026-06-01", "2026-06-01T09:00:00Z", 5.0)
    await _trip(client, "dear", "2026-06-02", "2026-06-02T09:00:00Z", 50.0)
    listing = (await client.get("/api/trips?amount_gte=10")).json()
    assert _names(listing) == {"dear"}


# ── malformed boundaries are per-field Problems ──────────────────────────

async def test_malformed_date_boundary_is_a_422_with_a_field_error(env):
    _, client = env
    res = await client.get("/api/trips?when_gte=June-the-first")
    assert res.status_code == 422, res.text
    errors = res.json()["errors"]
    assert "when_gte" in errors
    assert "ISO 8601 date" in errors["when_gte"][0]

    res = await client.get("/api/trips?happened_at_lte=yesterday")
    assert res.status_code == 422, res.text
    errors = res.json()["errors"]
    assert "happened_at_lte" in errors
    assert "date-time" in errors["happened_at_lte"][0]


async def test_the_collection_self_href_round_trips_temporal_filters(env):
    """The parse ∘ serialize fixpoint (the query-grammar discipline):
    fetching the collection's own self href re-applies the same slice."""
    _, client = env
    await _trip(client, "june", "2026-06-10", "2026-06-10T09:00:00Z")
    await _trip(client, "july", "2026-07-01", "2026-07-01T09:00:00Z")
    first = (await client.get(
        "/api/trips?when_gte=2026-06-01&when_lte=2026-06-30")).json()
    again = (await client.get(first["self"])).json()
    assert _names(again) == _names(first) == {"june"}
