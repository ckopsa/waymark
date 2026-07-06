"""Waymark 3.0: the affordance-oriented hypermedia framework, held to two
laws — nothing about a resource is inferred that the definition could have
declared (v2's), and nothing about a request's handling is woven that a
declaration could have staged: every concern is a declaration, a resource,
or an event class (design: docs/waymark3-design.md)."""

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
    OneOf,
    Relation,
    PartScope,
    Principal,
    Query,
    Observed,
    Ref,
    RefField,
    Registry,
    Resource,
    ResourceDef,
    Safety,
    UsabilityWarning,
    Vocab,
    VocabField,
    action,
    demand_class,
    emits,
    filterable,
    guard,
    link,
    profile,
    sortable,
)

FORMAT_VERSION = "3"
MEDIA_TYPE = "application/waymark+json; v=3"

__all__ = [
    "Acknowledged", "ActionDef", "Allow", "Bulk", "Ctx", "DefinitionError",
    "Deny", "DraftPolicy", "Edit", "Effect", "Guard", "OneOf", "PartScope",
    "Relation",
    "Principal", "Query", "Ref", "RefField", "Registry", "Resource",
    "ResourceDef", "Safety", "UsabilityWarning", "Vocab", "VocabField",
    "Observed", "action", "demand_class",
    "emits", "filterable", "guard", "link", "profile", "sortable",
    "FORMAT_VERSION", "MEDIA_TYPE",
]


def __getattr__(name: str):
    # Engine and storage pull in FastAPI/SQLAlchemy; import lazily so `import
    # waymark3` stays light for definition-only consumers (e.g. the CLI check).
    if name in ("Engine", "PostgresStorage"):
        from .server import engine as _engine
        return getattr(_engine, name)
    raise AttributeError(name)
