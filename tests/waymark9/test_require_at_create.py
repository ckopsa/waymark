"""require() evaluates at create (the "relations evaluate against inputs
and identities" wave, closing ledger6 seam #1).

``FactRequired`` used to read the materialized fact off ``r.data`` — at
create ``r is None`` and there is no row for the maintainer to have
materialized anything into, so a Related fact could not gate its own
create and apps kept a hand-written ``check=`` twin of the declared
derivation. The precedent is E9: guards already judge inputs. Extended:
with ``r=None`` the fact is computed FROM the validated create input
through the spec's own declared inputs and pure ``apply``
(``compute_from_input`` — the same function materialization and the
conformance replay run), so the value the guard judges equals the value
the same create materializes into the row. Own fields read off the
input; ``ChildField`` → an empty list (a new row owns nothing yet —
true, not a fallback); ``RelatedField`` → the forward read with the
input's join values (identity joins included); ``Clock`` → the ctx's
now. ``severity="warning"`` joins the E1 acknowledge protocol like any
guard.
"""
from __future__ import annotations

import os
import uuid
from datetime import date, timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import (
    Count,
    Ctx,
    DefinitionError,
    Derived,
    On,
    Owns,
    Ref,
    RefField,
    Related,
    Resource,
    Safety,
    action,
    filterable,
    require,
)
from waymark9.core.derived import Clock, compute_from_input, derived_specs
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena"}


# ── the registry case (the ledger6 finding, verbatim shape) ───────────

class TplState(StrEnum):
    ACTIVE = "active"
    RETIRED = "retired"


class TplData(BaseModel):
    fund: str = Field(min_length=1, max_length=40)


class RTemplate(Resource):
    kind = "rtpl6"
    State = TplState
    Data = TplData
    initial = TplState.ACTIVE
    terminal = {TplState.RETIRED}
    summary = "template {data.fund} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            fund=filterable.Eq)

    @action(from_=TplState.ACTIVE, to=TplState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="New books stop seeding from it."))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass


_registry = Related("rtpl6", on=(On(ours="fund", op="==", theirs="fund"),))


class BookState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class RBookData(BaseModel):
    fund: str = Field(min_length=1, max_length=40)
    registry_accounts: int = Count(_registry, where={"state": ("active",)})
    fund_has_templates: bool = Derived(
        over=(_registry.field("id", where={"state": ("active",)}),),
        fn=lambda ids: bool(ids),
        explain="Fund has {n} active registry template(s) — create one "
                "before opening a book.",
        vars=lambda ids: {"n": len(ids)})


class RBook(Resource):
    kind = "rbook6"
    State = BookState
    Data = RBookData
    initial = BookState.OPEN
    allow_dead = {BookState.CLOSED}
    summary = "book {data.fund} · {state.label}"
    filterable = filterable(state=filterable.Eq, fund=filterable.Eq)

    # the Related fact gating its own create — the relation-fact and the
    # create-guard are ONE spelling now, not two
    create_guards = (require("fund_has_templates",
                             remedies=("rtpl6.create",)),)


# ── ChildField at create: a new row owns nothing yet, honestly ───────────

_eggs = Owns("egg6", via="nest_id")


class OneState(StrEnum):
    OPEN = "open"


class EggData(BaseModel):
    nest_id: Ref["nest6"] = RefField(min_length=1)


class Egg(Resource):
    kind = "egg6"
    State = OneState
    Data = EggData
    initial = OneState.OPEN
    summary = "egg · {state.label}"
    filterable = filterable(state=filterable.Eq, nest_id=filterable.Eq)


class NestData(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    childless: bool = Derived(over=(_eggs.field("id"),),
                              fn=lambda ids: not ids)
    populated: bool = Derived(
        over=(_eggs.field("id"),),
        fn=lambda ids: bool(ids),
        explain="The nest holds {n} egg(s); it needs at least one.",
        vars=lambda ids: {"n": len(ids)})


class Nest(Resource):
    kind = "nest6"
    State = OneState
    Data = NestData
    initial = OneState.OPEN
    summary = "nest {data.name} · {state.label}"
    owns = (_eggs,)
    filterable = filterable(state=filterable.Eq)

    # childless is vacuously TRUE for a new row (an empty list is the
    # truth, not a fallback) — it allows; populated is honestly FALSE —
    # a warning the caller may acknowledge past
    create_guards = (require("childless"),
                     require("populated", severity="warning"))


# ── Clock at create ──────────────────────────────────────────────────────

class TickData(BaseModel):
    due_date: date
    not_past: bool = Derived(over=("due_date", Clock),
                             fn=lambda d, now: d >= now.date())


class Tick(Resource):
    kind = "tick6"
    State = OneState
    Data = TickData
    initial = OneState.OPEN
    summary = "deadline {data.due_date} · {state.label}"
    filterable = filterable(state=filterable.Eq)

    create_guards = (require("not_past"),)


# ── the identity join at create (Extension 1 riding Extension 2) ─────────

_gacct = Related("gacct6", on=(On(ours="account_id", op="==",
                                  theirs="id"),))


class GAcctState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class GAcctData(BaseModel):
    fund: str = Field(default="", max_length=40)


class GAccount(Resource):
    kind = "gacct6"
    State = GAcctState
    Data = GAcctData
    initial = GAcctState.OPEN
    summary = "account {data.fund} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            fund=filterable.Eq)

    @action(from_=GAcctState.OPEN, to=GAcctState.CLOSED,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=GAcctState.CLOSED, to=GAcctState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass


class GBreakData(BaseModel):
    account_id: Ref["gacct6"] = RefField(min_length=1)
    account_is_open: bool = Derived(
        over=(_gacct.field("state"),),
        fn=lambda states: bool(states) and states[0] == "open",
        explain="The account is {status} — a break can only be entered "
                "on an open account.",
        vars=lambda states: {"status": states[0] if states else "missing"})


class GBreak(Resource):
    kind = "gbrk6"
    State = OneState
    Data = GBreakData
    initial = OneState.OPEN
    summary = "break · {state.label}"
    filterable = filterable(state=filterable.Eq, account_id=filterable.Eq)

    # a break gating on its own account's state, AT CREATE: the create
    # input carries account_id, so the parent load works
    create_guards = (require("account_is_open",
                             remedies=("gacct6.reopen",)),)


# ── environment ──────────────────────────────────────────────────────────

@pytest.fixture
async def env():
    engine = waymark9.Engine(
        resources=[RTemplate, RBook, Nest, Egg, Tick, GAccount, GBreak],
        storage=TEST_DSN, principal=header_principal, services=None,
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


async def _post(client, href, body=None, **headers):
    return await client.post(href, json=body, headers={
        "Idempotency-Key": uuid.uuid4().hex, **headers})


# ── refuse severity: the derived explain, computed from the input ────────

async def test_refuse_at_create_speaks_the_derived_explain(env):
    engine, client = env
    res = await _post(client, "/api/rbook6s", {"fund": "nova"})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("guard-failed")
    assert problem["action_attempted"] == "create"
    assert problem["detail"] == ("Fund has 0 active registry template(s) — "
                                 "create one before opening a book.")
    assert problem.get("remedies") == ["rtpl6.create"]
    listing = (await client.get("/api/rbook6s")).json()
    assert listing["data"]["total"] == 0, "the refusal preceded any insert"


async def test_judged_value_equals_materialized_value(env):
    """The consistency guarantee: same inputs, same pure apply — the
    fact the create guard judged is the fact the same create wrote into
    the row, asserted end-to-end and then field-by-field through
    compute_from_input against the stored row."""
    engine, client = env
    for _ in range(2):
        assert (await _post(client, "/api/rtpl6s",
                            {"fund": "alpha"})).status_code == 201
    res = await _post(client, "/api/rbook6s", {"fund": "alpha"})
    assert res.status_code == 201, res.text
    doc = res.json()
    # the guard judged True (it allowed); the row materialized the same
    # truth, garnish values included
    assert doc["data"]["fund_has_templates"] is True
    assert doc["data"]["registry_accounts"] == 2

    class _ShimCtx:
        def __init__(self, s):
            self._s = s
            self.now = engine.invoker.clock()

        async def find(self, kind, *, sort=None, limit=25, page=1,
                       **filters):
            rows, _ = await engine.storage.query(
                self._s, kind, filters=filters, sort=sort,
                page_size=limit, page_number=page)
            return rows

    book_id = doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        values, _args = await compute_from_input(
            RBookData, RBookData(fund="alpha"), _ShimCtx(s))
        row = await engine.storage.load(s, "rbook6", book_id)
    stored = row.data.model_dump()
    for fact in derived_specs(RBookData):
        assert values[fact] == stored[fact], \
            f"{fact}: judged {values[fact]!r} != materialized {stored[fact]!r}"


# ── warning severity: the E1 acknowledge protocol, at create ─────────────

async def test_warning_require_is_acknowledgeable_with_the_standard_header(env):
    engine, client = env
    res = await _post(client, "/api/nest6s", {"name": "spring"})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    assert problem["severity"] == "warning"
    assert problem["warnings"] == [
        {"name": "require:populated",
         "reason": "The nest holds 0 egg(s); it needs at least one."}], \
        "the derived explain, garnished from the input-computed args"
    assert problem["acknowledge"] == {"header": "Waymark-Acknowledge",
                                      "names": ["require:populated"]}

    res = await _post(client, "/api/nest6s", {"name": "spring"},
                      **{"Waymark-Acknowledge": "require:populated"})
    assert res.status_code == 201, res.text
    doc = res.json()
    nest_id = doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "nest6", nest_id)
    assert last.acknowledged == ["require:populated"], \
        "the override is in the audit log, as for any warning guard"


async def test_childfield_at_create_judges_empty_honestly(env):
    """``childless`` allowed the acknowledged create above because a new
    row owning nothing is TRUE, not a fallback — and the created row
    materializes exactly what was judged."""
    _, client = env
    res = await _post(client, "/api/nest6s", {"name": "summer"},
                      **{"Waymark-Acknowledge": "require:populated"})
    assert res.status_code == 201, res.text
    assert res.json()["data"]["childless"] is True
    assert res.json()["data"]["populated"] is False


async def test_dry_run_judges_the_create_guards_too(env):
    _, client = env
    res = await _post(client, "/api/rbook6s?dry_run=1", {"fund": "nova"})
    assert res.status_code == 409, res.text
    assert res.json()["detail"].startswith("Fund has 0 active")

    res = await _post(client, "/api/nest6s?dry_run=1", {"name": "w"})
    assert res.status_code == 200, res.text
    doc = res.json()
    assert doc["valid"] is True
    assert doc["warnings"][0]["name"] == "require:populated"
    listing = (await client.get("/api/nest6s")).json()
    assert listing["data"]["total"] == 0


# ── Clock at create ──────────────────────────────────────────────────────

async def test_clock_input_resolves_to_the_ctx_now(env):
    _, client = env
    tomorrow = (date.today() + timedelta(days=1)).isoformat()
    yesterday = (date.today() - timedelta(days=1)).isoformat()
    res = await _post(client, "/api/tick6s", {"due_date": tomorrow})
    assert res.status_code == 201, res.text
    assert res.json()["data"]["not_past"] is True

    res = await _post(client, "/api/tick6s", {"due_date": yesterday})
    assert res.status_code == 409, res.text
    assert res.json()["detail"] == "Not yet: not past does not hold.", \
        "no explain declared: the r-None path renders the same default"


# ── the identity join riding the create guard ────────────────────────────

async def test_identity_join_fact_gates_its_own_create(env):
    """The dogfood's break: the create input carries account_id, so the
    forward read loads the parent — the gate refuses under a closed
    account with the derivation's sentence, allows under an open one,
    and the allowed row is born carrying the judged truth."""
    _, client = env
    res = await _post(client, "/api/gacct6s", {"fund": "alpha"})
    acct = res.json()
    acct_id = acct["self"].rsplit("/", 1)[-1]

    res = await _post(client, "/api/gbrk6s", {"account_id": acct_id})
    assert res.status_code == 201, res.text
    assert res.json()["data"]["account_is_open"] is True

    await _post(client, f"{acct['self']}/-/close")
    res = await _post(client, "/api/gbrk6s", {"account_id": acct_id})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["detail"] == ("The account is closed — a break can "
                                 "only be entered on an open account.")
    assert problem.get("remedies") == ["gacct6.reopen"]


# ── the class-binding pitfall, made a loud definition error ──────────────

def test_one_require_instance_cannot_serve_two_classes():
    shared = require("ok")

    def _mk(kind_, seed_default):
        class D(BaseModel):
            seed_val: bool = seed_default
            ok: bool = Derived(over=("seed_val",), fn=bool)

        class R(Resource):
            kind = kind_
            State = OneState
            Data = D
            initial = OneState.OPEN
            summary = "row · {state.label}"
            filterable = filterable(state=filterable.Eq)
            create_guards = (shared,)

        return R

    _mk("bindone6", True)
    with pytest.raises(DefinitionError, match="once per class"):
        _mk("bindtwo6", False)
