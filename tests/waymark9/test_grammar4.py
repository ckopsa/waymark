"""The collection grammar completes (design §9): ``?sort=`` is spec
(declared sortable, ``-`` for descending, derived fields sort because
they are maintained columns), ``?page[…]=`` is spec-owned with ``next``/
``prev`` envelope links, ``?rows=none`` returns the envelope — total,
facets, the query action — with no items, and unknown parameters or
values remain Problems.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import Ctx, Derived, Resource, Safety, action, filterable, sortable
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class TaskState(StrEnum):
    OPEN = "open"
    DONE = "done"


class TaskData(BaseModel):
    title: str = Field(min_length=1, max_length=80)
    estimate: int = Field(ge=0)
    bonus: int = Field(default=0, ge=0)
    # a derived fact sorts because it is a maintained column (design §9)
    score: int = Derived(over=("estimate", "bonus"),
                         fn=lambda e, b: e + b, default=0)
    # a boolean fact filters in the grammar's JSON spelling (true/false)
    big: bool = Derived(over=("estimate",),
                        fn=lambda e: e >= 3, default=False)


class Task(Resource):
    kind = "task"
    State = TaskState
    Data = TaskData
    initial = TaskState.OPEN
    terminal = {TaskState.DONE}
    summary = "{data.title} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        estimate=filterable.Range,
        score=filterable.Range,
        big=filterable.Eq,
    )
    sortable = sortable("title", "estimate", "score", default="title")

    @action(from_=TaskState.OPEN, to=TaskState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=waymark9.Acknowledged(
                              "Finishing a task is the point of one.")),
            display=dict(label="Finish"))
    async def finish(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark9.Engine(resources=[Task], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(
        transport=ASGITransport(app=app), base_url="http://t",
        headers={"X-Principal-Id": "marcus", "X-Principal-Type": "human"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _seed(client, rows) -> None:
    for title, estimate, bonus in rows:
        res = await client.post(
            "/api/tasks",
            json={"title": title, "estimate": estimate, "bonus": bonus},
            headers={"Idempotency-Key": uuid.uuid4().hex})
        assert res.status_code == 201, res.text


SEED = (("alpha", 5, 0), ("bravo", 1, 2), ("carol", 3, 9), ("delta", 2, 0),
        ("echo", 4, 4))


# ── sort: declared fields, - prefix, derived fields included ────────────
async def test_sort_ascending_and_descending(env):
    engine, client = env
    await _seed(client, SEED)
    asc = (await client.get("/api/tasks?sort=estimate")).json()
    assert [i["data"]["estimate"] for i in asc["data"]["items"]] == \
        [1, 2, 3, 4, 5]
    desc = (await client.get("/api/tasks?sort=-estimate")).json()
    assert [i["data"]["estimate"] for i in desc["data"]["items"]] == \
        [5, 4, 3, 2, 1]


async def test_derived_field_sorts_in_sql(env):
    engine, client = env
    await _seed(client, SEED)
    doc = (await client.get("/api/tasks?sort=-score")).json()
    scores = [i["data"]["score"] for i in doc["data"]["items"]]
    assert scores == [12, 8, 5, 3, 2], \
        "the maintained column sorts; nothing recomputed at the point of use"
    assert [i["data"]["title"] for i in doc["data"]["items"]][0] == "carol"


async def test_unknown_sort_field_is_a_problem(env):
    engine, client = env
    res = await client.get("/api/tasks?sort=flavor")
    assert res.status_code == 422
    assert "sort" in res.json()["errors"]


async def test_sort_is_advertised_with_descending_spellings(env):
    engine, client = env
    doc = (await client.get("/api/tasks")).json()
    sort_enum = doc["actions"]["query"]["input"]["properties"]["sort"]["enum"]
    assert "score" in sort_enum and "-score" in sort_enum


# ── pagination: spec-owned params, next/prev envelope links ─────────────
async def test_page_walk_via_next_and_prev(env):
    engine, client = env
    await _seed(client, SEED)
    seen: list[str] = []
    doc = (await client.get("/api/tasks?sort=title&page[size]=2")).json()
    pages = 0
    while True:
        pages += 1
        seen += [i["data"]["title"] for i in doc["data"]["items"]]
        nxt = doc["links"]["next"]
        if nxt is None:
            break
        doc = (await client.get(nxt["href"])).json()
    assert pages == 3
    assert seen == ["alpha", "bravo", "carol", "delta", "echo"], \
        "the walk visits every row exactly once"
    assert doc["links"]["next"] is None
    prev = (await client.get(doc["links"]["prev"]["href"])).json()
    assert [i["data"]["title"] for i in prev["data"]["items"]] == \
        ["carol", "delta"]
    assert prev["data"]["page"] == {"size": 2, "number": 2}


async def test_malformed_page_params_are_problems(env):
    engine, client = env
    for query, param in (("page[size]=0", "page[size]"),
                         ("page[size]=101", "page[size]"),
                         ("page[number]=zero", "page[number]")):
        res = await client.get(f"/api/tasks?{query}")
        assert res.status_code == 422, query
        assert param in res.json()["errors"]


# ── rows=none: the envelope without its rows ────────────────────────────
async def test_rows_none_returns_total_facets_and_query_but_no_items(env):
    engine, client = env
    await _seed(client, SEED)
    doc = (await client.get("/api/tasks?rows=none")).json()
    assert doc["data"]["items"] is None
    assert doc["data"]["total"] == 5
    assert "query" in doc["actions"] and "create" in doc["actions"]
    facets = doc["actions"]["query"]["input"]["properties"]["state"]["x-facets"]
    assert facets == {"open": 5}


async def test_rows_none_total_equals_filtered_count(env):
    engine, client = env
    await _seed(client, SEED)
    with_rows = (await client.get("/api/tasks?score_gte=5")).json()
    without = (await client.get("/api/tasks?score_gte=5&rows=none")).json()
    assert with_rows["data"]["total"] == len(with_rows["data"]["items"]) == 3
    assert without["data"]["total"] == 3, \
        "one WHERE: the count cannot disagree with the rows it summarizes"
    assert without["data"]["items"] is None


async def test_unknown_rows_value_is_a_problem(env):
    engine, client = env
    res = await client.get("/api/tasks?rows=headers")
    assert res.status_code == 422
    assert "rows" in res.json()["errors"]


async def test_new_params_round_trip_through_self_href(env):
    engine, client = env
    await _seed(client, SEED)
    first = (await client.get(
        "/api/tasks?sort=-score&page[size]=2&page[number]=2")).json()
    params = {k: v[-1]
              for k, v in parse_qs(urlsplit(first["self"]).query).items()}
    assert params["sort"] == "-score"
    assert params["page[size]"] == "2" and params["page[number]"] == "2"
    again = (await client.get(first["self"])).json()
    assert again["self"] == first["self"]
    assert [i["data"]["title"] for i in again["data"]["items"]] == \
        [i["data"]["title"] for i in first["data"]["items"]]

    counted = (await client.get("/api/tasks?rows=none&estimate_gte=3")).json()
    again = (await client.get(counted["self"])).json()
    assert again["data"]["items"] is None and again["data"]["total"] == 3


# ── booleans: JSON spellings, and only those ────────────────────────────
async def test_boolean_filter_speaks_json(env):
    engine, client = env
    await _seed(client, SEED)
    yes = (await client.get("/api/tasks?big=true")).json()
    assert [i["data"]["title"] for i in yes["data"]["items"]] == \
        ["alpha", "carol", "echo"]
    assert all(i["data"]["big"] is True for i in yes["data"]["items"])
    no = (await client.get("/api/tasks?big=false")).json()
    assert {i["data"]["title"] for i in no["data"]["items"]} == \
        {"bravo", "delta"}


async def test_boolean_filter_rejects_python_spelling(env):
    engine, client = env
    for raw in ("True", "1", "yes"):
        res = await client.get(f"/api/tasks?big={raw}")
        assert res.status_code == 422, raw
        assert "big" in res.json()["errors"], \
            "str(True) is not the grammar; malformed values are Problems"
