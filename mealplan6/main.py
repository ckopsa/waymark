"""The family meal-planning app: resource definitions in, everything else out.

    uv run uvicorn mealplan6.main:app --reload

Generic UI at /api/-/ui; agent tool surface at /api/.well-known/waymark.
"""
from __future__ import annotations

import os

from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse
from fastapi.staticfiles import StaticFiles

import waymark6 as waymark
from waymark6.server.engine import header_principal

from .resources.event import Event
from .resources.grocery_list import GroceryList
from .resources.meal import Meal
from .resources.plan import MealPlan
from .resources.prep_task import PrepTask
from .resources.rotation import SundayRotation
from .services import Services

def _dsn() -> str:
    """MEALPLAN_DSN from the environment, falling back to a repo-root .env
    (one KEY=VALUE per line) so `uv run uvicorn mealplan6.main:app` works
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
    # 2.0 tables differ from v1's (waymark6_* engine tables, timestamptz
    # generated columns) — default to a fresh database rather than colliding
    # with a v1 deployment's schema
    return "postgresql+asyncpg://localhost/mealplan6_dev"


DSN = _dsn()

engine = waymark.Engine(
    resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask, Event],
    storage=DSN,
    principal=header_principal,
    services=Services(),
)

app = FastAPI(title="Family meal planner", lifespan=engine.lifespan)
app.include_router(engine.router, prefix="/api")
app.include_router(engine.landing())  # the UI at /, the API under /api

from waymark6.server import openapi as _openapi  # noqa: E402

_openapi.install(app, engine.registry)

# The server hands clients their client: built wheels (waymark6 CLI) are
# served under /cli so a fresh agent can bootstrap with nothing but this
# host's URL. Populated by `uv build` (repo-root dist/, or WAYMARK_CLI_DIR).
CLI_DIR = os.environ.get(
    "WAYMARK_CLI_DIR",
    os.path.join(os.path.dirname(__file__), "..", "dist"),
)

if os.path.isdir(CLI_DIR):
    @app.get("/cli", include_in_schema=False)
    def cli_index(request: Request) -> PlainTextResponse:
        base = str(request.base_url).rstrip("/")
        wheels = sorted(f for f in os.listdir(CLI_DIR) if f.endswith(".whl"))
        lines = ["# waymark6 client — install with one of:", ""]
        for wheel in wheels:
            lines += [f"uv tool install {base}/cli/{wheel}",
                      f"pipx install {base}/cli/{wheel}"]
        lines += ["", f"# then: WAYMARK_BASE={base} waymark6 client index",
                  "# agent-link holders: also set WAYMARK_TOKEN=wmk_… "
                  "(sent as Authorization: Bearer)"]
        return PlainTextResponse("\n".join(lines) + "\n")

    app.mount("/cli", StaticFiles(directory=CLI_DIR), name="cli")
