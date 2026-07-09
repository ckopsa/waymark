"""``waymark7 client``: the affordance-following agent client as a CLI.

One shell call per affordance, driven by
:class:`~waymark7.client.AgentClient` so every Part IV rule stays *enforced*
rather than remembered: no URL construction (actions are named, hrefs come
from the envelope), automatic ``Idempotency-Key`` and ``If-Match``, dry-run
pre-validation, and ``safety.confirm`` as a hard stop — a confirm-gated
action exits with code 3 and does nothing until re-run with ``--confirmed``
(the flag is the human approval, visible in whatever harness runs the
command).

2.0: action listings show each affordance's demand class and draftability,
and the ``draft`` group joins in-progress effort — ``draft show`` reads a
half-written form (yours or anyone's, when shared), ``draft save`` continues
it through the same write path as every other client, ``draft discard``
exits via the envelope's own action. ``--part`` addresses a placed action's
per-part draft.

A per-server session file persists the idempotency key store and the learned
``effect.to`` graph across invocations, so retries replay instead of
duplicating and ``plan`` can route over states seen in earlier calls.

Exit codes: 0 ok · 1 problem/transport · 2 not afforded · 3 confirmation
required · 4 divergence (server landed somewhere ``effect.to`` didn't predict).
"""
from __future__ import annotations

import asyncio
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx
import typer

from ..client.agent import (AffordanceError, AgentClient, Divergence,
                            PendingConfirmation)
from ..client.py import Doc, Problem

client_app = typer.Typer(help="Talk to a Waymark API by following affordances.",
                         no_args_is_help=True)

EXIT_PROBLEM, EXIT_NOT_AFFORDED, EXIT_CONFIRM, EXIT_DIVERGED = 1, 2, 3, 4


@dataclass
class Settings:
    base: str
    headers: dict[str, str]
    session_path: Path
    raw: bool


def _parse_headers(pairs: list[str], principal: str | None) -> dict[str, str]:
    headers: dict[str, str] = {}
    if principal:
        pid, _, rest = principal.partition(":")
        ptype, _, display = rest.partition(":")
        headers["X-Principal-Id"] = pid
        headers["X-Principal-Type"] = ptype or "agent"
        if display:
            headers["X-Principal-Display"] = display
    for pair in pairs:
        name, sep, value = pair.partition(":")
        if not sep:
            raise typer.BadParameter(f"header {pair!r} is not 'Name: value'")
        headers[name.strip()] = value.strip()
    return headers


def _default_session(base: str) -> Path:
    slug = re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-")
    return Path.home() / ".waymark" / "cli" / f"{slug}.json"


@client_app.callback()
def _main(ctx: typer.Context,
          base: str = typer.Option("http://127.0.0.1:8000", "--base", "-b",
                                   envvar="WAYMARK_BASE",
                                   help="server base URL"),
          principal: str | None = typer.Option(
              None, "--as", envvar="WAYMARK_AS",
              help="dev principal: 'id[:type[:Display]]' → X-Principal-* "
                   "headers (type defaults to 'agent')"),
          token: str | None = typer.Option(
              None, "--token", envvar="WAYMARK_TOKEN",
              help="agent-link token (wmk_…) → Authorization: Bearer; "
                   "scope enforcement applies"),
          header: list[str] = typer.Option(
              [], "--header", "-H", help="extra header, 'Name: value'"),
          session: Path | None = typer.Option(
              None, "--session", envvar="WAYMARK_SESSION",
              help="session file (idempotency keys + learned state graph); "
                   "default is per-server under ~/.waymark/cli/"),
          raw: bool = typer.Option(False, "--raw",
                                   help="print full JSON bodies")) -> None:
    headers = _parse_headers(header, principal)
    if token:
        headers["Authorization"] = f"Bearer {token}"
    ctx.obj = Settings(base=base, headers=headers,
                       session_path=session or _default_session(base), raw=raw)


# ── session persistence ─────────────────────────────────────────────────
def _load_session(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def _save_session(path: Path, agent: AgentClient) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"key_store": agent.key_store,
                                "graph": agent.graph.seen}))


def _run(settings: Settings,
         fn: Callable[[AgentClient], Awaitable[int | None]]) -> None:
    async def go() -> int | None:
        stored = _load_session(settings.session_path)
        agent = AgentClient(settings.base, headers=settings.headers,
                            key_store=stored.get("key_store", {}))
        agent.graph.seen = stored.get("graph", {})
        try:
            return await fn(agent)
        except Problem as p:
            _print_problem(p, raw=settings.raw)
            return EXIT_PROBLEM
        except AffordanceError as e:
            typer.secho(f"✗ {e}", fg="red")
            return EXIT_NOT_AFFORDED
        except Divergence as e:
            typer.secho(f"⚠ {e}", fg="yellow")
            _print_doc(e.doc, raw=settings.raw)
            return EXIT_DIVERGED
        except httpx.HTTPError as e:
            typer.secho(f"✗ cannot reach {settings.base}: {e}", fg="red")
            return EXIT_PROBLEM
        finally:
            _save_session(settings.session_path, agent)
            await agent.aclose()

    code = asyncio.run(go())
    if code:
        raise typer.Exit(code)


# ── rendering ───────────────────────────────────────────────────────────
def _trunc(value: Any, limit: int = 160) -> Any:
    if isinstance(value, str) and len(value) > limit:
        return value[:limit] + f"… [{len(value)} chars; --raw for full]"
    if isinstance(value, dict):
        return {k: _trunc(v, limit) for k, v in value.items()}
    if isinstance(value, list):
        return [_trunc(v, limit) for v in value]
    return value


def _input_summary(entry: dict[str, Any]) -> str:
    schema = entry.get("input")
    if not schema:
        return ""
    if "$ref" in schema:
        return f"  input: {schema['$ref']}"
    required = set(schema.get("required", []))
    fields = [name + ("*" if name in required else "?")
              for name in schema.get("properties", {})]
    return f"  input: {', '.join(fields)}" if fields else ""


def _flags(entry: dict[str, Any]) -> str:
    safety = entry.get("safety", {})
    effect = entry.get("effect", {})
    flags = [label for label, on in (
        ("confirm", safety.get("confirm")),
        ("if-match", safety.get("fence")),
        ("non-idempotent", not safety.get("idempotent", False)),
        ("bulk", effect.get("bulk")),
        ("draftable", bool(entry.get("draft"))),
    ) if on]
    if entry.get("effort"):
        flags.insert(0, entry["effort"])  # the demand class, first
    return f" [{', '.join(flags)}]" if flags else ""


def _print_actions(doc: Doc) -> None:
    if doc.actions:
        typer.secho("actions:", bold=True)
        width = max(len(n) for n in doc.actions)
        for name, entry in doc.actions.items():
            effect = entry.get("effect", {})
            arrow = (f"→ {effect['to']}" if effect.get("to") else "")
            arrow += " (terminal)" if effect.get("terminal") else ""
            typer.echo(f"  {name:<{width}}  {arrow}{_flags(entry)}"
                       f"{_input_summary(entry)}")
    if doc.unavailable:
        typer.secho("unavailable:", bold=True)
        for name, entry in doc.unavailable.items():
            hope = entry.get("becomes_available")
            typer.echo(f"  {name}: {entry.get('reason', '')}"
                       + (f"  {json.dumps(hope)}" if hope else ""))


def _print_doc(doc: Doc, *, raw: bool = False) -> None:
    if raw:
        typer.echo(json.dumps(doc.body, indent=2, default=str))
        return
    # not every GET is a resource envelope: the public catalog surfaces
    # (e.g. /-/grantable) are plain JSON with no "kind" — pretty-print them
    # rather than assuming the envelope shape and crashing
    if "kind" not in doc.body:
        typer.echo(json.dumps(doc.body, indent=2, default=str))
        return
    meta = doc.body.get("meta") or {}
    version = f" · v{meta['version']}" if meta.get("version") is not None else ""
    typer.secho(f"{doc.kind} {doc.self_href} · state={doc.state}{version}",
                bold=True)
    typer.echo(doc.summary)
    items = doc.data.get("items")
    if isinstance(items, list) and items and isinstance(items[0], dict) \
            and "self" in items[0]:
        for item in doc.items:
            typer.echo(f"  {item.self_href} · {item.state} · {item.summary}")
        rest = {k: v for k, v in doc.data.items() if k != "items"}
        if rest:
            typer.echo(f"data: {json.dumps(_trunc(rest), default=str)}")
    else:
        typer.echo(f"data: {json.dumps(_trunc(doc.data), default=str)}")
    _print_actions(doc)
    if doc.links:
        typer.secho("links:", bold=True)
        for rel, link in doc.links.items():
            summary = f" · {link['summary']}" if link.get("summary") else ""
            typer.echo(f"  {rel} → {link['href']}{summary}")


def _print_problem(p: Problem, *, raw: bool = False) -> None:
    if raw:
        typer.echo(json.dumps(p.raw, indent=2, default=str))
        return
    typer.secho(f"✗ {p.status} {p.title}", fg="red", bold=True)
    if p.detail:
        typer.echo(p.detail)
    for field_name, messages in (p.errors or {}).items():
        typer.echo(f"  {field_name}: {'; '.join(messages)}")
    if p.actions:
        typer.echo(f"offered next: {', '.join(p.actions)}")
    if p.resource:
        typer.echo(f"resource: {p.resource.get('self')} · "
                   f"state={p.resource.get('state')} · "
                   f"{p.resource.get('summary', '')}")


def _parse_body(body_json: str | None) -> dict[str, Any] | None:
    if body_json is None:
        return None
    text = sys.stdin.read() if body_json == "-" else body_json
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        raise typer.BadParameter(f"--json is not valid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise typer.BadParameter("--json must be a JSON object")
    return parsed


# ── commands ────────────────────────────────────────────────────────────
@client_app.command()
def index(ctx: typer.Context,
          api_base: str = typer.Option("/api", help="API mount path")) -> None:
    """Discover the server: kinds, root collections, profiles."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> None:
        body = await agent.index(api_base)
        if settings.raw:
            typer.echo(json.dumps(body, indent=2))
            return
        typer.echo(f"waymark {body.get('waymark')} · {body.get('media_type')}")
        for kind, href in (body.get("collections") or {}).items():
            typer.echo(f"  {kind:<16} {href}")

    _run(settings, go)


@client_app.command()
def get(ctx: typer.Context, href: str,
        depth: str = typer.Option("summary", help="summary | full | "
                                                  "expanded:<profile>")) -> None:
    """Fetch a resource or collection document."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> None:
        _print_doc(await agent.fetch(href, depth=depth), raw=settings.raw)

    _run(settings, go)


@client_app.command()
def follow(ctx: typer.Context, href: str, rel: str) -> None:
    """Fetch HREF, then follow its link REL."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> None:
        doc = await agent.fetch(href)
        _print_doc(await agent.follow(doc, rel), raw=settings.raw)

    _run(settings, go)


@client_app.command()
def schema(ctx: typer.Context, href: str, action_name: str) -> None:
    """Print an action's full input JSON Schema."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> int | None:
        doc = await agent.fetch(href)
        entry = doc.actions.get(action_name) or doc.unavailable.get(action_name)
        if entry is None:
            typer.secho(f"✗ {doc.kind} declares no action {action_name!r}",
                        fg="red")
            return EXIT_NOT_AFFORDED
        typer.echo(json.dumps(entry.get("input") or
                              {"type": "object", "properties": {}}, indent=2))
        return None

    _run(settings, go)


def _act(ctx: typer.Context, href: str, action_name: str,
         body_json: str | None, confirmed: bool, dry_run: bool) -> None:
    settings: Settings = ctx.obj
    body = _parse_body(body_json)

    async def go(agent: AgentClient) -> int | None:
        doc = await agent.fetch(href)
        if dry_run:
            ok, problem = await agent.dry_run(doc, action_name, body)
            if ok:
                typer.secho("✓ valid — schema and guards accept this input",
                            fg="green")
                return None
            assert problem is not None
            _print_problem(problem, raw=settings.raw)
            return EXIT_PROBLEM
        out = await agent.act(doc, action_name, body, confirmed=confirmed)
        if isinstance(out, PendingConfirmation):
            typer.secho(f"⏸ confirmation required: {out.summary}", fg="yellow",
                        bold=True)
            typer.echo(out.reason)
            typer.echo("re-run with --confirmed once a human has approved")
            return EXIT_CONFIRM
        _print_doc(out, raw=settings.raw)
        return None

    _run(settings, go)


@client_app.command()
def act(ctx: typer.Context, href: str, action_name: str,
        body_json: str | None = typer.Option(
            None, "--json", help="action input as JSON ('-' reads stdin)"),
        confirmed: bool = typer.Option(
            False, "--confirmed",
            help="a human approved this confirm-gated action"),
        dry_run: bool = typer.Option(
            False, "--dry-run",
            help="validate schema+guards server-side; no transition")) -> None:
    """Invoke a declared action on the resource at HREF."""
    _act(ctx, href, action_name, body_json, confirmed, dry_run)


@client_app.command()
def create(ctx: typer.Context, collection_href: str,
           body_json: str | None = typer.Option(
               None, "--json", help="create input as JSON ('-' reads stdin)"),
           confirmed: bool = typer.Option(False, "--confirmed"),
           dry_run: bool = typer.Option(False, "--dry-run")) -> None:
    """Invoke a collection's create action (alias for `act … create`)."""
    _act(ctx, collection_href, "create", body_json, confirmed, dry_run)


# ── drafts (design §4): effort is server-state a shell can join ─────────
draft_app = typer.Typer(help="Read, continue, or discard in-progress draft "
                             "effort on a draftable action.",
                        no_args_is_help=True)
client_app.add_typer(draft_app, name="draft")


def _print_draft(env: Doc, *, raw: bool) -> None:
    if raw:
        typer.echo(json.dumps(env.body, indent=2, default=str))
        return
    typer.secho(f"{env.summary}", bold=True)
    data = env.data
    if data.get("stale"):
        typer.secho("⚠ stale: the resource moved on since this draft was "
                    "saved — review before committing", fg="yellow")
    values = data.get("values") or {}
    if not values:
        typer.echo("(empty)")
    for field_name, value in values.items():
        rev = (data.get("revs") or {}).get(field_name)
        author = ((data.get("authors") or {}).get(field_name) or {})
        who = author.get("display") or author.get("id") or ""
        meta = " · ".join(x for x in
                          (f"rev {rev}" if rev else "", who) if x)
        typer.secho(f"{field_name}" + (f"  ({meta})" if meta else ""),
                    bold=True)
        typer.echo(f"  {_trunc(value)}")


@draft_app.command("show")
def draft_show(ctx: typer.Context, href: str, action_name: str,
               part: str | None = typer.Option(
                   None, "--part", help="part key of a placed action")) -> None:
    """Read a draft's current truth (an absent draft is an empty open one)."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> None:
        doc = await agent.fetch(href, depth="full" if part else "summary")
        _print_draft(await agent.draft(doc, action_name, part=part),
                     raw=settings.raw)

    _run(settings, go)


@draft_app.command("save")
def draft_save(ctx: typer.Context, href: str, action_name: str,
               body_json: str = typer.Option(
                   ..., "--json", help="fields to merge as JSON ('-' reads "
                                       "stdin; null clears a field)"),
               part: str | None = typer.Option(
                   None, "--part", help="part key of a placed action")) -> None:
    """Merge fields into the draft — the same write path as every other
    client, so collaborators watching the form see it arrive live."""
    settings: Settings = ctx.obj
    fields = _parse_body(body_json)

    async def go(agent: AgentClient) -> None:
        doc = await agent.fetch(href, depth="full" if part else "summary")
        _print_draft(await agent.save_draft(doc, action_name, fields or {},
                                            part=part),
                     raw=settings.raw)

    _run(settings, go)


@draft_app.command("discard")
def draft_discard(ctx: typer.Context, href: str, action_name: str,
                  part: str | None = typer.Option(
                      None, "--part",
                      help="part key of a placed action")) -> None:
    """Discard the draft via its envelope's own discard action."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> None:
        doc = await agent.fetch(href, depth="full" if part else "summary")
        await agent.discard_draft(doc, action_name, part=part)
        typer.secho("✓ draft discarded", fg="green")

    _run(settings, go)


@client_app.command()
def watch(ctx: typer.Context,
          actor: str | None = typer.Option(
              None, "--actor", help="follow one principal's transitions "
                                    "(e.g. an agent you asked to work)"),
          kinds: str | None = typer.Option(
              None, "--kinds", help="comma-separated kind filter"),
          presence: bool = typer.Option(
              False, "--presence",
              help="also stream navigation ('viewed' lines, ephemeral)"),
          api_base: str = typer.Option("/api", help="API mount path")) -> None:
    """Stream live transitions from the workspace firehose (Ctrl-C stops).

    Supervision from a shell: `watch --actor claude` prints every action
    that principal takes, as it lands in the audit log — the same events
    that drive the UI's follow mode. `--presence` interleaves where they
    merely navigate (liveness only; never stored, never replayable).
    """
    import asyncio as _asyncio

    settings: Settings = ctx.obj

    def _print_transition(ev: dict[str, Any]) -> None:
        when = ev.get("at", "")[11:19]
        a = ev.get("actor", {})
        who_s = a.get("display") or a.get("id", "?")
        typer.echo("".join([
            typer.style(when, fg="bright_black"), "  ",
            typer.style(f"{who_s}",
                        fg="cyan" if a.get("type") == "agent" else None,
                        bold=True),
            f" {ev.get('action')}  ",
            f"{ev.get('kind')} {ev.get('from') or '·'} → {ev.get('to')}  ",
            typer.style(ev.get("self", ""), fg="bright_black"),
        ]))
        if settings.raw and ev.get("summary"):
            typer.echo(f"          {ev['summary']}")

    def _print_viewed(ev: dict[str, Any]) -> None:
        when = ev.get("at", "")[11:19]
        a = ev.get("actor", {})
        who_s = a.get("display") or a.get("id", "?")
        if ev.get("action"):
            verb = {"form": "opened form", "dry_run": "filling form",
                    "discard": "discarded draft"}.get(ev.get("via"), "engaged")
            line = (f"{when}  {who_s} {verb} '{ev['action']}' on "
                    f"{ev.get('kind')}  {ev.get('self', '')}")
        else:
            line = f"{when}  {who_s} viewed  {ev.get('kind')}  {ev.get('self', '')}"
        typer.echo(typer.style(line, fg="bright_black"))

    async def _stream(agent: AgentClient, target: str,
                      params: dict[str, str],
                      printer: Callable[[dict[str, Any]], None]) -> None:
        async with agent._client.http.stream(
                "GET", target, params=params,
                headers=agent._client.headers, timeout=None) as res:
            res.raise_for_status()
            frame: dict[str, str] = {}
            async for line in res.aiter_lines():
                line = line.strip()
                if line:
                    key, _, value = line.partition(":")
                    frame[key.strip()] = value.strip()
                    continue
                if "data" in frame:
                    printer(json.loads(frame["data"]))
                frame = {}

    async def go(agent: AgentClient) -> None:
        params = {}
        if actor:
            params["actor"] = actor
        if kinds:
            params["kinds"] = kinds
        who = f" · actor={actor}" if actor else ""
        extra = " + presence" if presence else ""
        typer.secho(f"watching {settings.base}{api_base}/-/events{who}{extra}"
                    " — Ctrl-C stops", fg="cyan")
        streams = [_stream(agent, f"{api_base}/-/events", params,
                           _print_transition)]
        if presence:
            streams.append(_stream(agent, f"{api_base}/-/presence", params,
                                   _print_viewed))
        await _asyncio.gather(*streams)

    _run(settings, go)


@client_app.command("follow-link")
def follow_link(ctx: typer.Context,
                principal_id: str = typer.Argument(
                    None, help="principal to follow; default: the CLI's own "
                               "principal (--as / WAYMARK_AS)"),
                name: str = typer.Option(
                    None, "--name", help="display name shown in the "
                                         "follower's chip"),
                at: str = typer.Option(
                    None, "--at", help="land the opener on this href "
                                       "(e.g. /api/plans/…)"),
                api_base: str = typer.Option("/api", help="API mount path")) -> None:
    """Print a UI link that makes its opener follow a principal.

    `follow-link` with no argument is "follow me" (the CLI's principal) —
    useful for agents that announce their own supervision link when they
    start work. The UI path comes from discovery, never hardcoded.
    """
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> int | None:
        from urllib.parse import urlencode

        index = await agent.index(api_base)
        ui = index.get("ui") or f"{api_base}/-/ui"
        own = settings.headers.get("X-Principal-Id")
        pid = principal_id or own
        if not pid:
            typer.secho("✗ no principal: pass one, or set --as / WAYMARK_AS",
                        fg="red")
            return EXIT_NOT_AFFORDED
        display = name or (settings.headers.get("X-Principal-Display")
                           if pid == own else None) or pid
        q = {"follow": pid}
        if display != pid:
            q["follow_name"] = display
        typer.echo(f"{settings.base}{ui}?{urlencode(q)}"
                   + (f"#{at}" if at else ""))
        return None

    _run(settings, go)


@client_app.command()
def plan(ctx: typer.Context, href: str, goal_state: str) -> None:
    """Route from HREF's current state to GOAL_STATE over the learned
    effect.to graph (grows as this session sees more documents)."""
    settings: Settings = ctx.obj

    async def go(agent: AgentClient) -> int | None:
        doc = await agent.fetch(href)
        route = agent.plan(doc, goal_state)
        if route is None:
            typer.secho(f"✗ no route from {doc.state!r} to {goal_state!r} in "
                        "the states seen so far — fetch more documents",
                        fg="red")
            return EXIT_NOT_AFFORDED
        typer.echo(" → ".join([doc.state, *route,
                               goal_state] if route else [doc.state]))
        return None

    _run(settings, go)
