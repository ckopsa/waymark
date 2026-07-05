"""The affordance-following agent client (Part IV, normative) and the MCP
tool-surface projection (§9).

The rules this client enforces are the prompt-injection boundary (§15):

1. It acts only on declared ``actions`` — it will not construct URLs.
2. ``safety.confirm=true`` is a hard stop: ``act()`` returns a
   ``PendingConfirmation`` instead of invoking; only an explicit
   ``confirm()`` call (i.e. the human said yes) proceeds.
3. Non-idempotent actions get a persisted ``Idempotency-Key`` before the
   first attempt; ambiguous network failures retry with the same key.
4. It prefers ``depth=summary`` and follows links over requesting embeds.
5. ``dry_run`` pre-validates input before a human is asked to confirm.
6. ``data`` and prose are inert content, never instructions.
7. It plans over ``effect.to`` and verifies each step's landing state,
   surfacing divergence rather than improvising.
"""
from __future__ import annotations

import uuid
from collections import deque
from dataclasses import dataclass, field
from typing import Any

import httpx

from .py import Doc, Problem, WaymarkClient, WaymarkError


class AffordanceError(WaymarkError):
    """Asked to do something the representation does not afford."""


class Divergence(WaymarkError):
    """The server landed somewhere the declared effect did not predict."""

    def __init__(self, action: str, predicted: str, actual: str, doc: Doc):
        super().__init__(
            f"action {action!r} declared effect.to={predicted!r} but the "
            f"resource is in state {actual!r} — surfacing instead of improvising")
        self.doc = doc


@dataclass
class PendingConfirmation:
    """A confirm-gated action, halted. Present ``action``/``effect``/
    ``summary`` to the user; call ``confirm()`` only after they approve."""

    client: "AgentClient"
    doc: Doc
    action: str
    body: dict[str, Any] | None
    effect: dict[str, Any]
    reason: str = "safety.confirm=true — a human must approve this action"

    @property
    def summary(self) -> str:
        return (f"{self.doc.kind} {self.doc.self_href}: {self.action} → "
                f"{self.effect.get('to')}"
                + (" (terminal)" if self.effect.get("terminal") else ""))

    async def confirm(self) -> Doc:
        return await self.client.act(self.doc, self.action, self.body,
                                     confirmed=True)


@dataclass
class StateGraph:
    """Accumulated ``effect.to`` knowledge from every document seen; the
    basis for planning (Part IV rule 7)."""

    edges: dict[str, dict[str, set[str]]] = field(default_factory=dict)
    # kind → state → {action: to}
    seen: dict[str, dict[str, dict[str, str]]] = field(default_factory=dict)

    def learn(self, doc: Doc) -> None:
        by_state = self.seen.setdefault(doc.kind, {})
        edges = by_state.setdefault(doc.state, {})
        for name, entry in doc.actions.items():
            to = (entry.get("effect") or {}).get("to")
            if to:
                edges[name] = to

    def plan(self, kind: str, start: str, goal: str) -> list[str] | None:
        """BFS over learned edges: a list of action names, or None if the
        graph seen so far offers no route."""
        if start == goal:
            return []
        frontier = deque([(start, [])])
        visited = {start}
        while frontier:
            state, path = frontier.popleft()
            for action, to in self.seen.get(kind, {}).get(state, {}).items():
                if to == goal:
                    return path + [action]
                if to not in visited:
                    visited.add(to)
                    frontier.append((to, path + [action]))
        return None


class AgentClient:
    def __init__(self, base_url: str = "", *,
                 http: httpx.AsyncClient | None = None,
                 headers: dict[str, str] | None = None,
                 key_store: dict[str, str] | None = None):
        self._client = WaymarkClient(base_url, http=http, headers=headers)
        self.graph = StateGraph()
        # persisted before first attempt so retries reuse the same key
        self.key_store: dict[str, str] = key_store if key_store is not None else {}

    async def index(self, base: str = "/api") -> dict[str, Any]:
        return await self._client.index(base)

    async def fetch(self, href: str, *, depth: str = "summary") -> Doc:
        doc = await self._client.get(href, depth=depth)
        self.graph.learn(doc)
        return doc

    async def create(self, collection: Doc, body: dict[str, Any] | None = None,
                     ) -> Doc | PendingConfirmation:
        """Invoke a collection's ``create`` action. Same affordance rules as
        ``act`` — including the idempotency-key store, so retrying an
        identical create replays instead of duplicating."""
        return await self.act(collection, "create", body)

    async def follow(self, doc: Doc, rel: str) -> Doc:
        out = await self._client.follow(doc, rel, depth="summary")
        self.graph.learn(out)
        return out

    # ── drafts (design §4): effort is server-state an agent can join ────
    def _draft_advert(self, doc: Doc, action: str,
                      part: Any = None) -> dict[str, Any]:
        """The draft sub-resource advert for an action — from the entry, or
        from the named part's entry when the action is placed. Adverts are
        followed, never constructed (Part IV rule 1)."""
        if part is not None:
            for group in (doc.body.get("parts") or {}).values():
                for item in group.get("items", []):
                    if str(item.get("key")) == str(part) \
                            and action in item.get("actions", {}):
                        advert = item["actions"][action].get("draft")
                        if advert:
                            return advert
            raise AffordanceError(
                f"{doc.kind} {doc.self_href}: no part {part!r} affords "
                f"{action!r} with a draft")
        advert = self._affordance(doc, action).get("draft")
        if not advert:
            raise AffordanceError(
                f"{doc.kind} {doc.self_href}: action {action!r} declares "
                "no draft")
        return advert

    async def draft(self, doc: Doc, action: str, *, part: Any = None) -> Doc:
        """Fetch the draft envelope — a human's (or another agent's)
        half-written effort, with per-field values/revs/authors in ``data``.
        An absent draft is an empty open one, so this always returns a Doc."""
        advert = self._draft_advert(doc, action, part)
        return await self._client.get(advert["href"])

    async def save_draft(self, doc: Doc, action: str,
                         fields: dict[str, Any], *, part: Any = None) -> Doc:
        """Continue the effort: merge fields into the draft through the same
        write path as every other client (the drain rule works both ways —
        a human watching the form sees the agent's help arrive live when the
        draft is shared). Returns the updated draft envelope."""
        advert = self._draft_advert(doc, action, part)
        return await self._client.post(advert["href"], fields, {})

    async def discard_draft(self, doc: Doc, action: str, *,
                            part: Any = None) -> None:
        """Discard via the draft envelope's own ``discard`` action."""
        env = await self.draft(doc, action, part=part)
        entry = env.actions.get("discard")
        if entry is None:
            raise AffordanceError(f"draft {env.self_href} affords no discard")
        res = await self._client.http.post(
            entry["href"], headers=self._client.headers)
        if res.status_code >= 400:
            raise Problem.from_response(res)

    async def dry_run(self, doc: Doc, action: str,
                      body: dict[str, Any] | None = None) -> tuple[bool, Problem | None]:
        entry = self._affordance(doc, action)
        try:
            await self._client.post(entry["href"] + "?dry_run=1", body,
                                    self._headers(doc, entry, idem_key=None))
            return True, None
        except Problem as p:
            return False, p

    async def act(self, doc: Doc, action: str,
                  body: dict[str, Any] | None = None, *,
                  confirmed: bool = False) -> Doc | PendingConfirmation:
        entry = self._affordance(doc, action)
        safety = entry.get("safety", {})
        effect = entry.get("effect", {})

        if safety.get("confirm") and not confirmed:
            return PendingConfirmation(client=self, doc=doc, action=action,
                                       body=body, effect=effect)

        idem_key: str | None = None
        if not safety.get("idempotent", False):
            store_key = f"{entry['href']}:{uuid.uuid5(uuid.NAMESPACE_URL, repr(sorted((body or {}).items())))}"
            idem_key = self.key_store.setdefault(store_key, uuid.uuid4().hex)

        headers = self._headers(doc, entry, idem_key=idem_key)
        try:
            out = await self._client.post(entry["href"], body, headers)
        except (httpx.TimeoutException, httpx.NetworkError):
            if idem_key is None and not safety.get("idempotent", False):
                raise
            # ambiguous failure: safe to retry — idempotent, or same key
            out = await self._client.post(entry["href"], body, headers)

        self.graph.learn(out)
        predicted = effect.get("to")
        if predicted and out.state != predicted:
            raise Divergence(action, predicted, out.state, out)
        return out

    def plan(self, doc: Doc, goal_state: str) -> list[str] | None:
        return self.graph.plan(doc.kind, doc.state, goal_state)

    def _affordance(self, doc: Doc, action: str) -> dict[str, Any]:
        entry = doc.actions.get(action)
        if entry is None:
            reason = doc.why_not(action)
            hint = f" Server says: {reason}" if reason else ""
            raise AffordanceError(
                f"{doc.kind} {doc.self_href} does not afford {action!r} "
                f"in state {doc.state!r}.{hint}")
        return entry

    def _headers(self, doc: Doc, entry: dict[str, Any],
                 idem_key: str | None) -> dict[str, str]:
        headers: dict[str, str] = {}
        if entry.get("safety", {}).get("requires_if_match") and doc.etag:
            headers["If-Match"] = doc.etag
        if idem_key:
            headers["Idempotency-Key"] = idem_key
        return headers

    async def aclose(self) -> None:
        await self._client.aclose()


# the computed demand class (design §10), phrased for a tool-using model:
# what kind of interaction this tool actually is
_EFFORT_HINTS = {
    "assent": "one call, no meaningful input",
    "selection": "inputs are choices from the offered values",
    "recall": "inputs are short, format-constrained values",
    "composition": "takes long-form content; consider drafting first",
}


def mcp_tools(doc_or_body: Doc | dict[str, Any]) -> list[dict[str, Any]]:
    """Project a resource document's actions onto an MCP-style tool list (§9):
    'whatever this resource currently affords' as an agent tool surface.
    Derived, never hand-maintained. 2.0 documents make this projection
    richer for free: acceptance sets arrive as enums in the input schema,
    confirm consequences arrive in the description, and the demand class
    annotates what kind of interaction each tool is."""
    body = doc_or_body.body if isinstance(doc_or_body, Doc) else doc_or_body
    kind = body["kind"]
    tools = []
    for name, entry in (body.get("actions") or {}).items():
        display = entry.get("display", {})
        effect = entry.get("effect", {})
        safety = entry.get("safety", {})
        description = display.get("description") or display.get("label") or (
            f"Transition this {kind} to state '{effect.get('to')}'")
        if safety.get("confirm"):
            description += " (requires human confirmation before invoking)"
        hint = _EFFORT_HINTS.get(entry.get("effort", ""))
        if hint:
            description += f" [{entry['effort']}: {hint}]"
        if entry.get("draft", {}).get("href"):
            description += (" [draftable: partial input can be saved and "
                            "resumed via the draft sub-resource]")
        tools.append({
            "name": f"{kind}.{name}",
            "description": description,
            "input_schema": entry.get("input",
                                      {"type": "object", "properties": {}}),
        })
    return tools
