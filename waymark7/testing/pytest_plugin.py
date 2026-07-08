"""Pytest plugin: ``pytest --waymark7`` runs the 2.0 conformance suite
against the application's resources.

The application supplies, in its **root** conftest.py (the conformance
module is collected from inside the installed package, so only rootdir
conftests apply to it):

- a ``waymark7_engine`` async fixture (fresh, isolated storage per test);
- enrollments: ``@conformance_resource`` (derived machine walker) or
  ``@state_factory`` where a walk needs semantic setup, plus
  ``@example_input`` for acknowledged-open guards.

Cases are parametrized at collection time from the enrollment registry.
All fixture names are ``wm7``-prefixed so the v1 and v2 plugins coexist in
one repository.
"""
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

from . import factories as reg


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("waymark7")
    group.addoption("--waymark7", action="store", nargs="?", const="*",
                    default=None, metavar="KIND",
                    help="run the Waymark 2.0 conformance suite (optionally for one kind)")
    group.addoption("--waymark7-walk", action="store_true", default=False,
                    help="also random-walk each resource's state machine (Hypothesis)")


def pytest_configure(config: pytest.Config) -> None:
    if config.getoption("--waymark7") is not None:
        from . import conformance

        path = str(Path(conformance.__file__).resolve())
        if path not in config.args:
            config.args.append(path)
    if config.getoption("--waymark7-walk"):
        from . import walker

        path = str(Path(walker.__file__).resolve())
        if path not in config.args:
            config.args.append(path)


def _selected_kinds(config: pytest.Config) -> list[str]:
    selector = config.getoption("--waymark7") or "*"
    all_kinds = list(reg.resources())
    if selector == "*":
        return all_kinds
    wanted = {k.strip() for k in selector.split(",")}
    return [k for k in all_kinds
            if k in wanted or reg.resources()[k].__name__ in wanted]


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    if metafunc.module is None or metafunc.module.__name__ not in (
            "waymark7.testing.conformance", "waymark7.testing.walker"):
        return
    kinds = _selected_kinds(metafunc.config)
    principals = sorted(reg.principals())

    if "wm3_case" in metafunc.fixturenames:
        cases, ids = [], []
        for kind in kinds:
            cls = reg.resources()[kind]
            for state in cls.__waymark_machine__.states:
                for pname in principals:
                    cases.append((kind, state, pname))
                    ids.append(f"{kind}:{state}:{pname}")
        metafunc.parametrize("wm3_case", cases, ids=ids)

    if "wm3_action_case" in metafunc.fixturenames:
        cases, ids = [], []
        for kind in kinds:
            cls = reg.resources()[kind]
            machine = cls.__waymark_machine__
            for state in machine.states:
                for name, defn in machine.actions.items():
                    if defn.bulk:
                        continue
                    cases.append((kind, state, name))
                    ids.append(f"{kind}:{state}:{name}")
        metafunc.parametrize("wm3_action_case", cases, ids=ids)

    if "wm3_kind" in metafunc.fixturenames:
        metafunc.parametrize("wm3_kind", kinds, ids=kinds)


@pytest.fixture
async def wm7(waymark7_engine):
    """The conformance environment: engine + client with principal injection."""
    from fastapi import FastAPI
    from httpx import ASGITransport, AsyncClient

    engine = waymark7_engine
    profiles = reg.principals()

    def test_principal(request):
        name = request.headers.get("X-Waymark-Test-Principal", "anonymous")
        return profiles[name]

    engine.principal = test_principal
    app = FastAPI()
    app.include_router(engine.router, prefix=engine.base_path)
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://waymark7-conformance")
    try:
        yield SimpleNamespace(
            engine=engine, client=client, registry=engine.registry,
            storage=engine.storage, principals=profiles, base=engine.base_path,
        )
    finally:
        await client.aclose()
        await transport.aclose()
