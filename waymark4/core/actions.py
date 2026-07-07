"""The @action decorator (design §3): grouped declarations, not a kwarg pile.

v1's ``@action`` grew to 24 flat keyword arguments; 2.0 groups them into
objects that own their invariants:

- ``safety=Safety(...)``   — explicit, required, self-validating (types.py)
- ``edit=Edit(...)``       — prefill + draft policy + the If-Match fence,
                             one concept; a prefilled-but-unfenced or
                             collab-without-draft edit is unrepresentable
- ``place=PartScope(...)`` — per-item placement, declared once on the
                             resource and shared by its actions
- ``bulk=Bulk(...)``       — collection-level fan-out with its own limits
"""
from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from typing import Any

from pydantic import BaseModel

from .guards import Guard
from .types import DefinitionError, Effect, Safety

_REQUIRED: Any = object()


@dataclass(frozen=True)
class DraftPolicy:
    """Server-persisted partial input (design §4).

    ``shared=True``: one draft for every principal who can see the action
    (the audience is decided here, at declaration — the write path never
    chooses a principal string). ``live=True``: the draft advertises a
    waymark-relay/2 channel; requires ``shared`` — a live channel into a
    private draft would relay to nobody.
    """

    shared: bool = False
    live: bool = False

    def __post_init__(self) -> None:
        if self.live and not self.shared:
            raise DefinitionError(
                "DraftPolicy(live=True) requires shared=True — the live "
                "channel relays between collaborators on one shared draft")


@dataclass(frozen=True)
class Edit:
    """An edit-shaped action is one concept, with one set of invariants.

    - ``prefill`` — input fields whose rendered schema ``default`` is the
      document's current value (editing is not re-authoring).
    - ``fence`` — the If-Match requirement. Defaults to True: a prefilled
      form is a snapshot, and without the fence editors silently clobber
      each other. Opting out requires a written reason.
    - ``draft`` — the persistence policy for in-progress effort.
    """

    prefill: tuple[str, ...] = ()
    draft: DraftPolicy | None = None
    fence: bool = True
    unfenced_reason: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "prefill", tuple(self.prefill))
        if not self.fence and not (self.unfenced_reason or "").strip():
            raise DefinitionError(
                "Edit(fence=False) requires unfenced_reason='…' — an "
                "unfenced prefilled form silently clobbers concurrent edits; "
                "say why that is acceptable here")


@dataclass(frozen=True)
class Bulk:
    """Collection-level fan-out. Guards run per item; the response is a
    partial-success report unless ``atomic``."""

    atomic: bool = False
    max_items: int = 500
    defer_over: int | None = None  # ids beyond this → 202 + job resource


@dataclass(frozen=True)
class Batch:
    """The collection form of the act (design §7): the same declared
    action also accepts N inputs at ``…/-/{name}/batch`` as
    ``{"items": [<input>, …]}``. Every item is validated and guarded
    exactly as a single invocation; the response is the bulk-report shape
    with per-item verdicts. ``atomic=True``: one transaction — all items
    land or none do, and a refusal's report still carries every item's
    verdict (validation always completes even when commitment doesn't).
    ``atomic=False``: per-item commits with partial success."""

    atomic: bool = True
    max_items: int = 1000

    def __post_init__(self) -> None:
        if self.max_items < 1:
            raise DefinitionError("Batch(max_items=…) must be ≥ 1")


class PartScope:
    """Per-item placement, declared once on the resource (design §3)::

        class MealPlan(Resource):
            days = PartScope("days", key="date")

            @action(..., place=days)
            async def assign_meal(self, inp, ctx): ...

    Actions placed on a scope render once per item of ``data.<array>`` under
    the envelope's ``parts`` namespace, with ``key`` pre-bound as ``const``
    — the form never re-asks which item the user is already looking at.
    Drafts of placed actions are keyed per part (v1's "don't combine scope
    with draft" caveat is gone).
    """

    def __init__(self, array: str, *, key: str):
        self.array = array
        self.key = key

    def __repr__(self) -> str:
        return f"PartScope({self.array!r}, key={self.key!r})"


@dataclass(frozen=True)
class ActionDef:
    name: str
    from_: frozenset[str]
    to: str
    input: type[BaseModel] | None
    guards: tuple[Guard, ...]
    safety: Safety
    emits: tuple[str, ...]
    display: Mapping[str, Any]
    handler: Callable  # unbound async def (self, inp, ctx) -> None
    bulk_spec: Bulk | None = None
    edit: Edit | None = None
    place: PartScope | None = None
    terminal: bool = False  # filled in by the machine build
    field_display: Mapping[str, Any] = field(default_factory=dict)
    waives: frozenset[str] = frozenset()
    # declared touches (design E8): what this transition may span beyond
    # its own resource — rendered on effect, enforced by the handler's ctx
    touches: tuple[Any, ...] = ()
    # per-action relative visibility (design §4): the Unless declaration,
    # kept whole for the import checks; its compiled guard already rides
    # ``guards``, which is how both consumers (probe → unavailable,
    # invoke → 409) run the one declaration
    unless: Any | None = None
    # the batch form (design §7): the same action, N inputs, one report
    batch_spec: "Batch | None" = None
    # the compound declaration (design §6) when this action was compiled
    # from one — the blast radius renders from it; the touches above were
    # compiled from it, so advertisement and enforcement cannot drift
    compound: Any | None = None

    @property
    def effect(self) -> Effect:
        return Effect(to=self.to, terminal=self.terminal, emits=self.emits,
                      bulk=self.bulk, touches=self.touches,
                      compound=self.compound)

    def with_terminal(self, terminal: bool) -> "ActionDef":
        return replace(self, terminal=terminal)

    # ── derived views (the server consumes these; one source of truth) ──
    @property
    def bulk(self) -> bool:
        return self.bulk_spec is not None

    @property
    def atomic(self) -> bool:
        return self.bulk_spec.atomic if self.bulk_spec else False

    @property
    def max_items(self) -> int:
        return self.bulk_spec.max_items if self.bulk_spec else 500

    @property
    def defer_over(self) -> int | None:
        return self.bulk_spec.defer_over if self.bulk_spec else None

    @property
    def prefill(self) -> tuple[str, ...]:
        return self.edit.prefill if self.edit else ()

    @property
    def draft(self) -> bool:
        return bool(self.edit and self.edit.draft)

    @property
    def draft_policy(self) -> DraftPolicy | None:
        return self.edit.draft if self.edit else None

    @property
    def collab(self) -> bool:
        return bool(self.edit and self.edit.draft and self.edit.draft.live)

    @property
    def scope(self) -> tuple[str, str] | None:
        return (self.place.array, self.place.key) if self.place else None


def emits(*events: str) -> tuple[str, ...]:
    return events


def action(
    *,
    from_: Any,
    to: Any,
    input: type[BaseModel] | None = None,
    guards: Sequence[Guard] = (),
    safety: Safety = _REQUIRED,
    side_effects: tuple[str, ...] = (),
    display: Mapping[str, Any] | None = None,
    field_display: Mapping[str, Any] | None = None,
    edit: Edit | None = None,
    place: PartScope | None = None,
    bulk: Bulk | None = None,
    batch: "Batch | None" = None,
    waives: Sequence[str] = (),
    touches: Sequence[Any] = (),
    unless: Any | None = None,
) -> Callable:
    if safety is _REQUIRED:
        raise DefinitionError(
            "@action requires safety=Safety(...) — safety is declared, "
            "never inferred")
    if not isinstance(safety, Safety):
        raise DefinitionError(
            f"@action safety must be a Safety declaration, got {safety!r}")
    if bulk is not None and edit is not None and edit.draft is not None:
        raise DefinitionError(
            "bulk actions cannot declare drafts; drafts are per-resource")
    if batch is not None:
        if not isinstance(batch, Batch):
            raise DefinitionError(
                f"@action batch= takes a Batch declaration, got {batch!r}")
        if input is None:
            raise DefinitionError(
                "@action batch= requires input= — a batch is N inputs of "
                "the declared model; an input-less action has no items")
        if place is not None:
            raise DefinitionError(
                "batch actions cannot be placed — a part-bound form "
                "addresses exactly one item; the batch IS the many")
        if bulk is not None:
            raise DefinitionError(
                "an action declares batch= (N inputs, one resource) or "
                "bulk= (one input, N resources), not both")
    froms = from_ if isinstance(from_, (set, frozenset, list, tuple)) else {from_}
    from_tokens = frozenset(str(s) for s in froms)

    all_guards = tuple(guards)
    if unless is not None:
        # relative visibility (design §4): the declaration compiles to a
        # guard, so projection (unavailable, with the reason) and
        # enforcement (409, same reason) are the one existing machinery —
        # nothing new to keep in agreement
        from .history import Unless

        if not isinstance(unless, Unless):
            raise DefinitionError(
                f"@action unless= takes an Unless declaration, got "
                f"{unless!r}")
        all_guards = all_guards + (unless.guard,)

    effective_safety = safety
    if edit is not None and edit.fence and not safety.fence:
        # Edit implies the fence; the declaration and the wire agree.
        effective_safety = replace(safety, fence=True)
    if batch is not None and effective_safety.fence:
        raise DefinitionError(
            "batch actions cannot be fenced — a batch carries one body and "
            "no per-item etag, so the fence could only lie. Drop fence= "
            "(Edit(fence=False, unfenced_reason=…)) or the batch")

    disp = dict(display or {})
    if safety.consequence and not disp.get("description"):
        disp["description"] = safety.consequence

    def decorate(fn: Callable) -> Callable:
        fn.__waymark_action__ = ActionDef(  # type: ignore[attr-defined]
            name=fn.__name__,
            from_=from_tokens,
            to=str(to),
            input=input,
            guards=all_guards,
            safety=effective_safety,
            emits=tuple(side_effects),
            display=disp,
            handler=fn,
            bulk_spec=bulk,
            edit=edit,
            place=place,
            field_display=dict(field_display or {}),
            waives=frozenset(waives),
            touches=tuple(touches),
            unless=unless,
            batch_spec=batch,
        )
        return fn

    return decorate
