"""The usability layer of §10.1: warnings for enforceable-but-hostile forms.

Two heuristics (``open_input``, ``altitude``) warn; ``admits`` declarations
and waive tokens are validated hard. One test per rule, in both directions:
the finding fires, and each sanctioned fix silences it.
"""
from enum import StrEnum

import warnings

import pytest
from pydantic import BaseModel, Field

from waymark import Allow, Deny, DefinitionError, Resource, UsabilityWarning, action, guard


class S(StrEnum):
    A = "a"
    B = "b"


class Row(BaseModel):
    key: str = Field(max_length=64)
    label: str | None = Field(default=None, max_length=64)


class D(BaseModel):
    rows: list[Row] = Field(default_factory=list)


class KeyInput(BaseModel):
    key: str = Field(max_length=64)


class Suit(StrEnum):
    HEARTS = "hearts"
    SPADES = "spades"


class SuitInput(BaseModel):
    key: Suit


def define(guards, *, input_model=KeyInput, waives=(), name="Widget"):
    @action(from_=S.A, to=S.B, input=input_model, guards=guards,
            idempotent=True, reversible=False, confirm=False, waives=waives)
    async def go(self, inp, ctx) -> None: ...

    return type(name, (Resource,), dict(
        kind="widget", State=S, Data=D, initial=S.A, terminal={S.B},
        summary="Widget {id}", go=go,
    ))


def collect(fn):
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        fn()
    return [w for w in caught if issubclass(w.category, UsabilityWarning)]


@guard(else_="{key} is not a row.", vars=["key"])
async def key_in_rows(r, inp: KeyInput, ctx):
    if any(row.key == inp.key for row in r.data.rows):
        return Allow()
    return Deny(vars={"key": inp.key})


@guard(else_="{key} is not a row.", vars=["key"],
       admits=("key", lambda r: [row.key for row in r.data.rows]))
async def key_in_rows_admitting(r, inp: KeyInput, ctx):
    if any(row.key == inp.key for row in r.data.rows):
        return Allow()
    return Deny(vars={"key": inp.key})


@guard(else_="Not allowed.")
async def needs_ctx(r, inp: KeyInput, ctx):
    other = await ctx.read("widget", inp.key)
    return Allow() if other else Deny()


# ── introspection ────────────────────────────────────────────────────────
def test_guard_dependency_classification():
    assert key_in_rows.reads_ctx is False
    assert key_in_rows.input_fields == frozenset({"key"})
    assert needs_ctx.reads_ctx is True

    @guard(else_="No.")
    async def wholesale(r, inp: KeyInput, ctx):
        return Allow() if validate(inp) else Deny()  # noqa: F821

    assert wholesale.input_fields is None  # inp escapes; fields unknowable


# ── open_input ───────────────────────────────────────────────────────────
def test_open_input_warns_on_document_derivable_guard():
    warns = collect(lambda: define([key_in_rows]))
    # both defects fire: the open schema and the wrong-altitude placement
    assert any("unbounded blank" in str(w.message)
               and "fn(r)" in str(w.message) for w in warns)


def test_admits_silences_open_input():
    warns = collect(lambda: define([key_in_rows_admitting]))
    assert not any("unbounded blank" in str(w.message) for w in warns)


def test_enum_field_silences_open_input():
    @guard(else_="Bad suit.")
    async def suit_ok(r, inp: SuitInput, ctx):
        return Allow() if inp.key == Suit.HEARTS else Deny()

    assert collect(lambda: define([suit_ok], input_model=SuitInput)) == []


def test_ctx_reading_guard_warns_with_async_admits_hint():
    # the acceptance set lives in another resource, but the render ctx can
    # read it — the warning points at the async admits form
    warns = collect(lambda: define([needs_ctx]))
    assert any("fn(r, ctx)" in str(w.message) for w in warns)


def test_async_admits_silences_ctx_reading_guard():
    @guard(else_="No.", admits=("key", _other_keys))
    async def needs_ctx_admitting(r, inp: KeyInput, ctx):
        other = await ctx.read("widget", inp.key)
        return Allow() if other else Deny()

    warns = collect(lambda: define([needs_ctx_admitting]))
    assert not any("unbounded blank" in str(w.message) for w in warns)


def test_widget_field_counts_as_guidance():
    class PickInput(BaseModel):
        key: str = Field(max_length=64, json_schema_extra={"x-display": {
            "widget": "resource", "kind": "widget"}})

    @guard(else_="No.")
    async def judge_pick(r, inp: PickInput, ctx):
        other = await ctx.read("widget", inp.key)
        return Allow() if other else Deny()

    warns = collect(lambda: define([judge_pick], input_model=PickInput))
    assert not any("unbounded blank" in str(w.message) for w in warns)


async def _other_keys(r, ctx):
    others = await ctx.find("widget", state="a")
    return [o.id for o in others]


def test_waives_open_input():
    warns = collect(lambda: define([key_in_rows], waives=("open_input", "altitude")))
    assert warns == []


# ── altitude ─────────────────────────────────────────────────────────────
def test_altitude_warns_when_judged_field_mirrors_a_data_array():
    # admits silences open_input; altitude still fires — different defects
    (w,) = collect(lambda: define([key_in_rows_admitting]))
    assert "mirror the items of data.rows" in str(w.message)


def test_altitude_silent_without_identifying_guard():
    # creation-shaped: fields mirror the item but no guard looks one up
    class NewRow(BaseModel):
        key: str = Field(max_length=64)
        label: str | None = Field(default=None, max_length=64)

    assert collect(lambda: define([], input_model=NewRow)) == []


def test_waives_altitude():
    warns = collect(lambda: define([key_in_rows_admitting], waives=("altitude",)))
    assert warns == []


# ── hard validation ──────────────────────────────────────────────────────
def test_admits_must_name_an_input_field():
    @guard(else_="No.", admits=("nope", lambda r: []))
    async def bad(r, inp: KeyInput, ctx):
        return Allow()

    with pytest.raises(DefinitionError, match="admits='nope'"):
        define([bad], waives=("open_input", "altitude"))


def test_admits_requires_an_input_model():
    @guard(else_="No.", admits=("key", lambda r: []))
    async def bad(r, inp, ctx):
        return Allow()

    @action(from_=S.A, to=S.B, guards=[bad],
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="takes no input"):
        type("W2", (Resource,), dict(
            kind="widget", State=S, Data=D, initial=S.A, terminal={S.B},
            summary="Widget {id}", go=go,
        ))


def test_unknown_waive_token_rejected():
    with pytest.raises(DefinitionError, match="unknown usability checks"):
        define([], waives=("altitud",))


# ── opaque refs (registry-level: needs every kind known) ─────────────────
def _mini(kind, data_model):
    class T(StrEnum):
        A = "a"

    return type(kind.title(), (Resource,), dict(
        kind=kind, State=T, Data=data_model, initial=T.A, terminal={T.A},
        summary=f"{kind} {{id}}"))


def test_opaque_ref_warns_at_registry_assembly():
    from waymark import Registry
    from waymark.core.checks import check_opaque_refs

    class GadgetData(BaseModel):
        name: str = Field(default="g", max_length=64)

    class HolderData(BaseModel):
        # a registered kind, no hint → opaque_ref warns (unbudgeted too,
        # so the assertion below filters by message)
        gadget_id: str | None = Field(default=None, max_length=64)
        vendor_id: str | None = Field(default=None, max_length=64)  # not a kind

    reg = Registry()
    reg.register(_mini("gadget", GadgetData))
    reg.register(_mini("holder", HolderData))
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        check_opaque_refs(reg)
    msgs = [str(w.message) for w in caught
            if issubclass(w.category, UsabilityWarning)]
    assert len(msgs) == 1 and "data.gadget_id" in msgs[0]


def test_opaque_ref_hints_silence():
    from waymark import Registry
    from waymark.core.checks import check_opaque_refs

    class GadgetData(BaseModel):
        name: str = Field(default="g", max_length=64)

    class Part(BaseModel):
        gadget_id: str | None = Field(default=None, json_schema_extra={
            "x-display": {"hidden": True}})

    class HolderData(BaseModel):
        gadget_id: str | None = Field(default=None, json_schema_extra={
            "x-display": {"widget": "resource", "kind": "gadget"}})
        parts: list[Part] = Field(default_factory=list)

    reg = Registry()
    reg.register(_mini("gadget", GadgetData))
    reg.register(_mini("holder", HolderData))
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        check_opaque_refs(reg)
    assert not [w for w in caught if issubclass(w.category, UsabilityWarning)]


# ── long text (layout honesty) ───────────────────────────────────────────
def test_long_text_warns_on_unbudgeted_and_big_fields():
    class LoudData(BaseModel):
        essay: str | None = None                       # unbounded → warn
        blurb: str | None = Field(default=None, max_length=2000)  # big → warn
        name: str = Field(max_length=200)              # budgeted → fine
        tags: list[str] = Field(default_factory=list)  # not a text field

    warns = collect(lambda: _mini("loud", LoudData))
    msgs = [str(w.message) for w in warns]
    assert len(msgs) == 2
    assert any("data.essay" in m and "no max_length" in m for m in msgs)
    assert any("data.blurb" in m and "max_length=2000" in m for m in msgs)


def test_long_text_hints_silence():
    class Item(BaseModel):
        body: str | None = Field(default=None, json_schema_extra={
            "x-display": {"raw": True}})

    class QuietData(BaseModel):
        essay: str | None = Field(default=None, json_schema_extra={
            "x-display": {"widget": "prose"}})
        plumbing: str | None = Field(default=None, json_schema_extra={
            "x-display": {"hidden": True}})
        entries: list[Item] = Field(default_factory=list)

    assert collect(lambda: _mini("quiet", QuietData)) == []


# ── edit shape: prefill, if-match fence, draft effort ────────────────────
class EditData(BaseModel):
    body: str | None = Field(default=None, max_length=64)
    minutes: int | None = None


class EditInput(BaseModel):
    body: str = Field(max_length=64)
    minutes: int | None = None


def define_edit(*, prefill=(), draft=False, if_match=False, waives=(),
                input_model=EditInput):
    @action(from_=S.A, to=S.A, input=input_model, prefill=prefill,
            draft=draft, requires_if_match=if_match, waives=waives,
            idempotent=True, reversible=False, confirm=False)
    async def edit(self, inp, ctx) -> None: ...

    @action(from_=S.A, to=S.B, idempotent=True, reversible=False,
            confirm=False)
    async def finish(self, inp: None, ctx) -> None: ...

    return type("Editable", (Resource,), dict(
        kind="editable", State=S, Data=EditData, initial=S.A, terminal={S.B},
        summary="Editable {id}", edit=edit, finish=finish))


def test_blank_edit_warns_on_mirrored_fields():
    warns = collect(lambda: define_edit())
    assert any("edit-shaped" in str(w.message)
               and "'body', 'minutes'" in str(w.message) for w in warns)


def test_prefill_silences_blank_edit_and_unfenced_fires():
    warns = collect(lambda: define_edit(prefill=("body", "minutes")))
    msgs = [str(w.message) for w in warns]
    assert not any("edit-shaped" in m for m in msgs)
    assert any("requires_if_match" in m for m in msgs)  # unfenced_edit


def test_fenced_prefill_is_clean():
    assert collect(lambda: define_edit(prefill=("body", "minutes"),
                                       if_match=True)) == []


def test_large_effort_requires_draft():
    class ProseInput(BaseModel):
        body: str = Field(json_schema_extra={"x-display": {"widget": "prose"}})

    warns = collect(lambda: define_edit(
        input_model=ProseInput, prefill=("body",), if_match=True))
    assert any("draft" in str(w.message) for w in warns)
    quiet = collect(lambda: define_edit(
        input_model=ProseInput, prefill=("body",), if_match=True, draft=True))
    assert not any("large_effort" in str(w.message) or "draft=True" in
                   str(w.message) for w in quiet)


def test_prefill_and_draft_hard_validation():
    with pytest.raises(DefinitionError, match="prefills 'nope'"):
        define_edit(prefill=("nope",), if_match=True)

    @action(from_=S.A, to=S.B, draft=True,
            idempotent=True, reversible=False, confirm=False)
    async def go(self, inp: None, ctx) -> None: ...

    with pytest.raises(DefinitionError, match="nothing to draft"):
        type("W3", (Resource,), dict(
            kind="widget", State=S, Data=D, initial=S.A, terminal={S.B},
            summary="Widget {id}", go=go))


def test_long_text_covers_input_models():
    class LoudInput(BaseModel):
        essay: str  # unbounded input, no prose hint → warn

    warns = collect(lambda: define_edit(input_model=LoudInput,
                                        waives=("blank_edit",)))
    assert any("input LoudInput.essay" in str(w.message) for w in warns)
