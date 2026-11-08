-- Expand-only. A film whose year is unknown stores NULL (V2 stored 0, which would show as "0").
ALTER TABLE film ALTER COLUMN release_year DROP NOT NULL;
UPDATE film SET release_year = NULL WHERE release_year = 0;

-- A collection whose parts could not be fetched from TMDB is retried on later scans.
ALTER TABLE film_collection ADD COLUMN parts_complete BOOLEAN NOT NULL DEFAULT TRUE;
