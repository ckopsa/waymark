import json
import subprocess
import sys

from waymark import Registry
from waymark.core import schemas as schemagen

from .sample import AssignInput, Ticket, TicketData


def test_input_schema_is_closed_and_inlined():
    schema, _ = schemagen.input_schema(AssignInput)
    assert schema["additionalProperties"] is False
    assert schema["required"] == ["assignee"]
    assert "$defs" not in json.dumps(schema)


def test_nested_model_defs_are_inlined():
    from pydantic import BaseModel

    class Inner(BaseModel):
        sku: str

    class Outer(BaseModel):
        items: list[Inner]

    schema, _ = schemagen.input_schema(Outer)
    assert "$defs" not in json.dumps(schema)
    assert schema["properties"]["items"]["items"]["properties"]["sku"] == {
        "title": "Sku", "type": "string"
    }
    assert schema["properties"]["items"]["items"]["additionalProperties"] is False


def test_canonical_bytes_are_sorted_and_tight():
    _, raw = schemagen.input_schema(AssignInput)
    parsed = json.loads(raw)
    assert list(parsed) == sorted(parsed)
    assert b": " not in raw and b", " not in raw


def test_canonical_bytes_stable_across_processes():
    code = (
        "import sys; sys.path.insert(0, 'tests')\n"
        "from core.sample import AssignInput\n"
        "from waymark.core import schemas\n"
        "sys.stdout.buffer.write(schemas.input_schema(AssignInput)[1])\n"
    )
    runs = {
        subprocess.run([sys.executable, "-c", code], capture_output=True,
                       check=True).stdout
        for _ in range(2)
    }
    assert len(runs) == 1
    assert runs.pop() == schemagen.input_schema(AssignInput)[1]


def test_query_schema_shape():
    schema, _ = schemagen.query_schema(Ticket)
    props = schema["properties"]
    assert props["state"]["enum"] == ["open", "assigned", "resolved", "closed"]
    assert props["priority_gte"]["type"] == "integer"
    assert props["priority_lte"]["type"] == "integer"
    assert props["opened_after"] == {"type": "string", "format": "date-time",
                                     "x-display": {"label": "Opened after"}}
    assert props["sort"]["enum"] == ["opened_at", "-opened_at", "priority", "-priority"]
    assert props["sort"]["default"] == "-opened_at"
    assert props["page[size]"]["maximum"] == 100
    assert props["page[number]"]["minimum"] == 1
    # filter params carry server-emitted labels (§8): clients render, not invent
    assert props["state"]["x-display"] == {"label": "State"}
    assert props["priority_gte"]["x-display"] == {"label": "Priority ≥"}
    assert schema["additionalProperties"] is False


def test_registry_registers_and_publishes_schemas():
    reg = Registry()
    rdef = reg.register(Ticket)
    assert reg["ticket"] is rdef
    assert reg.by_plural("tickets") is rdef
    assert rdef.action("assign").input is AssignInput
    # published names: kind → Data schema, input model names → input schemas
    assert reg.schema("ticket")[0] == rdef.data_schema
    assert reg.schema("AssignInput")[0] == rdef.action_schemas["assign"][0]
    assert reg.schema("nope") is None
    # idempotent re-register of the same class is fine
    assert reg.register(Ticket) is rdef


def test_registry_rejects_duplicate_kind():
    import pytest

    reg = Registry()
    reg.register(Ticket)

    with pytest.raises(ValueError, match="registered twice"):
        class Ticket2(Ticket):
            kind = "ticket"
            State = Ticket.State
            Data = TicketData
            initial = Ticket.initial
            terminal = Ticket.terminal
            summary = Ticket.summary

        reg.register(Ticket2)


def test_data_schema_uses_serialization_mode():
    schema, _ = schemagen.data_schema(TicketData)
    assert set(schema["properties"]) == {"title", "priority", "assignee", "opened_at"}
