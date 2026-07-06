"""Route classes as declarations (design §2).

v2's scar: ``build_router`` wove every cross-cutting concern by hand —
the "scoped agents can't read streams" gate copy-pasted three times,
observation emission and scope redaction re-stated per handler, draft
verbs registered twice each. 3.0 declares each route class once; handlers
name their class and the assembler applies its stages:

=========  =====================  ==========  =======================
class      gate                   emits       redacts (agent scope)
=========  =====================  ==========  =======================
read       —                      viewed      yes
lookup     —                      —           yes   (plumbing, §3)
stream     no scoped principals   —           n/a  (refused instead)
write      declared per-action    engaged*    yes  (via act's stages)
draft      draft scope gate       engaged     yes
=========  =====================  ==========  =======================

(*) the write pipeline's engagement events are emitted by its dry-run
stage; its scope gating is the grant's declared action mode.

A feature that cannot land as a stage, a resource, or an event class does
not belong in the router — that is the 3.0 admission test.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..core.types import Principal
from .grants import SCOPE_REASON
from .problems import Forbidden


@dataclass(frozen=True)
class RouteClass:
    name: str
    # streams carry every kind's summaries; scoped agents read documents
    # they were granted, not the whole workspace's pulse
    refuse_scoped: bool = False
    # observation-class event emitted after a successful response (§3);
    # None = never (lookup: plumbing is a route class, not a query flag)
    emits: str | None = None
    # post-render redaction for grant-scoped principals (until §1 makes
    # visibility a projection input, the redaction stage lives here)
    redacts: bool = False


ROUTE_CLASSES: dict[str, RouteClass] = {
    "read": RouteClass("read", emits="viewed", redacts=True),
    "lookup": RouteClass("lookup", redacts=True),
    "stream": RouteClass("stream", refuse_scoped=True),
    "draft": RouteClass("draft", emits="engaged", redacts=True),
}

# Draft verb aliases, emitted from this table instead of hand-registered
# route lines (design §2): one handler, its declared verbs.
DRAFT_VERBS: dict[str, tuple[tuple[str, str], ...]] = {
    "save": (("PUT", "/{plural}/{id}/-/{action}/draft"),
             ("POST", "/{plural}/{id}/-/{action}/draft")),
    "discard": (("DELETE", "/{plural}/{id}/-/{action}/draft"),
                ("POST", "/{plural}/{id}/-/{action}/draft/-/discard")),
}


def scope_of(principal: Principal) -> Any:
    return getattr(principal, "scope", None)


async def gate(route_class: str, engine: Any, request: Any) -> Principal:
    """The authenticate stage: resolve the principal and apply the route
    class's declared gate. One definition; every stream route consumes it
    — the v2 copy-paste is structurally gone."""
    principal = await engine.resolve_principal(request)
    spec = ROUTE_CLASSES[route_class]
    if spec.refuse_scoped and scope_of(principal) is not None:
        raise Forbidden(SCOPE_REASON)
    return principal
