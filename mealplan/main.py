"""The family meal-planning app: resource definitions in, everything else out.

    uv run uvicorn mealplan.main:app --reload

Generic UI at /api/-/ui; agent tool surface at /api/.well-known/waymark.
"""
from __future__ import annotations

import os

from fastapi import FastAPI

import waymark
from waymark.server.engine import header_principal

from .resources.grocery_list import GroceryList
from .resources.meal import Meal
from .resources.plan import MealPlan
from .resources.prep_task import PrepTask
from .resources.rotation import SundayRotation
from .services import Services

DSN = os.environ.get("MEALPLAN_DSN", "postgresql+asyncpg://localhost/mealplan_dev")

engine = waymark.Engine(
    resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask],
    storage=DSN,
    principal=header_principal,
    services=Services(),
)

app = FastAPI(title="Family meal planner", lifespan=engine.lifespan)
app.include_router(engine.router, prefix="/api")

from waymark.server import openapi as _openapi  # noqa: E402

_openapi.install(app, engine.registry)
