from datetime import UTC, datetime

from waymark import Ctx, Principal, Registry
from waymark.server.render import make_etag, render, render_collection

from ..core.sample import Ticket, make_ticket

REG = Registry()
RDEF = REG.register(Ticket)
NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)


def ctx(roles=(), principal_id="u1") -> Ctx:
    return Ctx(principal=Principal(id=principal_id, roles=frozenset(roles)),
               now=NOW, mode="probe")


async def doc_for(state="open", c=None, **data):
    return await render(make_ticket(state=state, **data), RDEF, ctx=c or ctx())


async def test_envelope_shape():
    doc = await doc_for()
    assert doc["waymark"] == "1"
    assert doc["kind"] == "ticket"
    assert doc["self"] == "/api/tickets/t-1"
    assert doc["state"] == "open"
    assert doc["summary"] == "Ticket t-1 · Broken build · p3 · Open"
    assert doc["data"]["title"] == "Broken build"
    assert doc["meta"]["version"] == 1
    assert doc["meta"]["etag"] == 'W/"ticket-t-1-v1"'
    assert make_etag("ticket", "t-1", 1) == 'W/"ticket-t-1-v1"'


async def test_pending_input_guard_renders_available():
    doc = await doc_for("open")
    # assign's guard needs input → probe short-circuits to available
    assert "assign" in doc["actions"]
    entry = doc["actions"]["assign"]
    assert entry["method"] == "POST"
    assert entry["href"] == "/api/tickets/t-1/-/assign"
    assert entry["input"]["required"] == ["assignee"]
    assert entry["effect"] == {"to": "assigned"}
    assert entry["safety"] == {"idempotent": True, "reversible": True, "confirm": False}
    assert entry["display"]["label"] == "Assign"


async def test_denied_guard_renders_unavailable_with_reason():
    doc = await doc_for("open")  # priority 3, no manager role
    entry = doc["unavailable"]["close"]
    # or-composite: the first denier supplies the reason (and its hope, here none)
    assert entry["reason"] == "Only low-priority tickets can be closed without resolution."
    assert "becomes_available" not in entry


async def test_denied_role_guard_surfaces_requires_token():
    from ..core.sample import manager_only  # bare role guard, not an or-composite

    from waymark import Deny

    verdict = await manager_only(None, None, ctx())
    assert isinstance(verdict, Deny)
    assert manager_only.becomes_available(verdict) == {"requires": "role:manager"}


async def test_or_guard_allows_via_role():
    doc = await doc_for("open", c=ctx(roles=["manager"]))
    assert "close" in doc["actions"]
    doc = await doc_for("open", priority=1)
    assert "close" in doc["actions"]


async def test_out_of_state_transitions_listed_with_in_states():
    doc = await doc_for("open")
    entry = doc["unavailable"]["resolve"]
    assert entry["becomes_available"] == {"in_states": ["assigned"]}
    assert "assigned" in entry["reason"].lower()


async def test_affordance_partition_is_complete():
    for state in ("open", "assigned", "resolved", "closed"):
        doc = await doc_for(state)
        assert set(doc["actions"]) | set(doc["unavailable"]) == \
            set(RDEF.machine.actions), state


async def test_terminal_state_has_no_actions():
    doc = await doc_for("closed")
    assert doc["actions"] == {}


async def test_safety_requires_if_match_surfaced():
    doc = await doc_for("assigned")
    assert doc["actions"]["resolve"]["safety"]["requires_if_match"] is True


async def test_reserved_links_present():
    doc = await doc_for()
    assert doc["links"]["collection"]["href"] == "/api/tickets"
    assert doc["links"]["collection"]["kind"] == "ticket_collection"
    assert doc["links"]["events"]["href"] == "/api/tickets/t-1/-/events"
    assert doc["links"]["events"]["kind"] == "event_stream"


async def test_display_state_label():
    doc = await doc_for("open")
    assert doc["display"]["state"]["label"] == "Open"


async def test_hidden_guard_conceals_in_and_out_of_state():
    from enum import StrEnum

    from pydantic import BaseModel

    from waymark import Resource, action, guard

    class S(StrEnum):
        A = "a"
        B = "b"

    class D(BaseModel):
        x: int = 1

    class Secret(Resource):
        kind = "secret"
        State = S
        Data = D
        initial = S.A
        terminal = {S.B}
        summary = "Secret {id}"

        @action(from_=S.A, to=S.B, guards=[guard.role("admin", hide=True)],
                idempotent=True, reversible=False, confirm=False)
        async def destroy(self, inp: None, ctx) -> None: ...

    reg = Registry()
    rdef = reg.register(Secret)
    for state in ("a", "b"):
        inst = Secret(id="s1", state=state, data=D())
        doc = await render(inst, rdef, ctx=ctx())
        assert "destroy" not in doc["actions"]
        assert "destroy" not in doc["unavailable"]
    admin = Ctx(principal=Principal(id="root", roles=frozenset(["admin"])),
                now=NOW, mode="probe")
    doc = await render(Secret(id="s1", state="a", data=D()), rdef, ctx=admin)
    assert "destroy" in doc["actions"]


async def test_collection_envelope():
    items = [make_ticket(state="open"), make_ticket(state="assigned")]
    doc = await render_collection(RDEF, items, ctx=ctx(), total=12,
                                  page_size=25, page_number=1,
                                  applied_query={"state": "open"})
    assert doc["kind"] == "ticket_collection"
    assert doc["state"] == "ok"
    assert "2 of 12 shown" in doc["summary"]
    assert "filtered: state=open" in doc["summary"]
    assert doc["data"]["total"] == 12
    assert doc["data"]["page"] == {"size": 25, "number": 1}
    # items are full envelopes with computed affordances
    row = doc["data"]["items"][0]
    assert row["waymark"] == "1"
    assert isinstance(row["actions"], dict)
    # generated actions
    assert doc["actions"]["create"]["effect"] == {"to": "open"}
    assert doc["actions"]["query"]["method"] == "GET"
    assert "state" in doc["actions"]["query"]["input"]["properties"]


async def test_collection_pagination_links():
    items = [make_ticket()]
    doc = await render_collection(RDEF, items, ctx=ctx(), total=60,
                                  page_size=25, page_number=2)
    assert "page%5Bnumber%5D=3" in doc["links"]["next"]["href"]
    assert "page%5Bnumber%5D=1" in doc["links"]["prev"]["href"]
    last = await render_collection(RDEF, items, ctx=ctx(), total=60,
                                   page_size=25, page_number=3)
    assert last["links"]["next"] is None


async def test_collection_facets_merged_into_query_schema():
    doc = await render_collection(RDEF, [], ctx=ctx(), total=0,
                                  page_size=25, page_number=1,
                                  facets={"state": {"open": 3, "closed": 9}})
    assert doc["actions"]["query"]["input"]["properties"]["state"]["x-facets"] == \
        {"open": 3, "closed": 9}
    # the cached registry schema must not be mutated
    assert "x-facets" not in RDEF.query_schema["properties"]["state"]
