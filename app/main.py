"""The runnable example app (§13): resource definitions in, everything else out.

    uv run uvicorn app.main:app --reload
"""
from __future__ import annotations

import os

from fastapi import FastAPI

import waymark
from waymark.server.engine import header_principal

from .resources.order import Order
from .resources.return_workflow import Return
from .resources.shipment import Shipment
from .services import Services

DSN = os.environ.get("WAYMARK_DSN", "postgresql+asyncpg://localhost/waymark_dev")

engine = waymark.Engine(
    resources=[Order, Shipment, Return],
    storage=DSN,
    principal=header_principal,
    services=Services(),
)

app = FastAPI(title="Waymark example", lifespan=engine.lifespan)
app.include_router(engine.router, prefix="/api")

from waymark.server import openapi as _openapi  # noqa: E402

_openapi.install(app, engine.registry)  # /docs shows every action's real schema
