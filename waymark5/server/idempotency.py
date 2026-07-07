"""Idempotency keys (§7.3): stored (key → response) replayed byte-for-byte."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import delete, select

from .problems import IdempotencyKeyReuse

RETENTION = timedelta(hours=24)


def body_digest(body: Any) -> str:
    canonical = json.dumps(body or {}, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


@dataclass(frozen=True)
class StoredResponse:
    status: int
    body: bytes
    media_type: str


class IdempotencyStore:
    def __init__(self, storage: Any):
        self.table = storage.idempotency

    async def lookup(self, s: Any, key: str, *, action: str,
                     digest: str) -> StoredResponse | None:
        row = (await s.execute(
            select(self.table).where(self.table.c.key == key)
        )).mappings().first()
        if row is None:
            return None
        if row["body_digest"] != digest or row["action"] != action:
            raise IdempotencyKeyReuse(
                f"Idempotency-Key {key!r} was already used for a different request.")
        return StoredResponse(status=row["status"], body=bytes(row["response_body"]),
                              media_type=row["media_type"])

    async def store(self, s: Any, key: str, *, kind: str, action: str, digest: str,
                    status: int, body: bytes, media_type: str,
                    at: datetime) -> None:
        await s.execute(self.table.insert().values(
            key=key, kind=kind, action=action, body_digest=digest,
            status=status, response_body=body, media_type=media_type,
            created_at=at,
        ))

    async def purge(self, s: Any, *, now: datetime) -> int:
        result = await s.execute(
            delete(self.table).where(self.table.c.created_at < now - RETENTION))
        return result.rowcount
