"""The weekly theme calendar: our meal-plan week runs Tuesday to Tuesday.

Every weekday has a fixed theme except Sunday, which draws from the
dynamic :class:`~mealplan4.resources.rotation.SundayRotation` resource.
Saturday owns BBQ, which is why Wednesday's "american" explicitly excludes it.
"""
from __future__ import annotations

ROTATING = "rotating"  # Sunday's placeholder until a theme is picked

# datetime.date.weekday(): Monday=0 … Sunday=6
WEEKDAY_THEMES: dict[int, str] = {
    0: "italian",
    1: "mexican",    # Taco Tuesday — the week starts here
    2: "american",   # American food, but no BBQ (that's Saturday's job)
    3: "asian",
    4: "pizza",
    5: "bbq",
    6: ROTATING,
}

THEME_LABELS: dict[str, str] = {
    "mexican": "Taco Tuesday (Mexican/Latin American)",
    "american": "American (no BBQ)",
    "asian": "Asian",
    "pizza": "Pizza",
    "bbq": "BBQ",
    "italian": "Italian",
    ROTATING: "Sunday special (pick from the rotation)",
}
