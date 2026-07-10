"""The expression language (design 8.0 §1–§2, §4): the tree is the law.

Unit half of the 8.0 proof: wire round-trips exactly; evaluation matches
the lambda semantics it replaces (Tolerance parity, quantifier empties,
None discipline); declaration checks refuse what the scopes forbid; the
fingerprint stores the tree — so two spellings of one law hash alike,
and a changed leaf diffs as ``data_law`` where an ``over=`` reshape or
an fn edit stays ``code_or_shape``. The engine half (hold, pilot,
grandfather) lives in ``test_expr_law.py``.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark8
from waymark8 import Acknowledged, Ctx, Derived, E, Resource, Safety, \
    action, guard
from waymark8.core.derived import derived_specs
from waymark8.core.expr import (
    DefinitionError, Scope, check_guard_expr, expr_info, from_wire,
)
from waymark8.core.fingerprint import (
    _derived_fp, _guard_fp, classify_diff, diff_fingerprints, stale_facts,
)
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


# ── wire round-trip ──────────────────────────────────────────────────────
def test_wire_round_trips_exactly():
    trees = [
        E.f("start_date") + E.days(7 * E.f("weeks") - 1),
        E.all(E.f("days"), E.it.meal_id.is_set() | E.it.eating_out.eq(True)),
        E.f("difference").abs() <= E.num("0.00001"),
        E.any(E.f("event.kind"), E.it.eq("blocking")),
        E.count(E.f("items.id")),
        E.sum(E.f("items.amount"), where=E.it > 0),
        E.max(E.num("0.01"), E.num("0.001") * E.f("base")),
        E.date(E.now()) >= E.data("start_date"),
        ~E.data("has_conflicts"),
        (E.input("qty") <= E.data("limit")) & E.input("sku").is_set(),
    ]
    for tree in trees:
        wire = tree.to_wire()
        assert from_wire(wire).to_wire() == wire


def test_wire_refuses_what_it_cannot_read_back():
    with pytest.raises(DefinitionError):
        from_wire({"call": {"fn": "os.system"}})
    with pytest.raises(DefinitionError):
        from_wire({"cmp": {"op": "matches", "left": {"lit": 1},
                           "right": {"lit": 2}}})
    with pytest.raises(DefinitionError):
        from_wire({"lit": 1, "var": "x"})  # one node, one key


# ── evaluation semantics: what the lambdas relied on, kept ───────────────
def test_tolerance_parity_including_none():
    tol = E.f("difference").abs() <= E.num("0.00001")
    assert tol.eval(Scope(vars={"difference": 0.000009})) is True
    assert tol.eval(Scope(vars={"difference": -0.000009})) is True
    assert tol.eval(Scope(vars={"difference": 0.2})) is False
    # a missing value satisfies no ordering claim — Tolerance's None→False
    assert tol.eval(Scope(vars={"difference": None})) is False


def test_decimal_promotes_what_it_meets():
    # decimal-literal arithmetic is exact end to end
    assert (E.num("0.1") + E.num("0.2")).eval(Scope()) == Decimal("0.3")
    assert ((E.num("0.1") + E.num("0.2")) <= E.num("0.3")).eval(
        Scope()) is True
    # a stored float promotes through Decimal(str(x)) at the comparison —
    # the Tolerance discipline verbatim: the value's own float error is
    # kept (it is the stored truth), the literal's is never introduced
    assert (E.f("d") <= E.num("0.1")).eval(Scope(vars={"d": 0.1})) is True
    assert (E.f("d") <= E.num("0.3")).eval(
        Scope(vars={"d": 0.1 + 0.2})) is False  # 0.30000000000000004, honestly


def test_quantifiers_keep_python_empty_semantics():
    assert E.all(E.f("xs"), E.it).eval(Scope(vars={"xs": []})) is True
    assert E.any(E.f("xs"), E.it).eval(Scope(vars={"xs": []})) is False
    assert E.count(E.f("xs")).eval(Scope(vars={"xs": []})) == 0
    assert E.sum(E.f("xs")).eval(Scope(vars={"xs": []})) == 0
    assert E.sum(E.f("xs")).eval(Scope(vars={"xs": [1, None, 2.5]})) == 3.5
    assert E.count(E.f("xs"), where=E.it.eq("blocking")).eval(
        Scope(vars={"xs": ["fyi", "blocking", "blocking"]})) == 2


def test_arithmetic_over_missing_is_missing_ordering_is_false():
    assert (E.f("a") + 1).eval(Scope(vars={"a": None})) is None
    assert (E.f("a") > 1).eval(Scope(vars={"a": None})) is False
    assert (E.f("a") <= 1).eval(Scope(vars={"a": None})) is False
    assert E.f("a").eq(None).eval(Scope(vars={"a": None})) is True


def test_date_arithmetic_and_coercion():
    end = E.f("start") + E.days(7 * E.f("weeks") - 1)
    assert end.eval(Scope(vars={"start": date(2026, 6, 30), "weeks": 2})) \
        == date(2026, 7, 13)
    started = E.date(E.now()) >= E.data("start_date")

    class Row:
        start_date = date(2026, 7, 9)

    assert started.eval(Scope(
        data=Row(), now=datetime(2026, 7, 9, 3, tzinfo=UTC))) is True
    assert started.eval(Scope(
        data=Row(), now=datetime(2026, 7, 8, 23, tzinfo=UTC))) is False


def test_item_access_reaches_into_models_and_mappings():
    class Item(BaseModel):
        have: bool = False
        name: str = "x"

    covered = E.all(E.f("items"), E.it.have)
    assert covered.eval(Scope(vars={"items": [Item(have=True)]})) is True
    assert covered.eval(Scope(vars={"items": [Item()]})) is False
    assert covered.eval(Scope(vars={"items": [{"have": True}]})) is True


# ── declaration checks (design §1: refused at import, not at eval) ───────
def test_it_outside_a_quantifier_is_refused():
    with pytest.raises(DefinitionError, match="quantifier"):
        Derived(over=("days",), expr=E.it.have)


def test_unknown_input_names_are_refused():
    with pytest.raises(DefinitionError, match="not in"):
        Derived(over=("amount",), expr=E.f("amuont") <= 1)


def test_guard_scope_in_a_derivation_is_refused():
    with pytest.raises(DefinitionError, match="guard-scope"):
        Derived(over=("amount",), expr=E.data("amount") <= 1)


def test_bare_vars_in_a_guard_are_refused():
    with pytest.raises(DefinitionError, match="derived calling convention"):
        check_guard_expr(E.f("amount") <= 1, "g")


def test_exactly_one_semantic_arm():
    with pytest.raises(DefinitionError, match="exactly one"):
        Derived(over=("a",), expr=E.f("a"), fn=lambda a: a)
    with pytest.raises(DefinitionError, match="exactly one"):
        Derived(over=("a",))


def test_ambiguous_input_names_are_refused():
    from waymark8 import Owns

    edge = Owns("child", via="parent_id")
    with pytest.raises(DefinitionError, match="unambiguous"):
        Derived(over=(edge.field("state"), edge.field("state")),
                expr=E.count(E.f("child.state")))


# ── the fingerprint reads the tree (design §2) ───────────────────────────
def _wb(expr):
    class WbData(BaseModel):
        amount: float = 0.0
        base: float = 1.0
        reconciled: bool = Derived(over=("amount", "base"), expr=expr)

    class Wb:
        Data = WbData

    return Wb


def test_two_spellings_one_law():
    # built in different orders / with intermediate names — same tree
    a = _wb(E.f("amount").abs() <= E.num("0.05"))
    threshold = E.num("0.05")
    b = _wb(E.f("amount").abs().__le__(threshold))
    assert _derived_fp(a) == _derived_fp(b)


def test_expr_leaf_diff_is_data_law_over_reshape_is_not():
    v1 = _wb(E.f("amount").abs() <= E.num("0.05"))
    v2 = _wb(E.f("amount").abs()
             <= E.max(E.num("0.01"), E.num("0.001") * E.f("base")))
    diff = diff_fingerprints({"derived": _derived_fp(v1)},
                             {"derived": _derived_fp(v2)})
    assert classify_diff(diff) == "data_law", \
        "a changed expression is stored parameters — holdable, pilotable"
    assert stale_facts(diff) == ("reconciled",)

    class V3Data(BaseModel):
        amount: float = 0.0
        base: float = 1.0
        extra: float = 0.0
        reconciled: bool = Derived(over=("amount", "base", "extra"),
                                   expr=E.f("amount").abs() <= E.f("extra"))

    class V3:
        Data = V3Data

    reshape = diff_fingerprints({"derived": _derived_fp(v1)},
                                {"derived": _derived_fp(V3)})
    assert classify_diff(reshape) == "code_or_shape", \
        "a changed over= changes the invalidation map — not overlayable"


def test_fn_edit_still_classifies_code_or_shape():
    def mk(fn):
        class D(BaseModel):
            amount: float = 0.0
            base: float = 1.0
            reconciled: bool = Derived(over=("amount", "base"), fn=fn)

        class C:
            Data = D

        return C

    v1 = mk(lambda a, b: abs(a) <= 0.05)
    v2 = mk(lambda a, b: abs(a) <= max(0.01, 0.001 * b))
    diff = diff_fingerprints({"derived": _derived_fp(v1)},
                             {"derived": _derived_fp(v2)})
    assert classify_diff(diff) == "code_or_shape", \
        "the same change spelled as code keeps 7.0's total-promote fate"


def test_x_derived_carries_the_expr_and_the_fingerprint_strips_it():
    from waymark8.core.fingerprint import _strip_derived_marks

    cls = _wb(E.f("amount").abs() <= E.num("0.05"))
    schema = cls.Data.model_json_schema(mode="serialization")
    hint = schema["properties"]["reconciled"]["x-derived"]
    assert hint["expr"] == (E.f("amount").abs() <= E.num("0.05")).to_wire()
    stripped = _strip_derived_marks(schema)
    assert "x-derived" not in stripped["properties"]["reconciled"]


# ── guard.expr (design §4): the judgment as a tree ───────────────────────
def test_guard_expr_declares_from_the_tree():
    g = guard.expr(when=E.date(E.now()) >= E.data("start_date"),
                   explain="The plan starts {start}.",
                   vars={"start": E.data("start_date")},
                   name="plan_started")
    assert g.judges == ()
    assert g.reads == ("now",)
    assert g.needs_input is False
    fp = _guard_fp(g)
    assert fp["check"] is None
    assert fp["expr"] == (E.date(E.now()) >= E.data("start_date")).to_wire()
    assert fp["vars_exprs"] == {"start": E.data("start_date").to_wire()}

    g2 = guard.expr(when=E.input("qty") <= E.data("limit"),
                    explain="At most {limit}.",
                    vars={"limit": E.data("limit")}, name="under_limit")
    assert g2.judges == ("qty",)
    assert g2.reads == ()
    assert g2.needs_input is True


class GState(StrEnum):
    OPEN = "open"
    STARTED = "started"


class GateInput(BaseModel):
    qty: int = Field(ge=0)


def make_gated():
    class GateData(BaseModel):
        start_date: date
        limit: int = 3
        taken: int = 0

    class Gated(Resource):
        kind = "xgate"
        State = GState
        Data = GateData
        initial = GState.OPEN
        terminal = {GState.STARTED}
        summary = "Gate · {state.label}"

        @action(from_=GState.OPEN, to=GState.STARTED,
                guards=[guard.expr(
                    when=E.date(E.now()) >= E.data("start_date"),
                    explain="The gate opens {start}.",
                    vars={"start": E.data("start_date")},
                    name="gate_started")],
                safety=Safety(idempotent=True, reversible=False,
                              confirm=False,
                              one_way=Acknowledged(
                                  "Starting reflects the calendar; "
                                  "nothing is lost.")))
        async def begin(self, inp: None, ctx: Ctx) -> None:
            pass

        @action(from_=GState.OPEN, to=GState.OPEN, input=GateInput,
                guards=[guard.expr(
                    when=E.input("qty") <= E.data("limit"),
                    explain="At most {limit} at a time.",
                    vars={"limit": E.data("limit")}, name="under_limit")],
                safety=Safety(idempotent=True, reversible=False,
                              confirm=False))
        async def take(self, inp: GateInput, ctx: Ctx) -> None:
            self.data.taken += inp.qty

    return Gated


async def test_guard_expr_advertises_and_enforces_one_tree():
    engine = waymark8.Engine(resources=[make_gated()], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "elena"})

    async def post(href, json=None):
        return await client.post(
            href, json=json, headers={"Idempotency-Key": uuid.uuid4().hex})

    try:
        tomorrow = (datetime.now(UTC) + timedelta(days=1)).date()
        res = await post("/api/xgates", {"start_date": tomorrow.isoformat()})
        assert res.status_code == 201, res.text
        doc = res.json()
        # the advertisement: refused-with-reason, rendered from the tree's
        # vars — the same tree the invoke below enforces
        assert "begin" not in doc["actions"]
        entry = doc["unavailable"]["begin"]
        assert entry["reason"] == f"The gate opens {tomorrow.isoformat()}."
        res = await post(doc["self"] + "/-/begin")
        assert res.status_code == 409
        assert res.json()["detail"] == \
            f"The gate opens {tomorrow.isoformat()}."

        # input judgment: advertised (pending input), enforced on invoke
        assert "take" in doc["actions"]
        res = await post(doc["self"] + "/-/take", {"qty": 5})
        assert res.status_code == 409
        assert res.json()["detail"] == "At most 3 at a time."
        res = await post(doc["self"] + "/-/take", {"qty": 2})
        assert res.status_code == 200, res.text
        assert res.json()["data"]["taken"] == 2

        yesterday = (datetime.now(UTC) - timedelta(days=1)).date()
        res = await post("/api/xgates",
                         {"start_date": yesterday.isoformat()})
        doc = res.json()
        assert "begin" in doc["actions"]
        res = await post(doc["self"] + "/-/begin")
        assert res.status_code == 200, res.text
        assert res.json()["state"] == "started"
    finally:
        await client.aclose()
        await engine.shutdown()


# ── the library rollups are expression-bodied now ────────────────────────
def test_count_and_sum_declare_expressions():
    from waymark8 import Owns

    edge = Owns("ift", via="wire_id")

    class WireData(BaseModel):
        open_ifts: int = waymark8.Count(edge, where={"state": ("open",)})
        total: float = waymark8.Sum(edge, "amount")

    specs = derived_specs(WireData)
    assert specs["open_ifts"].expr is not None
    assert specs["open_ifts"].fn is None
    assert specs["open_ifts"].apply([["a", "b", "c"]]) == 3
    assert specs["total"].apply([[1.5, None, 2.0]]) == 3.5
    info = expr_info(specs["total"].expr)
    assert info.vars == {"ift.amount"}
