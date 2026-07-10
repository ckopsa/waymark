"""The Sunday rotation: the dynamic list of themes Sunday cycles through.

Several rotations can exist (seasonal lists, experiments); ``activate``
stamps ``activated_at``, and new plans draw from the most recently activated
``active`` rotation — an action's effect belongs to this resource alone, so
activating one never mutates its siblings. New plans auto-select that
rotation and pre-theme their Sundays from it, starting at ``data.position``
(``advance`` moves it after a theme gets used). A plan's
``set_sunday_theme`` guard reads this resource, so a Sunday theme not in
the rotation is refused with a remedy pointing at ``add_theme``.

2.0: ``not_last_theme`` is a pure acceptance-set declaration — the set of
removable themes is both the rendered enum and the enforcement; there is no
separate check body to drift.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import AwareDatetime, BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from waymark9 import Ctx, Guard, Resource, Safety, action, filterable

DEFAULT_THEMES = ["breakfast for dinner", "indian", "greek", "soup night"]


class RotationState(StrEnum):
    ACTIVE = "active"      # the rotation plans draw from — at most one
    INACTIVE = "inactive"  # kept around (seasonal lists, experiments)


class RotationData(BaseModel):
    name: str = Field(default="Sunday rotation", min_length=1, max_length=100)
    themes: list[str] = Field(default_factory=lambda: list(DEFAULT_THEMES),
                              min_length=1)
    position: int = Field(default=0, ge=0,
                          description="Index of the next Sunday's theme")
    activated_at: AwareDatetime | None = Field(
        default=None, description="Set by `activate`; among active "
                                  "rotations, the most recent wins")


class RotationCreate(RotationData):
    """The create form: name it and list themes; the pointer starts at 0
    and only ever moves via ``advance``."""

    themes: list[str] = Field(default_factory=lambda: list(DEFAULT_THEMES),
                              min_length=1,
                              description="One theme per entry; Sunday cycles "
                                          "through these")
    position: SkipJsonSchema[int] = Field(default=0, ge=0)
    activated_at: SkipJsonSchema[AwareDatetime | None] = None


class ThemeInput(BaseModel):
    theme: str = Field(min_length=1, max_length=50)


# removable = what's on the rotation, unless that would empty it. One
# declaration: the rendered enum, the enforcement, and the per-part
# availability all come from this set.
not_last_theme = Guard(
    name="not_last_theme",
    judges=("theme",),
    accepts=lambda r: r.data.themes if len(r.data.themes) > 1 else [],
    explain="'{theme}' cannot be removed; the rotation must keep at least "
            "one theme.",
)


class SundayRotation(Resource):
    kind = "rotation"
    State = RotationState
    Data = RotationData
    Create = RotationCreate

    initial = RotationState.INACTIVE

    summary = "{data.name} · {data.themes|len} themes · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In)

    display = {"title": "{data.name}"}

    @action(from_=RotationState.INACTIVE, to=RotationState.ACTIVE,
            # honestly reversible: `deactivate` is the unconditional way back
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Make active", style="primary", order=1,
                         description="New plans draw Sunday themes from the "
                                     "most recently activated rotation"))
    async def activate(self, inp: None, ctx: Ctx) -> None:
        # an action's effect belongs to *this* resource, so activating never
        # touches siblings — among actives, the freshest activation wins
        self.data.activated_at = ctx.now

    @action(from_=RotationState.ACTIVE, to=RotationState.INACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Deactivate", order=4))
    async def deactivate(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=RotationState.ACTIVE, to=RotationState.ACTIVE,
            input=ThemeInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Add theme", style="primary", order=1))
    async def add_theme(self, inp: ThemeInput, ctx: Ctx) -> None:
        if inp.theme not in self.data.themes:
            self.data.themes.append(inp.theme)

    @action(from_=RotationState.ACTIVE, to=RotationState.ACTIVE,
            input=ThemeInput, guards=[not_last_theme],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Remove theme", order=2))
    async def remove_theme(self, inp: ThemeInput, ctx: Ctx) -> None:
        # removing an absent theme is a no-op, so retries stay replay-safe
        if inp.theme in self.data.themes:
            self.data.themes.remove(inp.theme)
            self.data.position %= len(self.data.themes)

    @action(from_=RotationState.ACTIVE, to=RotationState.ACTIVE,
            safety=Safety(idempotent=False, reversible=False, confirm=False),
            display=dict(label="Next theme", order=3))
    async def advance(self, inp: None, ctx: Ctx) -> None:
        self.data.position = (self.data.position + 1) % len(self.data.themes)
