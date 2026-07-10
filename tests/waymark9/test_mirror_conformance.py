"""Mirror-aware conformance (7.x extension): the generic suite can now walk
a pure :class:`~waymark9.server.external.Mirror` — a resource that breaks
*both* of the walker's standing assumptions at once (reads are pure; states
come from principal-invoked transitions).

A Mirror's sync states (``fresh/stale/conflicted/unreachable``) are
**un-drivable** — the transitions that enter them are ``system_only`` — and
**un-observable** — pull-through-on-read heals a stale/unreachable mirror on
the very GET a representation check makes. This test proves the two seams
that close that gap:

- the Mirror-aware state factory stages every sync state through the system
  actor (``waymark9.testing.factories.make_mirror_state``), and
- the default-off ``_suppress_mirror_refresh`` seam lets the suite read a
  staged state back without the read changing it —

by running the real conformance case functions against a minimal Mirror, and
by asserting the seam is **off** on a plain engine (which still pulls
through on read).
"""
from __future__ import annotations

import os
import uuid

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import Field
from types import SimpleNamespace

import waymark9
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.server.external import Discover, Mirror, MirrorMeta, SyncState
from waymark9.testing import (
    factories as reg,
    per_worker_dsn,
)
from waymark9.testing import conformance as conf
from waymark9 import filterable

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


# ── a scriptable external source (the mirrored truth) ───────────────────
class FakeSource:
    """The dev/test twin of a real boundary (crib of ``FakeTodoList`` /
    ``FakeFunds``): an unseeded id auto-vivifies to a readable document, so
    the walker can mint any mirror without hand-seeding every field;
    ``push`` is tolerant (it accepts the current etag and advances), so a
    ``reconcile`` keeping *ours* succeeds; ``discoverable`` feeds the clock
    sweep, kept separate so no mint happens until a test asks."""

    def __init__(self) -> None:
        self.docs: dict[str, dict] = {}
        self.etags: dict[str, str] = {}
        self.discoverable: list[str] = []
        self.down = False
        self._n = 0

    def _next_etag(self) -> str:
        self._n += 1
        return f"etag-{self._n}"

    def _doc(self, external_id: str) -> dict:
        if external_id in self.docs:
            return dict(self.docs[external_id])
        return {"label": external_id.replace("-", " ").replace("_", " ").title()}

    async def pull(self, external_id: str):
        if self.down:
            raise ConnectionError("source unreachable")
        if external_id not in self.etags:
            self.docs[external_id] = self._doc(external_id)
            self.etags[external_id] = self._next_etag()
        return dict(self.docs[external_id]), self.etags[external_id]

    async def push(self, external_id: str, document: dict, *, etag):
        if self.down:
            raise ConnectionError("source unreachable")
        self.docs[external_id] = dict(document)
        self.etags[external_id] = self._next_etag()
        return self.etags[external_id]

    async def discover(self) -> list[str]:
        if self.down:
            raise ConnectionError("source unreachable")
        return list(self.discoverable)


SOURCE = FakeSource()


class NoteMirrorData(MirrorMeta):
    label: str | None = Field(
        default=None, max_length=120,
        description="The note's label, per the source")


class NoteMirror(Mirror):
    kind = "note_mirror"
    Data = NoteMirrorData

    adapter = SOURCE
    ttl_seconds = 300
    push_on_write = True  # a read/write mirror: conflicted is reachable
    discover = Discover(every=300.0)

    # a state-varying summary (like the framework's GroceryMirror): a
    # Mirror's whole point is that the same document can be fresh or stale,
    # so the summary must orient by the sync state, not only the data
    summary = "Note · {state.label}"

    filterable = filterable(
        external_id=filterable.Eq,
        state=filterable.Eq | filterable.In,
    )


# NoteMirror is deliberately NOT enrolled via ``conformance_resource``: that
# writes to the process-global conformance registry, and under ``--waymark9``
# the plugin would then parametrize the whole suite over ``note_mirror``
# against the *app* engine (mealplan7 + ledger7), which never registers it
# — a flood of KeyErrors. This proof drives the conformance case functions
# directly against its own single-mirror engine below; ``reg.make_state``
# routes ``note_mirror`` to the Mirror-aware factory off ``engine.registry``,
# so no global enrollment is needed to exercise the extension.
@pytest.fixture
async def wm9():
    """A conformance environment for the mirror, mirroring the --waymark9
    plugin's ``wm9`` fixture — including the walker-scoped suppression seam."""
    SOURCE.docs.clear()
    SOURCE.etags.clear()
    SOURCE.discoverable.clear()
    SOURCE.down = False
    NoteMirror.adapter = SOURCE

    engine = waymark9.Engine(resources=[NoteMirror], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    # the seam the real plugin sets: suppress pull-through for the suite's
    # reads, so a staged sync state survives the representation GET
    engine._suppress_mirror_refresh = True

    profiles = reg.principals()

    def test_principal(request):
        name = request.headers.get("X-Waymark-Test-Principal", "anonymous")
        return profiles[name]

    engine.principal = test_principal
    app = FastAPI()
    app.include_router(engine.router, prefix=engine.base_path)
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport,
                         base_url="http://waymark9-conformance")
    try:
        yield SimpleNamespace(
            engine=engine, client=client, registry=engine.registry,
            storage=engine.storage, principals=profiles,
            base=engine.base_path)
    finally:
        await client.aclose()
        await transport.aclose()
        await engine.shutdown()


KIND = "note_mirror"
PRINCIPALS = sorted(reg.principals())
SYNC_STATES = [s.value for s in SyncState]


# The conformance case functions ``pytest.skip`` for cases that don't apply
# to a given kind (a hidden action has no principal, a state isn't
# producible). Looping many cases through one engine — rather than spawning
# one engine per parametrized case — keeps this proof to a handful of
# engines (a Mirror case builds a fresh engine per case is ~100 engines and
# exhausts the connection pool); a per-case skip must not skip the whole
# loop, so we swallow the expected ones and let only real failures raise.
_SKIPPED = pytest.skip.Exception


# ── the read stays pure: staging survives the representation GET ────────
async def test_representation_holds_the_staged_states(wm9):
    """The crux: stage each sync state, GET it, and the read must show that
    state — not the ``fresh`` a pull-through would have healed it to. Run
    across every principal profile, on one engine."""
    ran = 0
    for state in SYNC_STATES:
        for pname in PRINCIPALS:
            try:
                await conf.test_representation(wm9, (KIND, state, pname))
                ran += 1
            except _SKIPPED:
                continue
    # every sync state × principal is producible for this read/write mirror
    assert ran == len(SYNC_STATES) * len(PRINCIPALS)


async def test_affordance_and_prose(wm9):
    """The system-only sync transitions are hidden, not advertised — and
    invoking a hidden action either 404s or (for an idempotent mark already
    in its target state) naturally replays without advancing. Prose stays
    free of machine tokens across every staged state."""
    for state in SYNC_STATES:
        await conf.test_affordance_completeness(wm9, (KIND, state, "owner"))
        await conf.test_token_prose(wm9, (KIND, state, "owner"))


async def test_orientation(wm9):
    """Summaries differ across the staged sync states — a Mirror that could
    only ever be walked to ``fresh`` could never prove this."""
    await conf.test_orientation(wm9, KIND)


async def test_collection_contract(wm9):
    await conf.test_collection_contract(wm9, KIND)


async def test_replay_history(wm9):
    """Every recorded sync transition (including the system-actor
    ``observe_external`` / ``mark_*`` writes the factory staged) replays
    legally under its own anchored law — the Mirror's marks are real
    declared transitions, not same-state bookkeeping."""
    await conf.test_replay_history(wm9, KIND)


async def test_events(wm9):
    """A mirror has no principal-invokable action from its ``fresh`` initial
    state (the sync marks are system-only, ``reconcile`` is out of state),
    so the generic events test skips — which is the honest outcome, not a
    failure."""
    try:
        await conf.test_events(wm9, KIND)
    except _SKIPPED:
        pytest.skip("a mirror advertises no principal action from fresh")


# ── the sync transitions are exempt from the principal×action matrix ────
SYNC_ACTIONS = {"observe_external", "mark_stale", "mark_unreachable",
                "mark_conflicted"}


async def test_sync_transitions_are_hidden_not_advertised(wm9):
    """The system-only sync transitions are exempt from the principal×action
    matrix: hidden for every walker principal, advertised in neither
    ``actions`` nor ``unavailable`` — so ``test_transition_truth_available``
    finds no principal to demand them of (it skips), exactly as it does for
    job/attachment's system actions."""
    for state in SYNC_STATES:
        instance = await reg.make_state(KIND, state, wm9.engine)
        for pname in PRINCIPALS:
            doc = await conf.fetch(wm9, KIND, instance.id, pname)
            advertised = set(doc["actions"]) | set(doc["unavailable"])
            leaked = SYNC_ACTIONS & advertised
            assert not leaked, (
                f"a system-only sync transition is advertised to {pname} in "
                f"{state}: {sorted(leaked)}")


async def test_reconcile_is_a_real_principal_transition(wm9):
    """``reconcile`` (conflicted → fresh) is the one principal-driven sync
    transition — a person picks the winner, never a silent last-writer-wins.
    It walks through the full transition-truth / input-contract / safety
    battery like any human action, both where it is available and where it
    is out of state."""
    await conf.test_transition_truth_available(
        wm9, (KIND, "conflicted", "reconcile"))
    await conf.test_input_contract(wm9, (KIND, "conflicted", "reconcile"))
    await conf.test_safety_idempotent_double_invoke(
        wm9, (KIND, "conflicted", "reconcile"))
    # out of its from-state, reconcile is honestly unavailable and refuses
    await conf.test_transition_truth_unavailable(
        wm9, (KIND, "fresh", "reconcile"))


# ── staging honesty: each sync state is produced as asked ───────────────
async def test_factory_stages_each_sync_state(wm9):
    for state in SYNC_STATES:
        instance = await reg.make_state(KIND, state, wm9.engine)
        assert instance.state == state
        # `fresh` carried the authority's real fields (pulled at staging),
        # so the mirror is not an empty shell
        if state == SyncState.FRESH:
            assert instance.data.label, "a staged fresh mirror must carry data"


# ── Discover-minted instances don't break the walk ──────────────────────
async def test_discover_minted_instance_reads_cleanly(wm9):
    """The clock sweep mints a resource carrying only ``external_id`` (design
    §8); its fields arrive on the first pull-through. With suppression on it
    reads as a valid `fresh` envelope (no pull yet) — the walk is unharmed.

    Discovery is driven through ``run_discovery`` with a future clock: the
    engine's startup already swept once (minting nothing, since
    ``discoverable`` was empty) and stamped the per-kind cursor, so a
    same-instant ``tick()`` would be throttled inside the sweep interval."""
    from datetime import timedelta

    from waymark9.server.external import run_discovery

    SOURCE.discoverable = ["deal-9001"]
    future = wm9.engine.invoker.clock() + timedelta(days=2)
    minted = await run_discovery(wm9.engine, future)
    assert minted == 1

    async with wm9.storage.session() as s:
        rows, _ = await wm9.storage.query(
            s, KIND, filters={"external_id": "deal-9001"}, sort=None,
            page_size=1, page_number=1)
    assert rows, "discovery minted no resource"
    doc = await conf.fetch(wm9, KIND, rows[0].id, PRINCIPALS[0])
    assert doc["state"] == "fresh"
    assert set(conf.ENVELOPE_KEYS) <= set(doc)


# ── the suppression seam is DEFAULT-OFF (the critical proof) ────────────
async def test_suppression_seam_is_default_off():
    """A plain engine — no ``_suppress_mirror_refresh`` — still pulls through
    on read: a mirror observed unreachable heals to fresh on the next GET.
    Production behavior is unchanged; only the suite opts into suppression."""
    SOURCE.docs.clear()
    SOURCE.etags.clear()
    SOURCE.down = False
    NoteMirror.adapter = SOURCE

    engine = waymark9.Engine(resources=[NoteMirror], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    assert not getattr(engine, "_suppress_mirror_refresh", False), \
        "a plain engine must not carry the walker's suppression seam"
    app = FastAPI()
    app.include_router(engine.router, prefix=engine.base_path)
    transport = ASGITransport(app=app)
    owner = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana"}
    client = AsyncClient(transport=transport,
                         base_url="http://t", headers=owner)
    try:
        res = await client.post(
            f"{engine.base_path}/note_mirrors",
            json={"external_id": "note-1"},
            headers={**owner, "Idempotency-Key": uuid.uuid4().hex})
        assert res.status_code == 201, res.text
        href = res.json()["self"]

        # the source is dark before the mirror's first (unsynced) read: the
        # read pulls through, fails, and the mirror honestly becomes
        # unreachable — the read is NOT pure, exactly the behavior the suite
        # must suppress to hold a staged state still
        SOURCE.down = True
        doc = (await client.get(href)).json()
        assert doc["state"] == "unreachable", \
            "a plain engine must pull through on read (seam default-off)"

        # recovery: the source returns, and the next read observes it and
        # heals to fresh — the read changed the observed state, unsuppressed
        SOURCE.down = False
        doc = (await client.get(href)).json()
        assert doc["state"] == "fresh"
        assert doc["data"]["label"]
    finally:
        await client.aclose()
        await transport.aclose()
        await engine.shutdown()
