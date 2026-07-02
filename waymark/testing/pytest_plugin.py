"""Pytest plugin (Part III): ``pytest --waymark`` runs the generic conformance
suite against the application's resources.

The application supplies, in its **root** conftest.py (the conformance module
is collected from inside the installed package, so only rootdir conftests
apply to it):
- a ``waymark_engine`` async fixture (fresh, isolated storage per test);
- ``@state_factory`` / ``@example_input`` registrations.

Cases are parametrized at collection time from the factory registry (conftest
imports run before collection), so the plugin never needs the engine early.
"""
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

from . import factories as reg


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("waymark")
    group.addoption("--waymark", action="store", nargs="?", const="*",
                    default=None, metavar="KIND",
                    help="run the Waymark conformance suite (optionally for one kind)")
    group.addoption("--waymark-walk", action="store_true", default=False,
                    help="also random-walk each resource's state machine (Hypothesis)")


def pytest_configure(config: pytest.Config) -> None:
    if config.getoption("--waymark") is not None:
        from . import conformance

        path = str(Path(conformance.__file__).resolve())
        if path not in config.args:
            config.args.append(path)
    if config.getoption("--waymark-walk"):
        from . import walker

        path = str(Path(walker.__file__).resolve())
        if path not in config.args:
            config.args.append(path)


def _selected_kinds(config: pytest.Config) -> list[str]:
    selector = config.getoption("--waymark") or "*"
    all_kinds = list(reg.factories())
    if selector == "*":
        return all_kinds
    wanted = {k.strip() for k in selector.split(",")}
    return [k for k in all_kinds
            if k in wanted or reg.factories()[k][0].__name__ in wanted]


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    if metafunc.module is None or metafunc.module.__name__ not in (
            "waymark.testing.conformance", "waymark.testing.walker"):
        return
    kinds = _selected_kinds(metafunc.config)
    principals = sorted(reg.principals())

    if "wm_case" in metafunc.fixturenames:
        cases, ids = [], []
        for kind in kinds:
            cls, _ = reg.factories()[kind]
            for state in cls.__waymark_machine__.states:
                for pname in principals:
                    cases.append((kind, state, pname))
                    ids.append(f"{kind}:{state}:{pname}")
        metafunc.parametrize("wm_case", cases, ids=ids)

    if "wm_action_case" in metafunc.fixturenames:
        cases, ids = [], []
        for kind in kinds:
            cls, _ = reg.factories()[kind]
            machine = cls.__waymark_machine__
            for state in machine.states:
                for name, defn in machine.actions.items():
                    if defn.bulk:
                        continue
                    cases.append((kind, state, name))
                    ids.append(f"{kind}:{state}:{name}")
        metafunc.parametrize("wm_action_case", cases, ids=ids)

    if "wm_kind" in metafunc.fixturenames:
        metafunc.parametrize("wm_kind", kinds, ids=kinds)


@pytest.fixture
async def wm(waymark_engine):
    """The conformance environment: engine + client with principal injection."""
    from fastapi import FastAPI
    from httpx import ASGITransport, AsyncClient

    engine = waymark_engine
    profiles = reg.principals()

    def test_principal(request):
        name = request.headers.get("X-Waymark-Test-Principal", "anonymous")
        return profiles[name]

    engine.principal = test_principal
    app = FastAPI()
    app.include_router(engine.router, prefix=engine.base_path)
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://waymark-conformance")
    try:
        yield SimpleNamespace(
            engine=engine, client=client, registry=engine.registry,
            storage=engine.storage, principals=profiles, base=engine.base_path,
        )
    finally:
        await client.aclose()
        await transport.aclose()
