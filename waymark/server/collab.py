"""The collab seam (§2.2): a live channel that drains into the draft.

A ``collab=True`` action's draft entry advertises a WebSocket relay. The
protocol (``waymark-relay/1``) is deliberately small — field-level updates,
presence, and lifecycle — because the seam's one obligation is the drain
rule: every accepted update is persisted through the same storage path as a
plain draft PUT, so a dropped connection loses at most the frame in flight,
a client that cannot speak the protocol still sees the current truth via
``GET {draft.href}``, and an assisting agent reading the envelope sees the
work in progress. Effort may be spent off-wire; it may never be stranded
off-wire.

Wire messages (JSON text frames):

  server → joiner   {"type": "state", "values", "saved_at", "stale",
                     "participants"}
  client → server   {"type": "update", "values": {field: value}}
                    (a ``null`` value clears the field from the draft)
  server → sender   {"type": "saved", "saved_at"}
  server → others   {"type": "update", "values", "actor", "saved_at"}
  server → room     {"type": "presence", "event": "joined"|"left",
                     "actor", "participants"}
  server → room     {"type": "closed", "reason": "consumed"|"discarded"
                     |"gone"} — then the socket closes
  server → sender   {"type": "error", "errors": {field: [msgs]}}

Collab drafts are shared, not per-principal: they are stored under the
sentinel principal id ``"*"`` and their ``values`` render for every
principal who can see the action — collaborators are looking at the same
half-written effort, which is the point.

Rooms are in-process (single worker) in v0.1; see implementation notes.
"""
from __future__ import annotations

import asyncio
import json
from typing import Any

PROTOCOL = "waymark-relay/1"
SHARED = "*"  # principal_id sentinel for shared (collab) drafts


def actor_of(principal: Any) -> dict[str, str]:
    return {"id": principal.id, "type": principal.type,
            "display": principal.display or principal.id}


class Room:
    def __init__(self) -> None:
        self.lock = asyncio.Lock()  # serializes drains for one draft
        self.members: dict[Any, Any] = {}  # websocket → Principal


class CollabRooms:
    """In-process rooms keyed (kind, resource_id, action)."""

    def __init__(self, engine: Any) -> None:
        self.engine = engine
        self.rooms: dict[tuple[str, str, str], Room] = {}

    def room(self, key: tuple[str, str, str]) -> Room:
        return self.rooms.setdefault(key, Room())

    def participants(self, key: tuple[str, str, str]) -> list[dict[str, str]]:
        room = self.rooms.get(key)
        return [actor_of(p) for p in room.members.values()] if room else []

    async def broadcast(self, key: tuple[str, str, str],
                        message: dict[str, Any], *, exclude: Any = None) -> None:
        room = self.rooms.get(key)
        if room is None:
            return
        payload = json.dumps(message, default=str)
        for ws in list(room.members):
            if ws is exclude:
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                room.members.pop(ws, None)

    async def close(self, key: tuple[str, str, str], reason: str) -> None:
        """The draft is gone (consumed by an invoke, discarded, or its
        resource vanished): tell everyone, then hang up."""
        room = self.rooms.pop(key, None)
        if room is None:
            return
        payload = json.dumps({"type": "closed", "reason": reason})
        for ws in list(room.members):
            try:
                await ws.send_text(payload)
                await ws.close()
            except Exception:
                pass


async def _drain(engine: Any, rdef: Any, defn: Any, resource_id: str,
                 values: dict[str, Any], room: Room) -> str | None:
    """The drain rule: merge the update into the stored draft in its own
    transaction. Returns the save timestamp, or None if the resource is
    gone."""
    async with room.lock:
        async with engine.storage.session() as s:
            instance = await engine.storage.load(s, rdef.kind, resource_id)
            if instance is None:
                return None
            rows = await engine.storage.load_drafts(
                s, rdef.kind, resource_id, SHARED)
            row = rows.get(defn.name)
            merged = dict(row["values"]) if row else {}
            for field, value in values.items():
                if value is None:
                    merged.pop(field, None)
                else:
                    merged[field] = value
            now = engine.invoker.clock()
            await engine.storage.save_draft(
                s, kind=rdef.kind, resource_id=resource_id, action=defn.name,
                principal_id=SHARED, values=merged,
                base_version=instance.version, at=now)
    return now.isoformat()


async def serve(engine: Any, ws: Any, rdef: Any, defn: Any,
                resource_id: str, principal: Any) -> None:
    """Drive one participant's connection for the lifetime of the socket."""
    from starlette.websockets import WebSocketDisconnect

    rooms: CollabRooms = engine.collab
    key = (rdef.kind, resource_id, defn.name)
    await ws.accept()
    # the joiner gets the draft's current truth first — same contract as
    # GET {draft.href}: never trust an envelope rendered before typing began
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, rdef.kind, resource_id)
        if instance is None:
            await ws.close(code=4404)
            return
        rows = await engine.storage.load_drafts(s, rdef.kind, resource_id,
                                                SHARED)
    row = rows.get(defn.name)
    room = rooms.room(key)
    room.members[ws] = principal
    await ws.send_text(json.dumps({
        "type": "state",
        "values": row["values"] if row else {},
        "saved_at": row["saved_at"].isoformat() if row else None,
        "stale": (row["base_version"] != instance.version) if row else False,
        "participants": rooms.participants(key),
    }, default=str))
    await rooms.broadcast(key, {
        "type": "presence", "event": "joined", "actor": actor_of(principal),
        "participants": rooms.participants(key)}, exclude=ws)
    known = set(rdef.action_schemas[defn.name][0].get("properties", {}))
    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await ws.send_text(json.dumps({
                    "type": "error", "errors": {"_root": ["invalid JSON"]}}))
                continue
            if not isinstance(msg, dict) or msg.get("type") != "update" \
                    or not isinstance(msg.get("values"), dict):
                await ws.send_text(json.dumps({"type": "error", "errors": {
                    "_root": ["expected {\"type\": \"update\", "
                              "\"values\": {…}}"]}}))
                continue
            values = msg["values"]
            unknown = set(values) - known
            if unknown:
                # same contract as a draft PUT: fields must be a subset of
                # the action's schema; values may be invalid mid-edit
                await ws.send_text(json.dumps({"type": "error", "errors": {
                    f: ["unknown field"] for f in sorted(unknown)}}))
                continue
            saved_at = await _drain(engine, rdef, defn, resource_id, values,
                                    room)
            if saved_at is None:
                await rooms.close(key, "gone")
                return
            await ws.send_text(json.dumps(
                {"type": "saved", "saved_at": saved_at}))
            await rooms.broadcast(key, {
                "type": "update", "values": values,
                "actor": actor_of(principal), "saved_at": saved_at},
                exclude=ws)
    except WebSocketDisconnect:
        pass
    finally:
        room.members.pop(ws, None)
        if room.members:
            await rooms.broadcast(key, {
                "type": "presence", "event": "left",
                "actor": actor_of(principal),
                "participants": rooms.participants(key)})
        else:
            rooms.rooms.pop(key, None)
