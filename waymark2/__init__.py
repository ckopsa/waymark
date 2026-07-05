"""Waymark 2.0: the affordance-oriented hypermedia framework, rebuilt on one
law — nothing is inferred that the definition could have declared, and every
declaration is used at least twice (design: docs/waymark2-design.md)."""

from .core import (
    Acknowledged,
    ActionDef,
    Allow,
    Bulk,
    Ctx,
    DefinitionError,
    Deny,
    DraftPolicy,
    Edit,
    Effect,
    Guard,
    PartScope,
    Principal,
    Query,
    Ref,
    RefField,
    Registry,
    Resource,
    ResourceDef,
    Safety,
    UsabilityWarning,
    action,
    demand_class,
    emits,
    filterable,
    guard,
    link,
    profile,
    sortable,
)

FORMAT_VERSION = "2"
MEDIA_TYPE = "application/waymark+json; v=2"

__all__ = [
    "Acknowledged", "ActionDef", "Allow", "Bulk", "Ctx", "DefinitionError",
    "Deny", "DraftPolicy", "Edit", "Effect", "Guard", "PartScope",
    "Principal", "Query", "Ref", "RefField", "Registry", "Resource",
    "ResourceDef", "Safety", "UsabilityWarning", "action", "demand_class",
    "emits", "filterable", "guard", "link", "profile", "sortable",
    "FORMAT_VERSION", "MEDIA_TYPE",
]


def __getattr__(name: str):
    # Engine and storage pull in FastAPI/SQLAlchemy; import lazily so `import
    # waymark2` stays light for definition-only consumers (e.g. the CLI check).
    if name in ("Engine", "PostgresStorage"):
        from .server import engine as _engine
        return getattr(_engine, name)
    raise AttributeError(name)
