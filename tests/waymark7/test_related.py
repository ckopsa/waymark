"""Relationships are declarations (design 6.0 §1): the surface.

``Related(kind, on=(On(...), ...))`` names a target kind and a
conjunction of field comparisons; ``check_related`` refuses, at
assembly, every predicate the storage layer could not index —
invertibility is §2's guarantee, not an optimization. A ``link`` may
cite an edge and have its href compiled from the checked predicate
instead of hand-templating a query string the checks cannot see.

NOTE (waymark7-notes): the design doc spells the citation
``link("calendar", rel=_calendar, ...)``, which collides with link's
first positional parameter ``rel`` (the relation name). The shipped
spelling is ``link("calendar", edge=_calendar, ...)``.
"""
from __future__ import annotations

import os
import uuid
from datetime import date, datetime
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import AwareDatetime, BaseModel, Field

import waymark7
from waymark7 import (
    Count,
    DefinitionError,
    On,
    Registry,
    Related,
    Resource,
    filterable,
    link,
)
from waymark7.core import checks
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "priya", "X-Principal-Display": "Priya"}

_calendar = Related("revent", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date",   op=">=", theirs="date"),
))

_registry_edge = Related("revent", on=(
    On(ours="fund", op="==", theirs="fund"),
))


class OneState(StrEnum):
    OPEN = "open"


class REventData(BaseModel):
    date: date
    fund: str = Field(default="", max_length=40)


class REvent(Resource):
    kind = "revent"
    State = OneState
    Data = REventData
    initial = OneState.OPEN
    summary = "event {data.date} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            date=filterable.Range,
                            fund=filterable.Eq)


class RPlanData(BaseModel):
    start_date: date
    end_date: date
    fund: str = Field(default="", max_length=40)
    events: int = Count(_calendar)


class RPlan(Resource):
    kind = "rplan"
    State = OneState
    Data = RPlanData
    initial = OneState.OPEN
    summary = "plan {data.start_date} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            start_date=filterable.Range,
                            end_date=filterable.Range,
                            fund=filterable.Eq)
    links = (
        link("calendar", edge=_calendar, embed=True, badge="events",
             summary="What the family already has planned"),
        link("registry", edge=_registry_edge),
    )


# ── import-time refusals: the declaration cannot lie about its shape ─────

def test_on_refuses_unknown_ops_and_non_field_sides():
    with pytest.raises(DefinitionError, match="not one of"):
        On(ours="a", op="!=", theirs="b")
    with pytest.raises(DefinitionError, match="stored Data fields"):
        On(ours=None, op="==", theirs="b")  # Clock-shaped inputs included


def test_related_requires_a_predicate():
    with pytest.raises(DefinitionError, match="on="):
        Related("event")
    with pytest.raises(DefinitionError, match="On"):
        Related("event", on=("start_date <= date",))


def test_link_edge_and_href_are_mutually_exclusive():
    with pytest.raises(DefinitionError, match="mutually exclusive"):
        link("calendar", edge=_calendar, href="/revents?d={data.start_date}")
    with pytest.raises(DefinitionError, match="href= .*or edge="):
        link("calendar")
    with pytest.raises(DefinitionError, match="requires kind="):
        link("calendar", href="/revents")


def test_edge_cited_link_kind_defaults_to_the_target_collection():
    ld = link("calendar", edge=_calendar)
    assert ld.kind == "revent_collection"
    assert ld.href is None  # compiled at assembly, from the predicate
    ld = link("calendar", edge=_calendar, kind="week_view")
    assert ld.kind == "week_view"


# ── assembly refusals: check_related, the check_derived_edges tradition ──

def _plan_variant(data_cls=RPlanData, *, plan_filterable=None, links=()):
    class P(Resource):
        kind = "rplan"
        State = OneState
        Data = data_cls
        initial = OneState.OPEN
        summary = "plan · {state.label}"
    P.filterable = plan_filterable if plan_filterable is not None \
        else RPlan.filterable
    P.links = tuple(links)
    return P


def _registry(plan, event=REvent):
    reg = Registry()
    reg.register(plan)
    if event is not None:
        reg.register(event)
    return reg


def test_unregistered_target_kind_is_refused():
    with pytest.raises(DefinitionError, match="not registered"):
        checks.check_related(_registry(_plan_variant(), event=None))


def test_unpromoted_join_field_on_our_side_is_refused():
    thin = filterable(state=filterable.Eq, end_date=filterable.Range)
    with pytest.raises(DefinitionError,
                       match="'start_date' \\(ours\\) is not a promoted"):
        checks.check_related(_registry(
            _plan_variant(plan_filterable=thin)))


def test_unpromoted_join_field_on_their_side_is_refused():
    class ThinEvent(REvent):
        kind = "revent"
        filterable = filterable(state=filterable.Eq, fund=filterable.Eq)

    with pytest.raises(DefinitionError,
                       match="'date' \\(theirs\\) is not a promoted"):
        checks.check_related(_registry(_plan_variant(), event=ThinEvent))


def test_ordered_op_over_string_columns_is_refused():
    bad = Related("revent", on=(On(ours="fund", op="<=", theirs="fund"),))

    class D(BaseModel):
        start_date: date
        end_date: date
        fund: str = ""
        n: int = Count(bad)

    with pytest.raises(DefinitionError, match="ordered joins need matching"):
        checks.check_related(_registry(_plan_variant(D)))


def test_mismatched_temporal_families_are_refused():
    """A date field promotes to text, a date-time to timestamptz — the
    column types cannot serve one comparison, so the checks say so."""
    mixed = Related("revent", on=(On(ours="stamped_at", op="<=",
                                     theirs="date"),))

    class D(BaseModel):
        stamped_at: AwareDatetime
        n: int = Count(mixed)

    spec = filterable(state=filterable.Eq, stamped_at=filterable.Range)
    with pytest.raises(DefinitionError, match="cannot be served"):
        checks.check_related(_registry(_plan_variant(D, plan_filterable=spec)))


def test_where_must_run_on_promoted_target_columns():
    class D(BaseModel):
        start_date: date
        end_date: date
        n: int = Count(_calendar, where={"nope": ("x",)})

    with pytest.raises(DefinitionError, match="not filterable"):
        checks.check_related(_registry(_plan_variant(D)))


def test_related_field_must_name_a_real_target_field():
    class D(BaseModel):
        start_date: date
        end_date: date
        n: bool = waymark7.Derived(over=(_calendar.field("nope"),),
                                   fn=lambda xs: bool(xs))

    with pytest.raises(DefinitionError, match="not a data field"):
        checks.check_related(_registry(_plan_variant(D)))


def test_link_edges_with_strict_ops_are_refused():
    """The compiled href speaks the public range grammar (_gte/_lte);
    strict comparisons have no query parameter, deliberately."""
    strict = Related("revent", on=(On(ours="start_date", op="<",
                                      theirs="date"),))
    with pytest.raises(DefinitionError, match="no query parameter"):
        checks.check_related(_registry(_plan_variant(
            links=(link("later", edge=strict),))))


def test_link_edge_must_be_a_related_declaration():
    class FakeEdge:
        kind = "revent"

    with pytest.raises(DefinitionError, match="takes a Related"):
        checks.check_related(_registry(_plan_variant(
            links=(link("cal", edge=FakeEdge()),))))


# ── the compiled href, end to end ────────────────────────────────────────

@pytest.fixture
async def env():
    engine = waymark7.Engine(resources=[RPlan, REvent], storage=TEST_DSN,
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


async def _post(client, href, body):
    res = await client.post(href, json=body,
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def test_edge_cited_links_compile_render_and_resolve(env):
    _, client = env
    inside = await _post(client, "/api/revents",
                         {"date": "2026-06-03", "fund": "growth"})
    await _post(client, "/api/revents",
                {"date": "2026-07-20", "fund": "income"})
    plan = await _post(client, "/api/rplans",
                       {"start_date": "2026-06-01", "end_date": "2026-06-07",
                        "fund": "growth"})

    links = plan["links"]
    # the range predicate compiles to the §3 params, ours interpolated
    cal = links["calendar"]
    assert cal["href"] == \
        "/api/revents?date_gte=2026-06-01&date_lte=2026-06-07"
    assert cal["kind"] == "revent_collection"
    assert cal["embed"] is True, "embed still rides an edge-cited link"
    assert cal["badge"] == 1, "badge still rides an edge-cited link"
    assert cal["summary"] == "What the family already has planned"
    # the equality predicate compiles to a plain filter param
    assert links["registry"]["href"] == "/api/revents?fund=growth"
    assert links["registry"]["kind"] == "revent_collection"

    # the compiled href is not scent — it resolves, through the same
    # grammar the schema advertises and the same index the filter uses
    listing = (await client.get(cal["href"])).json()
    assert [i["self"] for i in listing["data"]["items"]] == [inside["self"]]
    listing = (await client.get(links["registry"]["href"])).json()
    assert [i["self"] for i in listing["data"]["items"]] == [inside["self"]]


async def test_the_predicate_rides_the_fingerprint(env):
    """A Related edge is law: the derived input's predicate and the
    link's citation both land in the definition fingerprint, so changing
    a join is a revise with a diff, never a silent redeploy."""
    from waymark7.core.fingerprint import fingerprint_of

    engine, _ = env
    fp = fingerprint_of(engine.registry["rplan"])
    over = fp["derived"]["events"]["over"][0]["related"]
    assert over["kind"] == "revent"
    assert over["on"] == [
        {"ours": "start_date", "op": "<=", "theirs": "date"},
        {"ours": "end_date", "op": ">=", "theirs": "date"},
    ]
    cal = next(l for l in fp["links"] if l["rel"] == "calendar")
    assert cal["edge"]["on"][0]["ours"] == "start_date"
    assert cal["href"].startswith("/revents?date_gte=")
