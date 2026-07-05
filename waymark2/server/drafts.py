"""Drafts as sub-resources (design §4): one concept, one write path.

A draft row is keyed ``(kind, resource_id, action, part_key, audience)``:

- ``part_key`` — the part this draft belongs to when the action is placed
  (``PartScope``); ``""`` for unplaced actions. v1's "a scoped draftable
  action shares one draft across parts" caveat is structurally gone.
- ``audience`` — ``"*"`` (shared, every principal who can see the action)
  or a principal id (private). Decided here, once, from the declared
  :class:`DraftPolicy` — the write path never chooses a principal string.

Every write lands through :meth:`DraftStore.save_fields` — the router's
PUT, the relay's drain, and the generic UI's autosave are the same code.
Per-field revisions (``revs``) and authors are maintained here, which is
what makes waymark-relay/2's ``base_rev``/``reject`` semantics enforceable
server-side instead of improvised client-side (the 41b0eac lesson).

The draft renders as an ordinary envelope (``kind: "draft"``): state
machine ``open → consumed | discarded``, ``save``/``discard`` actions, a
``channel`` link when the policy is live. GET always returns an envelope —
an absent row is an open draft with no values yet, not a 204 the client
must special-case.
"""
from __future__ import annotations

from datetime import datetime
from typing import Any

from ..core.actions import ActionDef
from ..core.registry import ResourceDef
from ..core.resource import Resource
from ..core.types import Principal

SHARED = "*"

COLLAB_CHANNEL = "waymark2_collab"
PROTOCOL = "waymark-relay/2"


def audience_of(defn: ActionDef, principal: Principal) -> str:
    """The declared audience of this action's draft. The one place the
    shared-vs-private decision is made."""
    policy = defn.draft_policy
    return SHARED if (policy is not None and policy.shared) else principal.id


def part_key_of(defn: ActionDef, body: dict[str, Any] | None) -> str:
    """The part a draft (or invoke) addresses, from the scope key field."""
    if defn.place is None or not body:
        return ""
    value = body.get(defn.place.key)
    return "" if value is None else str(value)


def draft_href(base: str, rdef: ResourceDef, resource_id: str,
               action: str, part_key: str = "") -> str:
    href = f"{base}/{rdef.plural}/{resource_id}/-/{action}/draft"
    return f"{href}?part={part_key}" if part_key else href


class DraftStore:
    """The single write path for draft effort."""

    def __init__(self, storage: Any, bus: Any = None):
        self.storage = storage
        self.bus = bus  # liveness only; the tables are the truth

    async def save_fields(
        self, s: Any, *, rdef: ResourceDef, defn: ActionDef,
        resource_id: str, part_key: str, audience: str,
        fields: dict[str, Any], base_version: int,
        actor: Principal, at: datetime,
    ) -> dict[str, Any]:
        """Merge field updates into the draft; bump each touched field's rev.

        ``None`` clears a field (its rev still advances — clearing is an
        edit). Returns the stored row (values/revs/authors/saved_at).
        """
        rows = await self.storage.load_drafts(s, rdef.kind, resource_id)
        row = rows.get((defn.name, part_key, audience))
        values = dict(row["values"]) if row else {}
        revs = dict(row["revs"]) if row else {}
        authors = dict(row["authors"]) if row else {}
        for field, value in fields.items():
            if value is None:
                values.pop(field, None)
            else:
                values[field] = value
            revs[field] = int(revs.get(field, 0)) + 1
            authors[field] = {"id": actor.id, "display": actor.display or actor.id,
                              "at": at.isoformat()}
        await self.storage.save_draft(
            s, kind=rdef.kind, resource_id=resource_id, action=defn.name,
            part_key=part_key, audience=audience, values=values, revs=revs,
            authors=authors, base_version=base_version, at=at)
        return {"values": values, "revs": revs, "authors": authors,
                "saved_at": at, "base_version": base_version}

    async def load(self, s: Any, rdef: ResourceDef, defn: ActionDef,
                   resource_id: str, part_key: str,
                   audience: str) -> dict[str, Any] | None:
        rows = await self.storage.load_drafts(s, rdef.kind, resource_id)
        return rows.get((defn.name, part_key, audience))

    async def discard(self, s: Any, rdef: ResourceDef, defn: ActionDef,
                      resource_id: str, part_key: str, audience: str) -> None:
        await self.storage.delete_draft(
            s, rdef.kind, resource_id, defn.name, part_key, audience)

    async def consume(self, s: Any, rdef: ResourceDef, defn: ActionDef,
                      resource_id: str, part_key: str, audience: str) -> None:
        """A successful invoke consumed the effort — same delete, different
        lifecycle event for the room."""
        await self.storage.delete_draft(
            s, rdef.kind, resource_id, defn.name, part_key, audience)

    async def announce(self, *, rdef: ResourceDef, defn: ActionDef,
                       resource_id: str, part_key: str,
                       frame: dict[str, Any]) -> None:
        """Publish a room frame on the bus (design §7): every worker's rooms
        deliver it locally, so collab liveness survives multi-worker."""
        if self.bus is None:
            return
        await self.bus.publish(COLLAB_CHANNEL, {
            "kind": rdef.kind, "id": resource_id, "action": defn.name,
            "part": part_key, "frame": frame})


def render_draft(rdef: ResourceDef, defn: ActionDef, instance: Resource,
                 row: dict[str, Any] | None, *, base: str,
                 part_key: str = "") -> dict[str, Any]:
    """The draft's envelope. Ordinary Waymark: consume only this and you can
    read, continue, or discard someone's half-written effort."""
    self_href = draft_href(base, rdef, instance.id, defn.name, part_key)
    values = dict(row["values"]) if row else {}
    revs = {k: int(v) for k, v in (row["revs"] if row else {}).items()}
    authors = dict(row["authors"]) if row else {}
    saved_at = row["saved_at"].isoformat() if row else None
    base_version = row["base_version"] if row else instance.version
    stale = row is not None and row["base_version"] != instance.version
    version = max(revs.values(), default=0)
    policy = defn.draft_policy

    authors_line = ", ".join(sorted({a["display"] for a in authors.values()})) \
        if authors else None
    # prose is human-facing: the action's label, never its token
    label = dict(defn.display).get("label") \
        or defn.name.replace("_", " ").capitalize()
    summary = (f"Draft of '{label}' · saved {saved_at} · by {authors_line}"
               if row else f"Draft of '{label}' · empty")
    if stale:
        summary += " · stale"

    doc: dict[str, Any] = {
        "waymark": "2",
        "kind": "draft",
        "self": self_href,
        "state": "open",
        "summary": summary[:140],
        "data": {
            "for_action": defn.name,
            **({"part": part_key} if part_key else {}),
            "base_version": base_version,
            "stale": stale,
            "values": values,
            "revs": revs,
            "authors": authors,
            **({"saved_at": saved_at} if saved_at else {}),
        },
        "actions": {
            "save": {
                "method": "POST",
                "href": self_href,
                "input": rdef.action_schemas[defn.name][0],
                "effect": {"to": "open"},
                "safety": {"idempotent": True, "reversible": True,
                           "confirm": False},
            },
            "discard": {
                "method": "POST",
                "href": draft_href(base, rdef, instance.id, defn.name,
                                   part_key).replace("/draft", "/draft/-/discard", 1),
                "effect": {"to": "discarded", "terminal": True},
                "safety": {"idempotent": True, "reversible": False,
                           "confirm": False},
            },
        },
        "unavailable": {},
        "links": {
            "parent": {"href": f"{base}/{rdef.plural}/{instance.id}",
                       "kind": rdef.kind,
                       "summary": f"The {rdef.kind} this draft belongs to"},
        },
        "meta": {"version": version,
                 "etag": f'W/"draft-{rdef.kind}-{instance.id}-{defn.name}'
                         f'{"-" + part_key if part_key else ""}-v{version}"'},
    }
    if policy is not None and policy.live:
        collab = f"{base}/{rdef.plural}/{instance.id}/-/{defn.name}/draft/collab"
        if part_key:
            collab += f"?part={part_key}"
        doc["links"]["channel"] = {"href": collab, "kind": "relay",
                                   "summary": f"Live co-editing ({PROTOCOL})"}
    return doc
