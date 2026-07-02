"""Every branch of the §11.3 transition algorithm, direct-call, real Postgres."""
import json
import uuid

import pytest
from sqlalchemy import func, select

from waymark.server.problems import (
    GuardRefused,
    IdempotencyKeyRequired,
    IdempotencyKeyReuse,
    NotFound,
    SchemaInvalid,
    VersionConflict,
    WrongState,
)

TICKET = {"title": "Broken build", "priority": 3}


def key() -> str:
    return uuid.uuid4().hex


async def create_ticket(invoker, principal, **data) -> dict:
    res = await invoker.create("ticket", {**TICKET, **data}, principal=principal,
                               idempotency_key=key())
    assert res.status == 201
    return res.doc


async def transition_count(storage) -> int:
    async with storage.session() as s:
        return (await s.execute(
            select(func.count()).select_from(storage.transitions))).scalar_one()


# ── create ──────────────────────────────────────────────────────────────
async def test_create_persists_and_returns_doc(invoker, storage, alice):
    doc = await create_ticket(invoker, alice)
    assert doc["state"] == "open"
    assert doc["meta"]["version"] == 1
    assert doc["data"]["title"] == "Broken build"
    tid = doc["self"].rsplit("/", 1)[-1]
    async with storage.session() as s:
        loaded = await storage.load(s, "ticket", tid)
        assert loaded.state == "open"
        last = await storage.last_transition(s, "ticket", tid)
        assert last.action == "create"
        assert last.from_state == ""
        assert last.actor_id == "alice"


async def test_create_requires_idempotency_key(invoker, alice):
    with pytest.raises(IdempotencyKeyRequired):
        await invoker.create("ticket", TICKET, principal=alice)


async def test_create_replays_on_same_key(invoker, alice):
    k = key()
    first = await invoker.create("ticket", TICKET, principal=alice,
                                 idempotency_key=k)
    second = await invoker.create("ticket", TICKET, principal=alice,
                                  idempotency_key=k)
    assert second.body == first.body  # byte-identical
    assert second.status == 201


async def test_key_reuse_with_different_body_409(invoker, alice):
    k = key()
    await invoker.create("ticket", TICKET, principal=alice, idempotency_key=k)
    with pytest.raises(IdempotencyKeyReuse):
        await invoker.create("ticket", {**TICKET, "priority": 1},
                             principal=alice, idempotency_key=k)


# ── invoke: happy path ──────────────────────────────────────────────────
async def test_transition_moves_state_bumps_version_appends_log(invoker, storage, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    res = await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                               principal=alice)
    assert res.status == 200
    assert res.doc["state"] == "assigned"
    assert res.doc["meta"]["version"] == 2
    assert res.doc["data"]["assignee"] == "alice"  # handler effect persisted
    async with storage.session() as s:
        last = await storage.last_transition(s, "ticket", tid)
        assert (last.action, last.from_state, last.to_state, last.version) == \
            ("assign", "open", "assigned", 2)
        assert last.summary.startswith("Ticket ")
        assert last.correlation_id


async def test_response_is_post_transition_document(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    res = await invoker.invoke("ticket", tid, "assign", {"assignee": "bob"},
                               principal=alice)
    # the new document advertises next steps from the new state
    assert set(res.doc["actions"]) >= {"unassign", "resolve"}
    assert "assign" in res.doc["unavailable"]


# ── invoke: error branches, in algorithm order ──────────────────────────
async def test_unknown_kind_and_id_and_action_404(invoker, alice):
    with pytest.raises(NotFound):
        await invoker.invoke("nope", "x", "assign", {}, principal=alice)
    with pytest.raises(NotFound):
        await invoker.invoke("ticket", "missing", "assign", {"assignee": "alice"},
                             principal=alice)
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(NotFound):
        await invoker.invoke("ticket", tid, "frobnicate", {}, principal=alice)


async def test_wrong_state_409_with_advertised_reason(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(WrongState) as exc:
        await invoker.invoke("ticket", tid, "resolve", None, principal=alice)
    problem = exc.value
    # detail string-equal to what render advertises in `unavailable`
    assert problem.detail == doc["unavailable"]["resolve"]["reason"]
    assert problem.extras["becomes_available"] == {"in_states": ["assigned"]}
    assert problem.extras["resource"]["state"] == "open"


async def test_if_match_stale_412_with_fresh_resource(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                         principal=alice)
    with pytest.raises(VersionConflict) as exc:
        await invoker.invoke("ticket", tid, "resolve", None, principal=alice,
                             if_match='W/"ticket-%s-v1"' % tid)  # stale
    fresh = exc.value.extras["resource"]
    assert fresh["meta"]["version"] == 2

    ok = await invoker.invoke("ticket", tid, "resolve", None, principal=alice,
                              if_match=fresh["meta"]["etag"])
    assert ok.doc["state"] == "resolved"


async def test_if_match_missing_when_required_412(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                         principal=alice)
    with pytest.raises(VersionConflict):
        await invoker.invoke("ticket", tid, "resolve", None, principal=alice)


async def test_schema_invalid_422_field_errors(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(SchemaInvalid) as exc:
        await invoker.invoke("ticket", tid, "assign", {}, principal=alice)
    assert "assignee" in exc.value.extras["errors"]


async def test_additional_properties_rejected(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(SchemaInvalid) as exc:
        await invoker.invoke("ticket", tid, "assign",
                             {"assignee": "alice", "bogus": 1}, principal=alice)
    assert exc.value.extras["errors"] == {"bogus": ["unexpected field"]}


async def test_empty_input_action_rejects_body(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                         principal=alice)
    with pytest.raises(SchemaInvalid):
        await invoker.invoke("ticket", tid, "unassign", {"x": 1}, principal=alice)


async def test_guard_refused_409_reason_and_field_errors(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(GuardRefused) as exc:
        await invoker.invoke("ticket", tid, "assign", {"assignee": "mallory"},
                             principal=alice)
    problem = exc.value
    assert problem.detail == "Assignee mallory is not on the team."
    assert problem.extras["errors"] == {"assignee": ["unknown"]}
    assert problem.extras["resource"]["state"] == "open"
    assert problem.status == 409


async def test_guard_refused_or_composite_allows_manager(invoker, alice, manager):
    doc = await create_ticket(invoker, alice)  # priority 3
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(GuardRefused):
        await invoker.invoke("ticket", tid, "close", None, principal=alice,
                             idempotency_key=key())
    res = await invoker.invoke("ticket", tid, "close", None, principal=manager,
                               idempotency_key=key())
    assert res.doc["state"] == "closed"


# ── dry run ─────────────────────────────────────────────────────────────
async def test_dry_run_validates_without_side_effects(invoker, storage, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    before = await transition_count(storage)

    res = await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                               principal=alice, dry_run=True)
    assert res.status == 200 and json.loads(res.body) == {"valid": True}

    with pytest.raises(GuardRefused):
        await invoker.invoke("ticket", tid, "assign", {"assignee": "mallory"},
                             principal=alice, dry_run=True)
    with pytest.raises(SchemaInvalid):
        await invoker.invoke("ticket", tid, "assign", {}, principal=alice,
                             dry_run=True)

    async with storage.session() as s:
        loaded = await storage.load(s, "ticket", tid)
    assert loaded.state == "open" and loaded.version == 1
    assert await transition_count(storage) == before


# ── idempotency & safety semantics ──────────────────────────────────────
async def test_non_idempotent_requires_key_428(invoker, alice, manager):
    doc = await create_ticket(invoker, alice, priority=1)
    tid = doc["self"].rsplit("/", 1)[-1]
    with pytest.raises(IdempotencyKeyRequired):
        await invoker.invoke("ticket", tid, "close", None, principal=manager)


async def test_non_idempotent_replay_byte_identical(invoker, alice, manager):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    k = key()
    first = await invoker.invoke("ticket", tid, "close", None,
                                 principal=manager, idempotency_key=k)
    replay = await invoker.invoke("ticket", tid, "close", None,
                                  principal=manager, idempotency_key=k)
    assert replay.body == first.body
    # note: replay wins even though state moved to a terminal state


async def test_idempotent_double_invoke_advances_version_once(invoker, storage, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    first = await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                                 principal=alice)
    second = await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                                  principal=alice)
    assert second.status == 200
    assert second.doc["meta"]["version"] == first.doc["meta"]["version"] == 2
    async with storage.session() as s:
        last = await storage.last_transition(s, "ticket", tid)
    assert last.version == 2  # only one transition row for the two calls


async def test_idempotent_replay_requires_same_input(invoker, alice):
    doc = await create_ticket(invoker, alice)
    tid = doc["self"].rsplit("/", 1)[-1]
    await invoker.invoke("ticket", tid, "assign", {"assignee": "alice"},
                         principal=alice)
    # different input, same action, state already 'assigned' → honest 409
    with pytest.raises(WrongState):
        await invoker.invoke("ticket", tid, "assign", {"assignee": "bob"},
                             principal=alice)


# ── storage-level details ───────────────────────────────────────────────
async def test_query_filters_sort_paginate(invoker, storage, alice):
    for i in range(5):
        await create_ticket(invoker, alice, priority=(i % 5) + 1,
                            title=f"t{i}",
                            opened_at=f"2026-07-0{i + 1}T09:00:00Z")
    async with storage.session() as s:
        rows, total = await storage.query(
            s, "ticket", filters={"priority_gte": 4}, sort="-priority",
            page_size=10, page_number=1)
        assert total == 2
        assert [r.data.priority for r in rows] == [5, 4]

        rows, total = await storage.query(
            s, "ticket", filters={"opened_after": "2026-07-03T00:00:00Z"},
            sort="opened_at", page_size=10, page_number=1)
        assert total == 3

        rows1, _ = await storage.query(s, "ticket", filters={}, sort="priority",
                                       page_size=2, page_number=1)
        rows2, _ = await storage.query(s, "ticket", filters={}, sort="priority",
                                       page_size=2, page_number=2)
        assert {r.id for r in rows1} & {r.id for r in rows2} == set()

        rows, total = await storage.query(
            s, "ticket", filters={"state": ["open", "assigned"]}, sort=None,
            page_size=10, page_number=1)
        assert total == 5


async def test_generated_columns_exist_and_indexed(storage, invoker, alice):
    await create_ticket(invoker, alice)
    async with storage.session() as s:
        from sqlalchemy import text

        cols = (await s.execute(text(
            "SELECT column_name, is_generated FROM information_schema.columns "
            "WHERE table_name = 'tickets'"))).all()
        generated = {c for c, g in cols if g == "ALWAYS"}
        assert generated == {"priority", "opened_at"}
        indexes = (await s.execute(text(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'tickets'"))).scalars().all()
        assert "ix_tickets_priority" in indexes


async def test_facets(storage, invoker, alice):
    await create_ticket(invoker, alice)
    await create_ticket(invoker, alice)
    async with storage.session() as s:
        facets = await storage.facets(s, "ticket", "state")
    assert facets == {"open": 2}
