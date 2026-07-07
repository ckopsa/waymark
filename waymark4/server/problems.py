"""Errors are hypermedia (§7.2): RFC 9457 problem+json extended with Waymark
affordances. Every error answers "what would a competent person do next".
"""
from __future__ import annotations

from typing import Any

PROBLEM_BASE = "https://waymark.dev/problems"
PROBLEM_MEDIA_TYPE = "application/problem+json"


class Problem(Exception):
    slug = "error"
    title = "Error"
    status = 500

    def __init__(self, detail: str, *, title: str | None = None, **extras: Any):
        super().__init__(detail)
        self.detail = detail
        if title is not None:
            self.title = title
        self.extras = {k: v for k, v in extras.items() if v is not None}

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": f"{PROBLEM_BASE}/{self.slug}",
            "title": self.title,
            "status": self.status,
            "detail": self.detail,
            **self.extras,
        }


class NotFound(Problem):
    slug = "not-found"
    title = "Not found"
    status = 404


class SchemaInvalid(Problem):
    """422: input failed schema validation; ``errors`` keyed by declared field."""

    slug = "schema-invalid"
    title = "Invalid input"
    status = 422


class GuardRefused(Problem):
    """409: a guard refused; ``detail`` is exactly the string that would have
    appeared in ``unavailable.reason``."""

    slug = "guard-failed"
    title = "Action not currently available"
    status = 409


class WrongState(GuardRefused):
    """409: the action exists on the machine but not from the current state."""

    slug = "wrong-state"


class WarningRefused(GuardRefused):
    """409: advisory guards denied and were not acknowledged (design E1).
    The problem IS the override affordance: ``warnings`` carries each
    guard's name and reason; ``acknowledge`` says how to proceed."""

    slug = "warning-required"
    title = "Acknowledgment required"


class Conflict(Problem):
    """409: a declared uniqueness group is already taken (design E2);
    ``existing`` links the resource holding it — the refusal carries the
    pointer, not just the no."""

    slug = "already-exists"
    title = "Already exists"
    status = 409


class VersionConflict(Problem):
    slug = "version-conflict"
    title = "Version conflict"
    status = 412


class IdempotencyKeyRequired(Problem):
    slug = "idempotency-key-required"
    title = "Idempotency-Key required"
    status = 428


class IdempotencyKeyReuse(Problem):
    slug = "idempotency-key-reuse"
    title = "Idempotency-Key reused with a different request"
    status = 409


class Forbidden(Problem):
    """403 — only when concealment is not required (§7.2)."""

    slug = "forbidden"
    title = "Forbidden"
    status = 403


class EffectFailed(Problem):
    """502: a declared external effect failed mid-compound (design §6);
    the act's resource writes rolled back and effects already executed
    were compensated in reverse order, each attempt audited."""

    slug = "effect-failed"
    title = "External effect failed"
    status = 502
