import os
from datetime import UTC, datetime

import pytest
from sqlalchemy.ext.asyncio import create_async_engine

from waymark import Principal, Registry
from waymark.server.invoke import Invoker
from waymark.server.storage.postgres import PostgresStorage

from ..core.sample import Ticket

from waymark.testing import per_worker_dsn

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

FIXED_NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)


@pytest.fixture(scope="session")
def registry() -> Registry:
    reg = Registry()
    reg.register(Ticket)
    return reg


@pytest.fixture
async def storage(registry):
    engine = create_async_engine(TEST_DSN)
    store = PostgresStorage(engine, registry)
    await store.drop_all()
    await store.create_all()
    try:
        yield store
    finally:
        await store.drop_all()
        await engine.dispose()


@pytest.fixture
def invoker(registry, storage) -> Invoker:
    return Invoker(registry=registry, storage=storage, clock=lambda: FIXED_NOW)


@pytest.fixture
def alice() -> Principal:
    return Principal(id="alice", type="human", display="Alice")


@pytest.fixture
def manager() -> Principal:
    return Principal(id="boss", type="human", roles=frozenset(["manager"]),
                     display="Boss")
