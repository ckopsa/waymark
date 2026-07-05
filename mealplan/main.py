"""The family meal-planning app: resource definitions in, everything else out.

    uv run uvicorn mealplan.main:app --reload

Generic UI at /api/-/ui; agent tool surface at /api/.well-known/waymark.
"""
from __future__ import annotations

import os

from fastapi import FastAPI

import waymark2 as waymark
from waymark2.server.engine import header_principal

from .resources.grocery_list import GroceryList
from .resources.meal import Meal
from .resources.plan import MealPlan
from .resources.prep_task import PrepTask
from .resources.rotation import SundayRotation
from .services import Services

def _dsn() -> str:
    """MEALPLAN_DSN from the environment, falling back to a repo-root .env
    (one KEY=VALUE per line) so `uv run uvicorn mealplan.main:app` works
    without exporting machine-specific config every time."""
    if "MEALPLAN_DSN" in os.environ:
        return os.environ["MEALPLAN_DSN"]
    env_file = os.path.join(os.path.dirname(__file__), "..", ".env")
    if os.path.exists(env_file):
        with open(env_file) as f:
            for line in f:
                key, sep, value = line.strip().partition("=")
                if sep and key == "MEALPLAN_DSN":
                    return value.strip().strip('"')
    # 2.0 tables differ from v1's (waymark2_* engine tables, timestamptz
    # generated columns) — default to a fresh database rather than colliding
    # with a v1 deployment's schema
    return "postgresql+asyncpg://localhost/mealplan2_dev"


DSN = _dsn()

engine = waymark.Engine(
    resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask],
    storage=DSN,
    principal=header_principal,
    services=Services(),
)

app = FastAPI(title="Family meal planner", lifespan=engine.lifespan)
app.include_router(engine.router, prefix="/api")

from waymark2.server import openapi as _openapi  # noqa: E402

_openapi.install(app, engine.registry)
