"""waymark-relay/2 (design §5): per-field revisions on the wire.

v1's relay shipped whole-value frames and left merge discipline to client
debounce timers — which dropped remote edits the moment a field was focused
(commit 41b0eac). Relay/2 makes the merge discipline protocol:

  server → joiner   {"type": "state", "values", "revs", "saved_at",
                     "stale", "participants"}
  client → server   {"type": "update", "field", "value", "base_rev"}
                    (``null`` value clears the field; ``base_rev`` is the
                    rev this edit was based on)
  server → sender   {"type": "saved", "field", "rev"}
  server → others   {"type": "update", "field", "value", "rev", "actor",
                     "saved_at"}
  server → sender   {"type": "reject", "field", "rev", "value"}
                    (base_rev was stale: here is the field's truth — apply
                    it, then re-edit)
  server → room     {"type": "presence", "event": "joined"|"left",
                     "actor", "participants"}
  server → room     {"type": "closed", "reason": "consumed"|"discarded"
                     |"gone"} — then the socket closes
  server → sender   {"type": "error", "errors": {field: [msgs]}}

Merge discipline: server-ordered per-field last-write-wins with explicit
staleness rejection — modest, declared, and enforced here rather than
emergent from two debounce timers. Character-level merge (CRDT/OT) is a
different ``protocol`` token; the drain rule and the revs map bind those
too.

The drain rule is inherited, not re-implemented: every accepted update goes
through :meth:`DraftStore.save_fields` — the same code as a plain draft
PUT. Rooms are local; liveness crosses workers via the bus (design §7):
accepted frames publish on ``waymark4_collab``, every worker's rooms
deliver to their local members, and the publisher skips its own echo.

Joining requires the parent action to render in ``actions`` for the
principal (design §11) — v1 gated on resource visibility only.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import Any

from .drafts import COLLAB_CHANNEL, PROTOCOL, Audience, audience_of

log = logging.getLogger("waymark4.collab")

__all__ = ["PROTOCOL", "Audience", "CollabRooms", "serve", "actor_of"]


def actor_of(principal: Any) -> dict[str, str]:
    return {"id": principal.id, "type": principal.type,
            "display": principal.display or principal.id}


RoomKey = tuple[str, str, str, str]  # (kind, resource_id, action, part_key)


class Room:
    def __init__(self) -> None:
        self.lock = asyncio.Lock()  # serializes drains for one draft
        self.members: dict[Any, Any] = {}  # websocket → Principal


class CollabRooms:
    """Local rooms + the bus. ``broadcast`` is local-only; ``announce`` (via
    DraftStore) is what crosses workers."""

    def __init__(self, engine: Any) -> None:
        self.engine = engine
        self.rooms: dict[RoomKey, Room] = {}
        self._regate_sub: Any = None
        self._regate_task: Any = None
        engine.bus.listen(COLLAB_CHANNEL, self._on_bus)

    # ── affordance regate (design §2) ───────────────────────────────────
    # The join gate is re-run on affordance-changing transitions: a grant
    # revocation or a state change drops lapsed members' sockets *now*, not
    # when the room happens to close. v2's caveat ("a principal whose
    # affordance lapses keeps the socket") is structurally gone.
    def start_regate(self, dispatcher: Any) -> None:
        self._regate_sub = dispatcher.subscribe(
            classes=frozenset({"transition"}))
        self._regate_task = asyncio.create_task(
            self._regate_loop(), name="waymark4-collab-regate")

    async def stop_regate(self) -> None:
        if self._regate_task is not None:
            self._regate_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._regate_task
            self._regate_task = None

    async def _regate_loop(self) -> None:
        while True:
            t = await self._regate_sub.queue.get()
            try:
                if t.kind == "grant":
                    # a grant change can affect any room's token principals
                    for key in list(self.rooms):
                        await self._regate_room(key)
                else:
                    for key in [k for k in self.rooms
                                if k[0] == t.kind and k[1] == t.resource_id]:
                        await self._regate_room(key)
            except Exception:  # a regate failure must not kill the loop
                log.exception("collab regate failed for transition %s", t.id)

    async def _regate_room(self, key: RoomKey) -> None:
        from .render import probe_transition

        room = self.rooms.get(key)
        if room is None or not room.members:
            return
        rdef = self.engine.registry.get(key[0])
        defn = rdef.machine.actions.get(key[2]) if rdef else None
        if rdef is None or defn is None:
            return
        async with self.engine.storage.session() as s:
            instance = await self.engine.storage.load(s, key[0], key[1])
            if instance is None:
                await self._close_local(key, "gone")
                return
            for ws, principal in list(room.members.items()):
                lapsed = instance.state not in defn.from_
                if not lapsed and getattr(principal, "scope", None) is not None:
                    # re-resolve the grant: the handshake's copy may be stale
                    from .grants import action_mode

                    grant = await self.engine.storage.load(
                        s, "grant", principal.scope.id)
                    lapsed = (grant is None or action_mode(
                        grant, self.engine.invoker.clock(), rdef.kind,
                        defn.name) == "none")
                if not lapsed:
                    ctx = self.engine.invoker._ctx(principal, s, mode="probe")
                    status, _, _, _ = await probe_transition(defn, instance, ctx)
                    lapsed = status != "available"
                if lapsed:
                    room.members.pop(ws, None)
                    with contextlib.suppress(Exception):
                        await ws.send_text(json.dumps(
                            {"type": "closed", "reason": "affordance lapsed"}))
                        await ws.close(code=4403)
        if room.members:
            await self.broadcast(key, {
                "type": "presence", "event": "left",
                "participants": self.participants(key)})

    def room(self, key: RoomKey) -> Room:
        return self.rooms.setdefault(key, Room())

    def participants(self, key: RoomKey) -> list[dict[str, str]]:
        room = self.rooms.get(key)
        return [actor_of(p) for p in room.members.values()] if room else []

    async def _on_bus(self, message: dict[str, Any]) -> None:
        """A frame from another worker (or our own echo, skipped)."""
        if message.get("_origin") == self.engine.bus.origin:
            return
        frame = message.get("frame")
        if not isinstance(frame, dict):
            return
        key = (message.get("kind"), message.get("id"),
               message.get("action"), message.get("part") or "")
        if frame.get("type") == "closed":
            await self._close_local(key, frame.get("reason", "gone"))
        else:
            await self.broadcast(key, frame)

    async def broadcast(self, key: RoomKey, message: dict[str, Any], *,
                        exclude: Any = None) -> None:
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

    async def close(self, key: RoomKey, reason: str) -> None:
        """The draft is gone (consumed, discarded, resource vanished): tell
        every worker's room, then hang up locally."""
        rdef = self.engine.registry.get(key[0])
        defn = rdef.machine.actions.get(key[2]) if rdef else None
        if rdef is not None and defn is not None:
            await self.engine.draft_store.announce(
                rdef=rdef, defn=defn, resource_id=key[1], part_key=key[3],
                frame={"type": "closed", "reason": reason})
        await self._close_local(key, reason)

    async def _close_local(self, key: RoomKey, reason: str) -> None:
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
                 part_key: str, field: str, value: Any, base_rev: int,
                 principal: Any, room: Room) -> dict[str, Any] | None:
    """One field update through the one write path. Returns
    {saved|reject...} or None when the resource is gone."""
    async with room.lock:
        async with engine.storage.session() as s:
            instance = await engine.storage.load(s, rdef.kind, resource_id)
            if instance is None:
                return None
            audience = audience_of(defn, principal)
            row = await engine.draft_store.load(
                s, rdef, defn, resource_id, part_key, audience)
            current_rev = int((row or {}).get("revs", {}).get(field, 0))
            if base_rev < current_rev:
                # stale edit: reject with the field's truth — the client
                # applies it and re-edits; nothing is silently clobbered
                return {"type": "reject", "field": field, "rev": current_rev,
                        "value": (row or {}).get("values", {}).get(field)}
            saved = await engine.draft_store.save_fields(
                s, rdef=rdef, defn=defn, resource_id=resource_id,
                part_key=part_key, audience=audience, fields={field: value},
                base_version=instance.version, actor=principal,
                at=engine.invoker.clock())
    return {"type": "saved", "field": field,
            "rev": saved["revs"][field], "saved_at": saved["saved_at"].isoformat(),
            "_value": value}


async def serve(engine: Any, ws: Any, rdef: Any, defn: Any,
                resource_id: str, part_key: str, principal: Any) -> None:
    """Drive one participant's connection for the lifetime of the socket."""
    from starlette.websockets import WebSocketDisconnect

    from .render import probe_transition

    rooms: CollabRooms = engine.collab
    key: RoomKey = (rdef.kind, resource_id, defn.name, part_key)
    await ws.accept()
    # affordance gate (design §11): the channel belongs to principals the
    # action currently renders for — visibility alone is not enough
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, rdef.kind, resource_id)
        if instance is None:
            await ws.close(code=4404)
            return
        ctx = engine.invoker._ctx(principal, s, mode="probe")
        if instance.state not in defn.from_:
            await ws.close(code=4403)
            return
        status, _, _, _ = await probe_transition(defn, instance, ctx)
        if status != "available":
            await ws.close(code=4403)
            return
        row = await engine.draft_store.load(
            s, rdef, defn, resource_id, part_key, audience_of(defn, principal))
    room = rooms.room(key)
    room.members[ws] = principal
    await ws.send_text(json.dumps({
        "type": "state",
        "values": (row or {}).get("values", {}),
        "revs": {k: int(v) for k, v in (row or {}).get("revs", {}).items()},
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
                    or not isinstance(msg.get("field"), str):
                await ws.send_text(json.dumps({"type": "error", "errors": {
                    "_root": ["expected {\"type\": \"update\", \"field\", "
                              "\"value\", \"base_rev\"}"]}}))
                continue
            field = msg["field"]
            if field not in known:
                # same contract as a draft PUT: fields must be a subset of
                # the action's schema; values may be invalid mid-edit
                await ws.send_text(json.dumps({"type": "error", "errors": {
                    field: ["unknown field"]}}))
                continue
            base_rev = msg.get("base_rev")
            if not isinstance(base_rev, int):
                base_rev = 0
            outcome = await _drain(engine, rdef, defn, resource_id, part_key,
                                   field, msg.get("value"), base_rev,
                                   principal, room)
            if outcome is None:
                await rooms.close(key, "gone")
                return
            if outcome["type"] == "reject":
                await ws.send_text(json.dumps(outcome, default=str))
                continue
            value = outcome.pop("_value")
            await ws.send_text(json.dumps(outcome, default=str))
            relay = {"type": "update", "field": field, "value": value,
                     "rev": outcome["rev"], "actor": actor_of(principal),
                     "saved_at": outcome["saved_at"]}
            await rooms.broadcast(key, relay, exclude=ws)
            # cross-worker liveness rides the bus; our own echo is skipped
            await engine.draft_store.announce(
                rdef=rdef, defn=defn, resource_id=resource_id,
                part_key=part_key, frame=relay)
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
