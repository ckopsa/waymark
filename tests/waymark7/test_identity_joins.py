"""Identity joins (the "relations evaluate against inputs and identities"
wave, closing ledger6 seam #2): ``On(theirs="id", op="==")`` joins a
stored field of ours against the target's primary key — the child→parent
direction every port so far hand-wrote three versions of
(``workbook_open``/``account_open``). The promoted-fields law holds at
full strength: the id IS the indexed column, the forward read is a point
lookup by the anchor's ref value, and the inverted map is an indexed
point lookup on the (promoted) ref column.

The new direction makes cross-kind fact cycles declarable for the first
time (facts can flow parent→child and child→parent), so assembly gains
``check_derived_cycles``: the maintainer's chained recompute terminates
exactly because the cross-kind fact graph is a DAG — a cycle is refused
at assembly, named, not throttled at runtime.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import (
    Count,
    Ctx,
    DefinitionError,
    Derived,
    On,
    Owns,
    Ref,
    RefField,
    Registry,
    Related,
    Resource,
    Safety,
    action,
    filterable,
    require,
)
from waymark7.core import checks
from waymark7.core.fingerprint import fingerprint_hash, fingerprint_of
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "marcus", "X-Principal-Display": "Marcus"}

# the dogfood spelling, verbatim: a break reads its own account's state
_account = Related("iacct6", on=(
    On(ours="account_id", op="==", theirs="id"),
))
_breaks = Owns("ibreak6", via="account_id")


class AcctState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class AcctData(BaseModel):
    fund: str = Field(default="", max_length=40)
    # downstream of the identity-join fact: the parent counts the very
    # gates its own state flips — legitimate two-direction composition
    # (acyclic at field level: open_gates → account_is_open → state)
    open_gates: int = Derived(
        over=(_breaks.field("account_is_open"),),
        fn=lambda flags: sum(1 for f in flags if f))


class IAccount(Resource):
    kind = "iacct6"
    State = AcctState
    Data = AcctData
    initial = AcctState.OPEN
    summary = "account {data.fund} · {state.label}"
    owns = (_breaks,)
    filterable = filterable(state=filterable.Eq | filterable.In,
                            fund=filterable.Eq,
                            open_gates=filterable.Eq | filterable.Range)

    @action(from_=AcctState.OPEN, to=AcctState.CLOSED,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=AcctState.CLOSED, to=AcctState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass


class BreakState(StrEnum):
    ACTIVE = "active"
    REMOVED = "removed"


class BreakData(BaseModel):
    account_id: Ref["iacct6"] = RefField(min_length=1)
    amount: float = Field(default=0.0)
    account_is_open: bool = Derived(
        over=(_account.field("state"),),
        fn=lambda states: bool(states) and states[0] == "open",
        explain="The account is {status} — reopen it first.",
        vars=lambda states: {"status": states[0] if states else "missing"})


class IBreak(Resource):
    kind = "ibreak6"
    State = BreakState
    Data = BreakData
    initial = BreakState.ACTIVE
    terminal = {BreakState.REMOVED}
    summary = "break {data.amount} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            account_id=filterable.Eq,
                            account_is_open=filterable.Eq)

    # the gate the dogfood wants: a break changes only while its own
    # account is open — require() over the identity-join fact
    @action(from_=BreakState.ACTIVE, to=BreakState.ACTIVE,
            guards=[require("account_is_open",
                            remedies=("iacct6.reopen",))],
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def amend(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=BreakState.ACTIVE, to=BreakState.REMOVED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The break stops explaining."))
    async def remove(self, inp: None, ctx: Ctx) -> None:
        pass


# ── declaration-time refusals ────────────────────────────────────────────

def test_ours_id_is_refused_pointing_at_owns():
    with pytest.raises(DefinitionError, match="Owns"):
        On(ours="id", op="==", theirs="workbook_id")


def test_ordered_comparison_against_an_identity_is_refused():
    for op in ("<=", ">=", "<", ">"):
        with pytest.raises(DefinitionError, match="ordered comparison"):
            On(ours="account_id", op=op, theirs="id")


# ── assembly refusals: the promoted-fields law, unweakened ───────────────

def _anchor_variant(data_cls, *, spec=None, links=()):
    class A(Resource):
        kind = "ibrkvar6"
        State = BreakState
        Data = data_cls
        initial = BreakState.ACTIVE
        allow_dead = {BreakState.REMOVED}
        summary = "break · {state.label}"
    A.filterable = spec if spec is not None else filterable(
        state=filterable.Eq, account_id=filterable.Eq)
    A.links = tuple(links)
    reg = Registry()
    reg.register(A)
    reg.register(IAccount)
    return A, reg


def test_identity_join_ours_side_must_stay_promoted():
    class D(BaseModel):
        account_id: str = ""
        n: int = Count(_account)

    _, reg = _anchor_variant(D, spec=filterable(state=filterable.Eq))
    with pytest.raises(DefinitionError, match="point lookup"):
        checks.check_related(reg)


def test_identity_join_across_mismatched_types_is_refused():
    numeric = Related("iacct6", on=(
        On(ours="account_no", op="==", theirs="id"),))

    class D(BaseModel):
        account_no: int = 0
        n: int = Count(numeric)

    _, reg = _anchor_variant(
        D, spec=filterable(state=filterable.Eq, account_no=filterable.Eq))
    with pytest.raises(DefinitionError,
                       match="identity join across mismatched"):
        checks.check_related(reg)


def test_link_citing_an_identity_edge_is_refused():
    """The public collection grammar has no ``id`` param; the Ref field
    itself already renders the navigable reference to the parent."""
    from waymark7 import link

    class D(BaseModel):
        account_id: str = ""

    _, reg = _anchor_variant(D, links=(link("parent", edge=_account),))
    with pytest.raises(DefinitionError, match="no collection query"):
        checks.check_related(reg)


# ── the cross-kind cycle check ───────────────────────────────────────────

def test_two_directional_cycle_is_refused_at_assembly():
    """Two kinds each deriving over the other's derived fact: the chained
    recompute could never settle it, so assembly refuses, naming the
    loop."""
    p_edge = Related("cycq6", on=(On(ours="q_id", op="==", theirs="id"),))
    q_edge = Related("cycp6", on=(On(ours="p_id", op="==", theirs="id"),))

    class PData(BaseModel):
        q_id: str = ""
        p_flag: bool = Derived(over=(p_edge.field("q_flag"),),
                               fn=lambda xs: bool(xs))

    class QData(BaseModel):
        p_id: str = ""
        q_flag: bool = Derived(over=(q_edge.field("p_flag"),),
                               fn=lambda xs: bool(xs))

    class P(Resource):
        kind = "cycp6"
        State = AcctState
        Data = PData
        initial = AcctState.OPEN
        allow_dead = {AcctState.CLOSED}
        summary = "p · {state.label}"
        filterable = filterable(state=filterable.Eq, q_id=filterable.Eq)

    class Q(Resource):
        kind = "cycq6"
        State = AcctState
        Data = QData
        initial = AcctState.OPEN
        allow_dead = {AcctState.CLOSED}
        summary = "q · {state.label}"
        filterable = filterable(state=filterable.Eq, p_id=filterable.Eq)

    reg = Registry()
    reg.register(P)
    reg.register(Q)
    with pytest.raises(DefinitionError, match="cross-kind cycle") as exc:
        checks.check_derived_cycles(reg)
    # the refusal names the loop, both facts in it
    assert "cycp6.p_flag" in str(exc.value)
    assert "cycq6.q_flag" in str(exc.value)


def test_legitimate_three_kind_chain_passes_the_cycle_check():
    """break.amount → account.total → workbook.all_ok — derived over
    derived across three kinds, plus an identity join reading a stored
    field: chains are the point; only loops are refused."""
    t_parent = Related("chm6", on=(On(ours="m_id", op="==", theirs="id"),))
    m_kids = Owns("cht6", via="m_id")
    w_kids = Owns("chm6", via="w_id")

    class TData(BaseModel):
        m_id: Ref["chm6"] = RefField(min_length=1)
        amount: float = 0.0
        parent_open: bool = Derived(over=(t_parent.field("state"),),
                                    fn=lambda s: bool(s) and s[0] == "open")

    class MData(BaseModel):
        w_id: Ref["chw6"] = RefField(min_length=1)
        total: float = Derived(over=(m_kids.field("amount"),),
                               fn=lambda xs: sum(xs))

    class WData(BaseModel):
        all_ok: bool = Derived(over=(w_kids.field("total"),),
                               fn=lambda ts: all(t == 0 for t in ts))

    def _mk(kind_, data_, spec, owns_=()):
        class R(Resource):
            kind = kind_
            State = AcctState
            Data = data_
            initial = AcctState.OPEN
            allow_dead = {AcctState.CLOSED}
            summary = "row · {state.label}"
        R.filterable = spec
        R.owns = tuple(owns_)
        return R

    reg = Registry()
    reg.register(_mk("cht6", TData, filterable(
        state=filterable.Eq, m_id=filterable.Eq,
        amount=filterable.Range)))
    reg.register(_mk("chm6", MData, filterable(
        state=filterable.Eq, w_id=filterable.Eq, total=filterable.Range),
        owns_=(m_kids,)))
    reg.register(_mk("chw6", WData, filterable(state=filterable.Eq),
                     owns_=(w_kids,)))
    checks.check_derived_cycles(reg)  # no raise: the chain is the point


# ── the live engine: forward read, invalidation, chaining ────────────────

@pytest.fixture
async def env():
    engine = waymark7.Engine(resources=[IAccount, IBreak], storage=TEST_DSN,
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


async def _post(client, href, body=None, **headers):
    return await client.post(href, json=body, headers={
        "Idempotency-Key": uuid.uuid4().hex, **headers})


async def _acct(client, fund: str) -> dict:
    res = await _post(client, "/api/iacct6s", {"fund": fund})
    assert res.status_code == 201, res.text
    return res.json()


async def _brk(client, account: dict, amount: float = 1.0) -> dict:
    res = await _post(client, "/api/ibreak6s", {
        "account_id": account["self"].rsplit("/", 1)[-1], "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


async def _fresh(client, doc) -> dict:
    return (await client.get(doc["self"])).json()["data"]


async def assert_conform(engine, kind: str, doc) -> None:
    """The §2 conformance proof: recompute-from-inputs == stored value."""
    rid = doc["self"].rsplit("/", 1)[-1]
    rdef = engine.registry[kind]
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, kind, rid)
        fresh = await engine.invoker.derived.compute(
            s, instance, rdef, now=engine.invoker.clock())
    stored = instance.data.model_dump()
    for field, value in fresh.items():
        assert stored[field] == value, \
            f"{kind}.{field}: stored {stored[field]!r} != derived {value!r}"


async def test_the_child_fact_is_born_from_the_forward_read(env):
    """Create materializes the identity join by loading the parent the
    input's ref selects — a break is born knowing its account's state."""
    engine, client = env
    acct = await _acct(client, "alpha")
    brk = await _brk(client, acct)
    assert brk["data"]["account_is_open"] is True

    closed = await _acct(client, "beta")
    await _post(client, f"{closed['self']}/-/close")
    brk2 = await _brk(client, closed)
    assert brk2["data"]["account_is_open"] is False
    await assert_conform(engine, "ibreak6", brk)
    await assert_conform(engine, "ibreak6", brk2)


async def test_parent_transition_flips_child_fact_and_downstream_in_one_request(env):
    """The new invalidation direction and the chain, in one act: closing
    the account (a) flips both of its breaks' identity-join facts via the
    inverted point lookup on ``account_id``, (b) does not leak into the
    other account's break, and (c) chains into the account's own
    downstream fact over the flipped values — visible in the close
    response itself, not a later read."""
    engine, client = env
    acct = await _acct(client, "alpha")
    brk1 = await _brk(client, acct, 1.0)
    brk2 = await _brk(client, acct, 2.0)
    other = await _acct(client, "beta")
    brk3 = await _brk(client, other, 3.0)
    assert (await _fresh(client, acct))["open_gates"] == 2

    res = await _post(client, f"{acct['self']}/-/close")
    assert res.status_code == 200, res.text
    # (c) the same request's response already carries the chained truth:
    # close → break.account_is_open → account.open_gates, one commit
    assert res.json()["data"]["open_gates"] == 0

    # (a) both breaks flipped, read-after-write, no tick, no sleep
    assert (await _fresh(client, brk1))["account_is_open"] is False
    assert (await _fresh(client, brk2))["account_is_open"] is False
    # (b) the other account's break did not leak into the recompute set
    assert (await _fresh(client, brk3))["account_is_open"] is True
    assert (await _fresh(client, other))["open_gates"] == 1

    # and back: reopen flips the same chain the other way
    res = await _post(client, f"{acct['self']}/-/reopen")
    assert res.status_code == 200, res.text
    assert res.json()["data"]["open_gates"] == 2
    assert (await _fresh(client, brk1))["account_is_open"] is True

    for kind, doc in (("iacct6", acct), ("iacct6", other),
                      ("ibreak6", brk1), ("ibreak6", brk2),
                      ("ibreak6", brk3)):
        await assert_conform(engine, kind, doc)


async def test_require_over_the_identity_fact_speaks_the_derived_sentence(env):
    """The gate ledger has hand-written three times: amend refuses
    while the account is closed, with the derivation's own generated
    sentence and remedies — and the envelope's unavailable entry says
    the identical thing."""
    _, client = env
    acct = await _acct(client, "alpha")
    brk = await _brk(client, acct)
    await _post(client, f"{acct['self']}/-/close")

    res = await _post(client, f"{brk['self']}/-/amend")
    assert res.status_code == 409, res.text
    body = res.json()
    assert body["detail"] == "The account is closed — reopen it first."
    assert body.get("remedies") == ["iacct6.reopen"]
    entry = body["resource"]["unavailable"]["amend"]
    assert entry["reason"] == "The account is closed — reopen it first."

    await _post(client, f"{acct['self']}/-/reopen")
    assert (await _post(client, f"{brk['self']}/-/amend")).status_code == 200


# ── backfill: an identity-join fact defined after rows exist ─────────────

class BAcctData(BaseModel):
    fund: str = Field(default="", max_length=40)


class BAcct(Resource):
    kind = "bacct6"
    State = AcctState
    Data = BAcctData
    initial = AcctState.OPEN
    summary = "account · {state.label}"
    filterable = filterable(state=filterable.Eq)

    @action(from_=AcctState.OPEN, to=AcctState.CLOSED,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=AcctState.CLOSED, to=AcctState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass


def make_bbreak(with_fact: bool):
    """Two laws for one kind (the test_backfill pattern): the second adds
    an identity-join fact to an app whose rows already exist."""
    edge = Related("bacct6", on=(
        On(ours="account_id", op="==", theirs="id"),))

    if with_fact:
        class BD(BaseModel):
            account_id: Ref["bacct6"] = RefField(min_length=1)
            account_is_open: bool = Derived(
                over=(edge.field("state"),),
                fn=lambda states: bool(states) and states[0] == "open")
    else:
        class BD(BaseModel):
            account_id: Ref["bacct6"] = RefField(min_length=1)

    class B(Resource):
        kind = "bbrk6"
        State = BreakState
        Data = BD
        initial = BreakState.ACTIVE
        terminal = {BreakState.REMOVED}
        summary = "break · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                account_id=filterable.Eq)

        @action(from_=BreakState.ACTIVE, to=BreakState.REMOVED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Removes the break."))
        async def remove(self, inp: None, ctx: Ctx) -> None:
            pass

    return B


async def _boot(resources, *, drop=False):
    engine = waymark7.Engine(resources=resources, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    return engine, client


async def test_backfill_materializes_an_identity_fact_over_existing_rows():
    """A deploy that ADDS the identity-join fact recomputes every existing
    anchor through the ordinary forward read inside boot — no events, no
    version bumps — and the maintainer owns the fact from there."""
    e1, c1 = await _boot([make_bbreak(with_fact=False), BAcct], drop=True)
    try:
        res = await _post(c1, "/api/bacct6s", {"fund": "alpha"})
        assert res.status_code == 201, res.text
        acct_href = res.json()["self"]
        acct_id = acct_href.rsplit("/", 1)[-1]
        res = await _post(c1, "/api/bbrk6s", {"account_id": acct_id})
        assert res.status_code == 201, res.text
        brk_id = res.json()["self"].rsplit("/", 1)[-1]
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2, c2 = await _boot([make_bbreak(with_fact=True), BAcct])
    try:
        async with e2.storage.session() as s:
            row = await e2.storage.load(s, "bbrk6", brk_id)
        assert row.data.account_is_open is True, \
            "the boot's backfill ran the forward read per anchor"
        assert row.version == 1, \
            "backfill is maintenance: no transition, no version bump"
        # the maintainer owns the fact from here: the parent's transition
        # flips it through the new inverted direction
        res = await _post(c2, f"/api/bacct6s/{acct_id}/-/close")
        assert res.status_code == 200, res.text
        doc = (await c2.get(f"/api/bbrk6s/{brk_id}")).json()
        assert doc["data"]["account_is_open"] is False
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── the fingerprint: the identity predicate is law like any on= ─────────

def _fp_variant(theirs: str):
    edge = Related("bacct6", on=(
        On(ours="account_id", op="==", theirs=theirs),))

    class D(BaseModel):
        account_id: str = ""
        fund: str = ""
        related_open: bool = Derived(
            over=(edge.field("state"),),
            fn=lambda states: bool(states) and states[0] == "open")

    class F(Resource):
        kind = "fpbrk6"
        State = BreakState
        Data = D
        initial = BreakState.ACTIVE
        allow_dead = {BreakState.REMOVED}
        summary = "break · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                account_id=filterable.Eq,
                                fund=filterable.Eq)

    return fingerprint_of(Registry().register(F))


def test_fingerprint_changes_when_the_join_moves_to_the_identity():
    """The predicate is law: moving a join from a data field to the
    identity is a redefinition — the fingerprint says so, and the facet
    spells theirs='id' verbatim."""
    fp_field = _fp_variant("fund")
    fp_id = _fp_variant("id")
    assert fingerprint_hash(fp_field) != fingerprint_hash(fp_id)
    facet = fp_id["derived"]["related_open"]["over"][0]["related"]
    assert facet["on"] == [
        {"ours": "account_id", "op": "==", "theirs": "id"}]
