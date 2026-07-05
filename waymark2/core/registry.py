"""The registry: one ResourceDef per resource, schemas pre-generated and cached."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import schemas as schemagen
from .actions import ActionDef
from .machine import StateMachine
from .resource import Resource


def _relations(defn: ActionDef) -> list[tuple[str, str, str]]:
    out = []
    for guard in defn.guards:
        for g in guard.iter_leaves():
            if g.relates is not None:
                out.append(g.relates)
    return out


@dataclass
class ResourceDef:
    cls: type[Resource]
    kind: str
    plural: str
    machine: StateMachine
    data_schema: dict[str, Any]
    data_schema_bytes: bytes
    action_schemas: dict[str, tuple[dict[str, Any], bytes]]
    query_schema: dict[str, Any]
    query_schema_bytes: bytes
    row_model: Any = None  # set by storage
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def summary_template(self) -> str:
        return self.cls.summary

    def action(self, name: str) -> ActionDef | None:
        return self.machine.actions.get(name)


class Registry:
    def __init__(self) -> None:
        self._by_kind: dict[str, ResourceDef] = {}
        self._by_plural: dict[str, ResourceDef] = {}
        self._schemas: dict[str, tuple[dict[str, Any], bytes]] = {}

    def register(self, cls: type[Resource]) -> ResourceDef:
        if cls.kind in self._by_kind:
            existing = self._by_kind[cls.kind]
            if existing.cls is cls:
                return existing
            raise ValueError(f"kind {cls.kind!r} registered twice "
                             f"({existing.cls.__qualname__} and {cls.__qualname__})")
        data_dict, data_bytes = schemagen.data_schema(cls.Data)
        action_schemas = {
            name: schemagen.input_schema(defn.input, dict(defn.field_display),
                                         relations=_relations(defn))
            for name, defn in cls.__waymark_machine__.actions.items()
            if defn.input is not None
        }
        query_dict, query_bytes = schemagen.query_schema(cls)
        create_model = getattr(cls, "Create", None) or cls.Data
        create_schema, _ = schemagen.input_schema(create_model)
        rdef = ResourceDef(
            cls=cls, kind=cls.kind, plural=cls.plural,
            machine=cls.__waymark_machine__,
            data_schema=data_dict, data_schema_bytes=data_bytes,
            action_schemas=action_schemas,
            query_schema=query_dict, query_schema_bytes=query_bytes,
        )
        rdef.extra["create_model"] = create_model
        rdef.extra["create_schema"] = create_schema
        self._by_kind[cls.kind] = rdef
        self._by_plural[cls.plural] = rdef
        # published schema names: the kind for Data, model names for inputs
        self._schemas[cls.kind] = (data_dict, data_bytes)
        for name, defn in cls.__waymark_machine__.actions.items():
            if defn.input is not None:
                self._schemas[defn.input.__name__] = action_schemas[name]
        return rdef

    def __getitem__(self, kind: str) -> ResourceDef:
        return self._by_kind[kind]

    def __contains__(self, kind: str) -> bool:
        return kind in self._by_kind

    def get(self, kind: str) -> ResourceDef | None:
        return self._by_kind.get(kind)

    def by_plural(self, plural: str) -> ResourceDef | None:
        return self._by_plural.get(plural)

    def kinds(self) -> list[str]:
        return list(self._by_kind)

    def defs(self) -> list[ResourceDef]:
        return list(self._by_kind.values())

    def schema(self, name: str) -> tuple[dict[str, Any], bytes] | None:
        return self._schemas.get(name)
