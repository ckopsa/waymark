-- waymark2 migration: generated from the declared registry.
-- Review before applying; lines marked for review need a human.

-- Meals are now tagged with every theme night they can serve: the promoted
-- `theme` text column becomes a `themes` JSONB array with a GIN index
-- (Eq filters are @> containment, In filters are ?| any-of).

-- Backfill first: fold each single-theme row into a one-tag list so the
-- generated column computes from `themes` for every existing meal.
UPDATE meals
SET data = (data - 'theme') || jsonb_build_object('themes', jsonb_build_array(data->'theme'))
WHERE data ? 'theme' AND NOT data ? 'themes';

ALTER TABLE meals ADD COLUMN themes JSONB GENERATED ALWAYS AS ((data->'themes')) STORED;

ALTER TABLE meals DROP COLUMN theme;

CREATE INDEX ix_meals_themes ON meals USING gin (themes);

DROP INDEX IF EXISTS ix_meals_theme;
