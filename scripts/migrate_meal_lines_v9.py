"""One-time migration: embedded meal ``data.ingredients`` → meal_line rows.

Runs AFTER deploying the code that promotes lines to a resource (the old
field is gone from the model, so its JSONB residue is invisible to the
API — this script reads it straight from the database and re-creates each
entry through the engine's own create surface, which prices blanks and
maintains labels). Deterministic idempotency keys make re-runs safe;
the residue key is stripped only after its lines all land.

    MEALPLAN_DSN=postgresql://user:pw@host:port/db \
    MEALPLAN_BASE=https://meals.kopsa.info \
    uv run python scripts/migrate_meal_lines_v9.py
"""
from __future__ import annotations

import asyncio
import json
import os

import asyncpg
import httpx

PRINCIPAL = {"X-Principal-Id": "meal-line-migrate",
             "X-Principal-Display": "meal_line migration"}


async def main() -> None:
    dsn = os.environ["MEALPLAN_DSN"].replace("postgresql+asyncpg://",
                                             "postgresql://")
    base = os.environ["MEALPLAN_BASE"].rstrip("/")
    conn = await asyncpg.connect(dsn)
    client = httpx.AsyncClient(base_url=base, headers=PRINCIPAL, timeout=30)
    made = skipped = 0
    try:
        rows = await conn.fetch(
            "SELECT id, data->'ingredients' AS lines FROM meals "
            "WHERE jsonb_array_length(coalesce(data->'ingredients', "
            "'[]'::jsonb)) > 0")
        for row in rows:
            lines = json.loads(row["lines"])
            for line in lines:
                body = {"meal_id": row["id"],
                        "ingredient_id": line["ingredient_id"],
                        "grams": line["grams"]}
                if line.get("est_cost_cents") is not None:
                    body["est_cost_cents"] = line["est_cost_cents"]
                r = await client.post(
                    "/api/meal_lines", json=body,
                    headers={"Idempotency-Key":
                             f"mline-{row['id']}-{line['ingredient_id']}"})
                if r.status_code == 201:
                    made += 1
                elif r.status_code == 200:  # idempotent replay
                    skipped += 1
                else:
                    raise SystemExit(f"meal {row['id']} line "
                                     f"{line['ingredient_id']}: "
                                     f"{r.status_code} {r.text[:200]}")
            await conn.execute(
                "UPDATE meals SET data = data - 'ingredients' WHERE id = $1",
                row["id"])
            print(f"  meal {row['id']}: {len(lines)} line(s) migrated, "
                  "residue stripped")
    finally:
        await client.aclose()
        await conn.close()
    print(f"done: {made} created, {skipped} replayed")


if __name__ == "__main__":
    asyncio.run(main())
