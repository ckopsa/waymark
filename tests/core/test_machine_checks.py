"""One negative test per §10.1 import-time rule, plus machine graph queries."""
from enum import StrEnum

import pytest
from pydantic import BaseModel, Field

from waymark import DefinitionError, Resource, action, guard, Allow, Deny

from .sample import Ticket, TicketState


class S(StrEnum):
    A = "a"
    B = "b"
    C = "c"


class D(BaseModel):
    name: str = Field(default="x", max_length=64)


def define(**overrides):
    """Build a minimal resource class, applying overrides, expecting success."""
    attrs = dict(
        kind="widget", State=S, Data=D, initial=S.A, terminal={S.C},
        summary="Widget {id} · {state.label}",
    )
    attrs.update(overrides.pop("attrs", {}))
    actions = overrides.pop("actions", None)
    if actions is None:
        @action(from_=S.A, to=S.B, idempotent=True, reversible=False, confirm=False)
        async def go(self, inp: None, ctx) -> None: ...

        @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
        async def finish(self, inp: None, ctx) -> None: ...

        actions = {"go": go, "finish": finish}
    attrs.update(actions)
    return type("Widget", (Resource,), attrs)


def test_valid_definition_builds_machine():
    cls = define()
    m = cls.__waymark_machine__
    assert m.initial == "a"
    assert [d.name for d in m.transitions_from("a")] == ["go"]
    assert m.actions["finish"].terminal is True
    assert m.actions["go"].terminal is False


def test_missing_safety_field_is_typeerror_at_decoration():
    with pytest.raises(TypeError, match="missing: confirm"):
        @action(from_=S.A, to=S.B, idempotent=True, reversible=False)
        async def go(self, inp: None, ctx) -> None: ...


def test_unreachable_state_rejected():
    @action(from_=S.A, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def skip(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="unreachable"):
        define(actions={"skip": skip})


def test_dead_end_state_rejected():
    @action(from_=S.A, to=S.B, idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    # b is reachable, non-terminal, and has no exit
    with pytest.raises(DefinitionError, match="dead end"):
        define(actions={"go": go})


def test_allow_dead_optout():
    @action(from_=S.A, to=S.B, idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    cls = define(actions={"go": go}, attrs={"allow_dead": {S.B, S.C}})
    assert cls.__waymark_machine__.initial == "a"


def test_terminal_state_with_exit_rejected():
    @action(from_=S.A, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.C, to=S.A, idempotent=True, reversible=False, confirm=False)
    async def undo(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="exits terminal"):
        define(actions={"go": go, "undo": undo},
               attrs={"terminal": {S.C}, "allow_dead": {S.B}})


def test_reversible_without_reverse_edge_rejected():
    @action(from_=S.A, to=S.B, idempotent=True, reversible=True, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="reversible=True"):
        define(actions={"go": go, "finish": finish})


def test_reversible_with_reverse_edge_accepted():
    @action(from_=S.A, to=S.B, idempotent=True, reversible=True, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.A, idempotent=True, reversible=True, confirm=False)
    async def back(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    define(actions={"go": go, "back": back, "finish": finish})


def test_reversible_via_role_gated_reverse_rejected():
    """The reverse edge must be unconditional; a role-gated one doesn't count."""
    @action(from_=S.A, to=S.B, idempotent=True, reversible=True, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.A, guards=[guard.role("manager")],
            idempotent=True, reversible=False, confirm=False)
    async def back(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="reversible=True"):
        define(actions={"go": go, "back": back, "finish": finish})


def test_self_transition_is_trivially_reversible():
    @action(from_=S.A, to=S.A, idempotent=True, reversible=True, confirm=False)
    async def touch(self, inp: None, ctx) -> None: ...

    @action(from_=S.A, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    define(actions={"touch": touch, "finish": finish},
           attrs={"allow_dead": {S.B}})


def test_guard_template_with_unsupplied_var_rejected():
    @guard(else_="Fails at {deadline}.")
    async def bad(r, inp, ctx):
        return Deny()  # never supplies vars

    @action(from_=S.A, to=S.B, guards=[bad],
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="deadline"):
        define(actions={"go": go, "finish": finish})


def test_guard_template_var_from_deny_scan_accepted():
    @guard(else_="Fails at {deadline}.")
    async def good(r, inp, ctx):
        return Deny(vars={"deadline": ctx.now})

    @action(from_=S.A, to=S.B, guards=[good],
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    define(actions={"go": go, "finish": finish})


def test_handler_annotation_mismatch_rejected():
    class OtherInput(BaseModel):
        x: int = 1

    @action(from_=S.A, to=S.B, input=D,
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: OtherInput, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="declares input=D"):
        define(actions={"go": go, "finish": finish})


def test_handler_unresolvable_forward_ref_rejected():
    @action(from_=S.A, to=S.B, input=D,
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: "NoSuchModel", ctx) -> None: ...  # noqa: F821

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="unresolvable annotation"):
        define(actions={"go": go, "finish": finish})


def test_non_async_handler_rejected():
    @action(from_=S.A, to=S.B, idempotent=True, reversible=False, confirm=False)
    def go(self, inp, ctx) -> None: ...

    @action(from_=S.B, to=S.C, idempotent=True, reversible=False, confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="async def"):
        define(actions={"go": go, "finish": finish})


def test_summary_template_unknown_root_rejected():
    with pytest.raises(DefinitionError, match="unknown roots"):
        define(attrs={"summary": "Widget {nonsense.field}"})


def test_bad_kind_token_rejected():
    with pytest.raises(DefinitionError, match="snake_case"):
        define(attrs={"kind": "Widget-Kind"})


def test_missing_declaration_rejected():
    with pytest.raises(DefinitionError, match="missing required declaration"):
        type("Nope", (Resource,), {"kind": "nope"})


def test_ticket_sample_machine_shape():
    m = Ticket.__waymark_machine__
    assert set(m.actions) == {"assign", "unassign", "resolve", "close"}
    assert {d.name for d in m.transitions_from(str(TicketState.OPEN))} == {"assign", "close"}
    assert m.reachable_states() == {"open", "assigned", "resolved", "closed"}
    assert Ticket.plural == "tickets"
