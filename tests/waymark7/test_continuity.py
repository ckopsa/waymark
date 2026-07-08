"""History keeps its own law (design §5).

The continuity map: v1's ``renames`` covered states; ``renamed_actions``
covers the action vocabulary. A deploy that renames an action without
declaring the map refuses to boot — with a message that names the
declaration to write — and with the map declared, the log reads
continuously across the rename: the replay conformance proves every
recorded write legal under the revision it is anchored to, a corrupted
anchor or hand-edited row is a loud failure, and the pre-law horizon
stays skipped, never guessed at.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime
from enum import StrEnum

import pytest
from pydantic import BaseModel, Field

import waymark7
from waymark7 import Ctx, Principal, Resource, Safety, action
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn
from waymark7.testing.conformance import replay_history

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

DANA = Principal(id="dana", type="human", display="Dana")


class TaskState(StrEnum):
    DRAFT = "draft"
    APPROVED = "approved"


class TaskData(BaseModel):
    title: str = Field(min_length=1, max_length=80)


def make_task_v1():
    """The original law: ``approve`` moves draft → approved."""

    class CTask(Resource):
        kind = "ctask"
        State = TaskState
        Data = TaskData
        initial = TaskState.DRAFT
        terminal = {TaskState.APPROVED}
        summary = "{data.title} · {state.label}"

        @action(from_=TaskState.DRAFT, to=TaskState.APPROVED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="The task is locked."),
                display=dict(label="Approve"))
        async def approve(self, inp: None, ctx: Ctx) -> None:
            pass

    return CTask


def make_task_v2(renamed: dict[str, str] | None = None):
    """The renamed law: ``approve`` became ``authorize``. With no
    continuity map, two years of audit log would read as invocations of
    an action that doesn't exist."""

    class CTask(Resource):
        kind = "ctask"
        State = TaskState
        Data = TaskData
        initial = TaskState.DRAFT
        terminal = {TaskState.APPROVED}
        summary = "{data.title} · {state.label}"
        renamed_actions = renamed or {}

        @action(from_=TaskState.DRAFT, to=TaskState.APPROVED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="The task is locked."),
                display=dict(label="Authorize"))
        async def authorize(self, inp: None, ctx: Ctx) -> None:
            pass

    return CTask


def _engine(task_cls):
    return waymark7.Engine(resources=[task_cls], storage=TEST_DSN,
                           principal=header_principal, services=None,
                           bus=InProcessBus())


async def _boot(task_cls, *, drop: bool = False):
    engine = _engine(task_cls)
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


async def _mk_task(engine) -> str:
    res = await engine.invoker.create("ctask", {"title": "Q3 budget"},
                                      principal=DANA,
                                      idempotency_key=uuid.uuid4().hex)
    return res.doc["self"].rsplit("/", 1)[-1]


async def _seed_v1_history():
    """Boot the original law and record an ``approve`` in the log."""
    engine = await _boot(make_task_v1(), drop=True)
    tid = await _mk_task(engine)
    await engine.invoker.invoke("ctask", tid, "approve", None,
                                principal=DANA)
    await engine.shutdown()
    return tid


async def _revisions(engine):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": "ctask"},
            sort="revision", page_size=100, page_number=1)
    return rows


async def _fabricate(engine, *, action_name: str, defined_by: str | None,
                     from_state: str = "draft",
                     to_state: str = "approved") -> None:
    async with engine.storage.session() as s:
        await s.execute(engine.storage.transitions.insert().values(
            kind="ctask", resource_id="ghost-row", action=action_name,
            from_state=from_state, to_state=to_state, version=7,
            actor_type="human", actor_id="mallory",
            actor_display="Mallory", input_digest="", correlation_id=None,
            summary="a fabricated row", at=datetime.now(UTC),
            defined_by=defined_by))


# ── the boot refusal names the missing declaration ───────────────────────
async def test_renamed_action_without_declaration_refuses_boot():
    await _seed_v1_history()

    engine = _engine(make_task_v2())  # no renamed_actions declared
    try:
        with pytest.raises(RuntimeError) as err:
            await engine.startup()
        message = str(err.value)
        assert "ctask" in message
        assert "'approve'" in message, \
            "the refusal must name the orphaned action"
        assert 'renamed_actions={"approve": "<current action>"}' in message, \
            "the error tells you what to write"
        assert "CTask" in message
    finally:
        await engine.bus.stop()
        await engine.storage.engine.dispose()


async def test_declared_rename_boots_and_replays_across_it():
    await _seed_v1_history()

    engine = await _boot(make_task_v2({"approve": "authorize"}))
    try:
        # the map is data on the revise: the revision's diff names it,
        # classified truth (§4's tag)
        rev1, rev2 = await _revisions(engine)
        assert rev2.state == "current"
        added = {e["path"]: e["class"] for e in rev2.data.diff["added"]}
        assert added.get("renamed_actions.approve") == "truth", added

        # post-rename history under the new law
        tid = await _mk_task(engine)
        await engine.invoker.invoke("ctask", tid, "authorize", None,
                                    principal=DANA)

        # the replay check reads the whole log — the approve rows legal
        # under revision 1, the authorize rows under revision 2
        checked = await replay_history(engine.storage, engine.registry,
                                       "ctask")
        assert checked == 4  # create+approve (v1), create+authorize (v2)

        # forward mapping: a row carrying the OLD spelling anchored to
        # the post-rename revision is read through the declared chain
        await _fabricate(engine, action_name="approve",
                         defined_by=rev2.id)
        assert await replay_history(engine.storage, engine.registry,
                                    "ctask") == 5
    finally:
        await engine.shutdown()


# ── the replay check fails loudly on lies ────────────────────────────────
async def test_replay_fails_on_illegal_anchored_row():
    await _seed_v1_history()
    engine = await _boot(make_task_v2({"approve": "authorize"}))
    try:
        _, rev2 = await _revisions(engine)
        # an action no revision ever declared, anchored to real law
        await _fabricate(engine, action_name="reject", defined_by=rev2.id)
        with pytest.raises(AssertionError, match="no declared rename chain"):
            await replay_history(engine.storage, engine.registry, "ctask")
    finally:
        await engine.shutdown()


async def test_replay_fails_on_illegal_states_under_anchored_law():
    await _seed_v1_history()
    engine = await _boot(make_task_v2({"approve": "authorize"}))
    try:
        _, rev2 = await _revisions(engine)
        # right action, impossible edge under that law
        await _fabricate(engine, action_name="authorize",
                         defined_by=rev2.id,
                         from_state="approved", to_state="approved")
        with pytest.raises(AssertionError, match="from_state is not among"):
            await replay_history(engine.storage, engine.registry, "ctask")
    finally:
        await engine.shutdown()


async def test_anchor_integrity_failure_detected():
    await _seed_v1_history()
    engine = await _boot(make_task_v2({"approve": "authorize"}))
    try:
        await _fabricate(engine, action_name="authorize",
                         defined_by="f" * 32)
        with pytest.raises(AssertionError,
                           match="names no definition revision"):
            await replay_history(engine.storage, engine.registry, "ctask")
    finally:
        await engine.shutdown()
