"""Migrations are the contract (design §8), not a promise.

The declared schema is an artifact with a diff:

- ``waymark3 migrate`` snapshots the registry's storage schema
  (``schema_snapshot()``) and emits the SQL revision for the delta since the
  last snapshot — new resource tables, newly promoted filterable/sortable
  columns (as generated columns + indexes), dropped columns/indexes.
- Revisions are plain ``NNNN_*.sql`` files under ``migrations/waymark3/``,
  applied in order and recorded in ``waymark3_schema_migrations`` —
  reviewable in a PR, runnable by anything that can execute SQL.
- The conformance suite includes the round-trip: empty database → all
  revisions → schema equals a fresh snapshot. The promise cannot silently
  rot because CI replays it.

``create_all`` remains for dev/test; production boots apply revisions.
Deltas the differ cannot express safely (a changed column type, a data
backfill) are emitted as loud ``-- REVIEW:`` comments — the file fails to
apply until a human finishes the sentence, which is the point.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateIndex, CreateTable

from .storage.postgres import TS_FUNCTION_DDL, PostgresStorage

SNAPSHOT = "snapshot.json"
MIGRATIONS_TABLE = "waymark3_schema_migrations"

_DIALECT = postgresql.dialect()


def _ddl(element: Any) -> str:
    return str(element.compile(dialect=_DIALECT)).strip() + ";"


def _table_ddl(storage: PostgresStorage, name: str) -> list[str]:
    table = storage.metadata.tables[name]
    out = [_ddl(CreateTable(table))]
    for ix in sorted(table.indexes, key=lambda i: i.name):
        out.append(_ddl(CreateIndex(ix)))
    return out


def _column_ddl(table: str, column: str, spec: dict[str, Any]) -> str:
    if "generated" in spec:
        return (f"ALTER TABLE {table} ADD COLUMN {column} {spec['type']} "
                f"GENERATED ALWAYS AS ({spec['generated']}) STORED;")
    stmt = f"ALTER TABLE {table} ADD COLUMN {column} {spec['type']}"
    if not spec.get("nullable", True):
        return (f"-- REVIEW: {table}.{column} is NOT NULL; supply a backfill "
                f"then uncomment.\n-- {stmt} NOT NULL;")
    return stmt + ";"


def diff_statements(storage: PostgresStorage,
                    old: dict[str, Any] | None) -> list[str]:
    """SQL statements taking a database at ``old`` to the current snapshot."""
    new = storage.schema_snapshot()
    stmts: list[str] = []
    if old is None:
        stmts.append(TS_FUNCTION_DDL.strip() + ";")
        for name in new["tables"]:
            stmts.extend(_table_ddl(storage, name))
        return stmts

    if old.get("function") != new.get("function"):
        stmts.append(TS_FUNCTION_DDL.strip() + ";")

    old_tables, new_tables = old["tables"], new["tables"]
    for name in new_tables:
        if name not in old_tables:
            stmts.extend(_table_ddl(storage, name))
            continue
        oldt, newt = old_tables[name], new_tables[name]
        for col, spec in newt["columns"].items():
            if col not in oldt["columns"]:
                stmts.append(_column_ddl(name, col, spec))
            elif oldt["columns"][col] != spec:
                stmts.append(
                    f"-- REVIEW: {name}.{col} changed "
                    f"{oldt['columns'][col]} → {spec}; write the ALTER "
                    "(and any backfill) by hand.")
        for col in oldt["columns"]:
            if col not in newt["columns"]:
                stmts.append(f"ALTER TABLE {name} DROP COLUMN {col};")
        old_ix = dict(oldt["indexes"])
        new_ix = dict(newt["indexes"])
        table = storage.metadata.tables[name]
        by_name = {ix.name: ix for ix in table.indexes}
        for ix_name, cols in new_ix.items():
            if ix_name not in old_ix and ix_name in by_name:
                stmts.append(_ddl(CreateIndex(by_name[ix_name])))
        for ix_name in old_ix:
            if ix_name not in new_ix:
                stmts.append(f"DROP INDEX IF EXISTS {ix_name};")
        # declared uniqueness (design E2): constraints round-trip too — a
        # constraint that exists only under create_all is a dev-only lie
        old_uq = dict(oldt.get("unique", {}))
        new_uq = dict(newt.get("unique", {}))
        for uq_name, cols in new_uq.items():
            if uq_name not in old_uq:
                stmts.append(f"ALTER TABLE {name} ADD CONSTRAINT {uq_name} "
                             f"UNIQUE ({', '.join(cols)});")
            elif old_uq[uq_name] != cols:
                stmts.append(
                    f"-- REVIEW: constraint {uq_name} changed "
                    f"{old_uq[uq_name]} → {cols}; existing rows may violate "
                    "the new shape — write the migration by hand.")
        for uq_name in old_uq:
            if uq_name not in new_uq:
                stmts.append(
                    f"ALTER TABLE {name} DROP CONSTRAINT IF EXISTS {uq_name};")
    for name in old_tables:
        if name not in new_tables:
            stmts.append(
                f"-- REVIEW: table {name} is no longer declared; drop it "
                f"deliberately.\n-- DROP TABLE {name};")
    return stmts


def emit(storage: PostgresStorage, directory: Path, *,
         label: str = "auto") -> Path | None:
    """Write the next revision (or nothing when the schema is unchanged) and
    refresh the snapshot. Returns the revision path."""
    directory.mkdir(parents=True, exist_ok=True)
    snap_path = directory / SNAPSHOT
    old = json.loads(snap_path.read_text()) if snap_path.exists() else None
    new = storage.schema_snapshot()
    if old == new:
        return None
    stmts = diff_statements(storage, old)
    existing = sorted(p for p in directory.glob("[0-9]*.sql"))
    number = (int(existing[-1].name.split("_", 1)[0]) + 1) if existing else 1
    safe = re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_") or "auto"
    path = directory / f"{number:04d}_{safe}.sql"
    header = ("-- waymark3 migration: generated from the declared registry.\n"
              "-- Review before applying; lines marked for review need a "
              "human.\n\n")
    path.write_text(header + "\n\n".join(stmts) + "\n")
    snap_path.write_text(json.dumps(new, indent=2, sort_keys=True) + "\n")
    return path


async def apply(dsn: str, directory: Path) -> list[str]:
    """Apply pending revisions in order, recording each in
    ``waymark3_schema_migrations``. Refuses files with unresolved REVIEW
    lines — an unfinished sentence must not half-apply."""
    from sqlalchemy import text
    from sqlalchemy.ext.asyncio import create_async_engine

    engine = create_async_engine(dsn)
    applied: list[str] = []
    try:
        async with engine.begin() as conn:
            await conn.execute(text(
                f"CREATE TABLE IF NOT EXISTS {MIGRATIONS_TABLE} "
                "(name text PRIMARY KEY, applied_at timestamptz NOT NULL "
                "DEFAULT now())"))
            done = {r[0] for r in (await conn.execute(
                text(f"SELECT name FROM {MIGRATIONS_TABLE}"))).all()}
        for path in sorted(directory.glob("[0-9]*.sql")):
            if path.name in done:
                continue
            sql = path.read_text()
            if re.search(r"^-- REVIEW:", sql, flags=re.M):
                raise RuntimeError(
                    f"{path.name} contains unresolved -- REVIEW: lines; "
                    "finish them before applying")
            async with engine.begin() as conn:
                for stmt in _split_sql(sql):
                    await conn.execute(text(stmt))
                await conn.execute(
                    text(f"INSERT INTO {MIGRATIONS_TABLE} (name) VALUES (:n)"),
                    {"n": path.name})
            applied.append(path.name)
    finally:
        await engine.dispose()
    return applied


def _split_sql(sql: str) -> list[str]:
    """Split on statement-terminating semicolons at line ends. Function
    bodies here are single-expression ``RETURN``s, so this stays honest."""
    out, buf = [], []
    for line in sql.splitlines():
        if line.startswith("--"):
            continue
        buf.append(line)
        if line.rstrip().endswith(";"):
            stmt = "\n".join(buf).strip().rstrip(";").strip()
            if stmt:
                out.append(stmt)
            buf = []
    tail = "\n".join(buf).strip().rstrip(";").strip()
    if tail:
        out.append(tail)
    return out
