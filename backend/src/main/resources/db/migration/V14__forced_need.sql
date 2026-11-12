-- Which films need forced subtitles (expand-only, all nullable). The need itself is worked out when read; only its
-- inputs are stored.
-- original_language is TMDB's ISO 639-1 code for the film's original language. spoken_languages is TMDB's spoken
-- languages as lower-case codes, comma-separated, in TMDB's order (an empty string means TMDB lists none; null means
-- not known). languages_fetched_at is when TMDB was last asked (null = not yet; a film TMDB no longer has is stamped
-- with null languages, so it is not asked again). Rows already in the library stay NULL until the languages refresh
-- backfills them from TMDB.
-- forced_need_owner is the owner's choice, NEEDED or NOT_NEEDED; null leaves it to the automatic rule.
ALTER TABLE film ADD COLUMN original_language VARCHAR(8);
ALTER TABLE film ADD COLUMN spoken_languages VARCHAR(200);
ALTER TABLE film ADD COLUMN languages_fetched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE film ADD COLUMN forced_need_owner VARCHAR(16);
ALTER TABLE film ADD CONSTRAINT film_forced_need_owner_check CHECK (forced_need_owner IN ('NEEDED', 'NOT_NEEDED'));
