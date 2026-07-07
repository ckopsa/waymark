"""Waymark 3.0: the affordance-oriented hypermedia framework, held to two
laws — nothing about a resource is inferred that the definition could have
declared (v2's), and nothing about a request's handling is woven that a
declaration could have staged: every concern is a declaration, a resource,
or an event class (design: docs/waymark4-design.md)."""

from .core import (
    Acknowledged,
    Advance,
    Advances,
    Authored,
    Batch,
    Compound,
    Create,
    Creates,
    Delegated,
    Each,
    Op,
    ServiceEffect,
    ActionDef,
    Allow,
    Bulk,
    Clock,
    Count,
    Ctx,
    DefinitionError,
    Deny,
    Derived,
    DraftPolicy,
    Edit,
    Effect,
    Field,
    Guard,
    OneOf,
    Owns,
    Relation,
    Rollup,
    Seed,
    Sum,
    Tolerance,
    PartScope,
    Predecessor,
    Principal,
    Query,
    Observed,
    Ref,
    RefField,
    Registry,
    Resource,
    ResourceDef,
    Safety,
    Unless,
    UsabilityWarning,
    Vocab,
    VocabField,
    When,
    action,
    actor_of,
    demand_class,
    emits,
    filterable,
    four_eyes,
    guard,
    link,
    profile,
    require,
    rollup_is,
    sortable,
)

FORMAT_VERSION = "4"
MEDIA_TYPE = "application/waymark+json; v=4"

__all__ = [
    "Acknowledged", "ActionDef", "Allow", "Authored", "Batch", "Bulk",
    "Clock", "Count",
    "Ctx",
    "DefinitionError",
    "Deny", "Derived", "DraftPolicy", "Edit", "Effect", "Guard", "OneOf",
    "Owns",
    "PartScope", "Relation", "Rollup", "Seed", "Sum", "Tolerance",
    "Advance", "Advances", "Compound", "Create", "Creates", "Delegated",
    "Each", "Op", "ServiceEffect",
    "Predecessor", "Principal", "Query", "Ref", "RefField", "Registry", "Resource",
    "ResourceDef", "Safety", "UsabilityWarning", "Vocab", "VocabField",
    "Field", "Unless", "When",
    "Observed", "action", "actor_of", "demand_class",
    "emits", "filterable", "four_eyes", "guard", "link", "profile",
    "require", "rollup_is", "sortable",
    "FORMAT_VERSION", "MEDIA_TYPE",
]


def __getattr__(name: str):
    # Engine and storage pull in FastAPI/SQLAlchemy; import lazily so `import
    # waymark4` stays light for definition-only consumers (e.g. the CLI check).
    if name in ("Engine", "PostgresStorage"):
        from .server import engine as _engine
        return getattr(_engine, name)
    raise AttributeError(name)
