"""Per-worker test databases: make ``pytest -n auto`` (pytest-xdist) safe.

Every engine/storage fixture drops and recreates the schema, so two workers
sharing one database destroy each other mid-test — the suite is only serial
because of that. Routing each xdist worker to its own database
(``{name}_gw0``, ``{name}_gw1``, …) removes the contention; the databases are
created on first use from the base DSN and persist between runs (each test
recreates the schema anyway).
"""
from __future__ import annotations

import asyncio
import os

_ensured: set[str] = set()


def per_worker_dsn(dsn: str) -> str:
    """Return ``dsn`` unchanged outside pytest-xdist; under a worker, return
    the same DSN pointed at a per-worker database, creating it if missing."""
    worker = os.environ.get("PYTEST_XDIST_WORKER")
    if not worker:
        return dsn
    from sqlalchemy.engine import make_url

    url = make_url(dsn)
    name = f"{url.database}_{worker}"
    if name not in _ensured:
        asyncio.run(_ensure_database(url, name))
        _ensured.add(name)
    return url.set(database=name).render_as_string(hide_password=False)


async def _ensure_database(base_url, name: str) -> None:
    from sqlalchemy import text
    from sqlalchemy.ext.asyncio import create_async_engine

    # CREATE DATABASE refuses to run in a transaction → autocommit engine
    engine = create_async_engine(base_url, isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as conn:
            exists = await conn.execute(
                text("select 1 from pg_database where datname = :name"),
                {"name": name})
            if exists.scalar() is None:
                await conn.execute(text(f'create database "{name}"'))
                # test data needs no durability; skip the WAL flush per commit
                await conn.execute(text(
                    f'alter database "{name}" set synchronous_commit = off'))
    finally:
        await engine.dispose()
