"""Visibility as a typed capability (design §1, §9).

v2 enforced least privilege with ``apply_scope`` — a ~500-line post-hoc
envelope rewriter bolted *after* render, driven by ``getattr(principal,
"scope")`` sniffing, a ``_collection`` string-suffix dispatch, and a
``parts`` re-admit TODO. 3.0 applies the guard unification to grants:
**one visibility declaration, two consumers.** The projector consults the
principal's ``Visibility`` while *building* the envelope — a field the
principal cannot see is never rendered, so it can't need popping and
``parts`` inherit for free — and the write path consults the same object
to enforce.

Three implementations:

- :class:`FullVisibility` (here) — system actors, and every human when
  the engine runs ``member_visibility="full"``.
- ``GrantVisibility`` (``server/grants.py``) — token principals; what the
  grant says, optionally ceilinged by its approver's effective view
  (delegation is attenuation, design §9).
- ``MemberVisibility`` (``server/grants.py``) — members under
  ``member_visibility="granted"``: full over what they own, the union of
  their member- and role-held grants otherwise.

``owner`` threads through every method because ownership is a visibility
*rule* ("full control over what you created"), evaluated at projection —
and pushed into collection SQL via ``restrict()`` rather than re-derived
by post-filtering envelopes.
"""
from __future__ import annotations

from typing import Any, Literal, Protocol

FieldMode = Literal["clear", "hashed", "hidden"]
ActionMode = Literal["open", "approval", "none"]
ArgMode = Literal["edit", "approval", "none"]

# rank orders for intersection (attenuation) and union (grant stacking)
FIELD_RANK = {"hidden": 0, "hashed": 1, "clear": 2}
ACTION_RANK = {"none": 0, "negotiation": 0, "approval": 1, "open": 2}
ARG_RANK = {"none": 0, "approval": 1, "edit": 2}


class Visibility(Protocol):
    """What one principal may see and do, per kind — and, where grants
    select instances or ownership applies, per resource id/owner."""

    full: bool

    def field(self, kind: str, name: str, id: str | None = None,
              owner: str | None = None) -> FieldMode: ...

    def action(self, kind: str, name: str, id: str | None = None,
               data: dict[str, Any] | None = None,
               owner: str | None = None) -> ActionMode: ...

    def arg(self, kind: str, action: str, name: str) -> ArgMode: ...

    def summary_clear(self, kind: str, id: str | None = None,
                      owner: str | None = None) -> bool: ...

    def restrict(self, kind: str) -> tuple[str, set[str]] | None:
        """The collection pushdown (design §9): None = unrestricted;
        ``(owner_id, granted_ids)`` = ``WHERE owner = :me OR id IN …``."""
        ...


class FullVisibility:
    """The unscoped view: everything clear, everything open. The
    projector's fast path — and the *only* representation of "no scope";
    there is no None to sniff for."""

    full = True

    def field(self, kind: str, name: str, id: str | None = None,
              owner: str | None = None) -> FieldMode:
        return "clear"

    def action(self, kind: str, name: str, id: str | None = None,
               data: dict[str, Any] | None = None,
               owner: str | None = None) -> ActionMode:
        return "open"

    def arg(self, kind: str, action: str, name: str) -> ArgMode:
        return "edit"

    def summary_clear(self, kind: str, id: str | None = None,
                      owner: str | None = None) -> bool:
        return True

    def restrict(self, kind: str) -> tuple[str, set[str]] | None:
        return None


FULL = FullVisibility()
