"""The waymark7 CLI: check | routes | openapi | migrate | new-resource
| extract-messages | client."""
from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

import typer

from .client import client_app

app = typer.Typer(help="Waymark 3.0: affordance-oriented hypermedia framework tools.",
                  no_args_is_help=True)
app.add_typer(client_app, name="client")


def _load_engine(spec: str):
    """Load an Engine from 'module.path:attribute' (e.g. app.main:engine)."""
    module_name, _, attr = spec.partition(":")
    sys.path.insert(0, ".")
    module = importlib.import_module(module_name)
    engine = getattr(module, attr or "engine")
    return engine


@app.command()
def check(engine_spec: str = typer.Argument(
        "app.main:engine", help="module:attr of the Engine"),
        strict: bool = typer.Option(
            False, "--strict", help="exit 1 on usability warnings too")) -> None:
    """Import the application (running every §10.1 import-time check) and
    print the machine summary. Exit 1 on any DefinitionError — the CI fast path.
    Usability findings (open_input, altitude) print as warnings; --strict
    promotes them to failures."""
    import warnings as _warnings

    from ..core.checks import UsabilityWarning

    try:
        with _warnings.catch_warnings(record=True) as caught:
            _warnings.simplefilter("always", UsabilityWarning)
            engine = _load_engine(engine_spec)
    except Exception as exc:
        typer.secho(f"✗ {type(exc).__name__}: {exc}", fg="red")
        raise typer.Exit(1) from exc
    usability = [w for w in caught if issubclass(w.category, UsabilityWarning)]
    for rdef in engine.registry.defs():
        m = rdef.machine
        typer.secho(f"✓ {rdef.kind}", fg="green", bold=True)
        typer.echo(f"    states     {', '.join(m.states)}")
        typer.echo(f"    initial    {m.initial}   terminal: "
                   f"{', '.join(sorted(m.terminal)) or '—'}")
        for name, defn in m.actions.items():
            arrows = f"{'|'.join(sorted(defn.from_))} → {defn.to}"
            flags = [f for f, v in (("idempotent", defn.safety.idempotent),
                                    ("reversible", defn.safety.reversible),
                                    ("confirm", defn.safety.confirm),
                                    ("if-match", defn.safety.fence),
                                    ("bulk", defn.bulk)) if v]
            guards = ", ".join(g.name for g in defn.guards) or "—"
            typer.echo(f"    {name:<16} {arrows:<40} [{', '.join(flags) or '-'}] "
                       f"guards: {guards}")
    if usability:
        typer.echo()
        for w in usability:
            typer.secho(f"⚠ {w.message}", fg="yellow")
        typer.echo()
    verdict = "all definitions pass import-time checks"
    if usability:
        verdict += (f"; {len(usability)} usability warning"
                    f"{'s' if len(usability) > 1 else ''}")
    typer.secho(verdict, fg="yellow" if usability else "green")
    if usability and strict:
        raise typer.Exit(1)


@app.command()
def routes(engine_spec: str = typer.Argument("app.main:engine")) -> None:
    """Print the generated route table (§13)."""
    engine = _load_engine(engine_spec)
    base = engine.base_path
    typer.echo(f"GET    {base}/.well-known/waymark")
    typer.echo(f"GET    {base}/schemas/{{name}}")
    typer.echo(f"GET    {base}/-/events")
    for rdef in engine.registry.defs():
        col = f"{base}/{rdef.plural}"
        typer.echo(f"GET    {col}")
        typer.echo(f"POST   {col}")
        typer.echo(f"GET    {col}/{{id}}")
        typer.echo(f"GET    {col}/{{id}}/-/events")
        for name, defn in rdef.machine.actions.items():
            if defn.bulk:
                typer.echo(f"POST   {col}/-/{name}")
            else:
                typer.echo(f"POST   {col}/{{id}}/-/{name}")


@app.command()
def openapi(engine_spec: str = typer.Argument("app.main:engine")) -> None:
    """Dump the OpenAPI overlay for the application's action surface."""
    engine = _load_engine(engine_spec)
    from ..server.openapi import overlay_paths

    document = {
        "openapi": "3.1.0",
        "info": {"title": "Waymark API", "version": "0.1.0"},
        "paths": overlay_paths(engine.registry, engine.base_path),
    }
    typer.echo(json.dumps(document, indent=2))


@app.command()
def migrate(engine_spec: str = typer.Argument(
        "app.main:engine", help="module:attr of the Engine"),
        directory: Path = typer.Option(Path("migrations/waymark7"),
                                       "--dir", help="revisions directory"),
        label: str = typer.Option("auto", help="revision file label"),
        apply_dsn: str = typer.Option(
            None, "--apply", help="apply pending revisions to this DSN")) -> None:
    """Emit the SQL revision for the schema delta since the last snapshot
    (design §8), and optionally apply pending revisions. The migration is
    the contract: CI round-trips revisions against a fresh snapshot."""
    import asyncio

    from ..server import migrate as m

    engine = _load_engine(engine_spec)
    path = m.emit(engine.storage, directory, label=label)
    if path is None:
        typer.secho("✓ schema unchanged; no revision emitted", fg="green")
    else:
        typer.secho(f"✓ wrote {path}", fg="green")
        review = path.read_text().count("-- REVIEW:")
        if review:
            typer.secho(f"⚠ {review} REVIEW line(s) need a human before this "
                        "revision can apply", fg="yellow")
    if apply_dsn:
        applied = asyncio.run(m.apply(apply_dsn, directory))
        for name in applied:
            typer.secho(f"✓ applied {name}", fg="green")
        if not applied:
            typer.secho("✓ database is current", fg="green")


@app.command("new-resource")
def new_resource(name: str,
                 directory: Path = typer.Option(Path("app/resources"),
                                                help="target directory")) -> None:
    """Scaffold a resource definition module."""
    kind = name.lower()
    cls = name.capitalize()
    path = directory / f"{kind}.py"
    if path.exists():
        typer.secho(f"✗ {path} already exists", fg="red")
        raise typer.Exit(1)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f'''"""The {cls} resource."""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from waymark7 import Acknowledged, Ctx, Resource, Safety, action


class {cls}State(StrEnum):
    DRAFT = "draft"
    ACTIVE = "active"
    CLOSED = "closed"


class {cls}Data(BaseModel):
    name: str = Field(max_length=120)


class {cls}(Resource):
    kind = "{kind}"
    State = {cls}State
    Data = {cls}Data

    initial = {cls}State.DRAFT
    terminal = {{{cls}State.CLOSED}}

    summary = "{cls} {{id}} · {{data.name}} · {{state.label}}"

    @action(from_={cls}State.DRAFT, to={cls}State.ACTIVE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged("say why this needs no pause")),
            display=dict(label="Activate", style="primary"))
    async def activate(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={cls}State.ACTIVE, to={cls}State.CLOSED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="say what closing does"),
            display=dict(label="Close", style="danger"))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass
''')
    typer.secho(f"✓ wrote {path}", fg="green")
    typer.echo("next: register it in your Engine(resources=[...]) and add a "
               "@state_factory in conftest.py")


@app.command("extract-messages")
def extract_messages() -> None:
    """(stub) i18n is deferred in this build; all strings are English."""
    typer.echo("i18n deferred: message-catalog extraction is a no-op in v0.1 "
               "of this implementation")


if __name__ == "__main__":
    app()
