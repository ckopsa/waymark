"""Waymark: an affordance-oriented hypermedia format and server framework."""

from .core import (
    ActionDef,
    Allow,
    Ctx,
    DefinitionError,
    Deny,
    UsabilityWarning,
    Effect,
    Guard,
    Principal,
    Registry,
    Resource,
    ResourceDef,
    Safety,
    action,
    emits,
    filterable,
    guard,
    link,
    profile,
    sortable,
)

FORMAT_VERSION = "1"
MEDIA_TYPE = "application/waymark+json; v=1"

__all__ = [
    "ActionDef", "Allow", "Ctx", "DefinitionError", "Deny", "Effect", "Guard",
    "Principal", "Registry", "Resource", "ResourceDef", "Safety", "UsabilityWarning", "action",
    "emits", "filterable", "guard", "link", "profile", "sortable",
    "FORMAT_VERSION", "MEDIA_TYPE",
]


def __getattr__(name: str):
    # Engine and storage pull in FastAPI/SQLAlchemy; import lazily so `import
    # waymark` stays light for definition-only consumers (e.g. the CLI check).
    if name in ("Engine", "PostgresStorage"):
        from .server import engine as _engine
        return getattr(_engine, name)
    raise AttributeError(name)
