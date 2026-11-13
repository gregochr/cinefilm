-- The owner's choice on whether a film needs full English subtitles (expand-only, nullable). The need itself is worked
-- out when read from TMDB's languages and the audio of the owner's copy; only the owner's choice is stored.
-- full_need_owner is NEEDED or NOT_NEEDED; null leaves it to the automatic rule.
ALTER TABLE film ADD COLUMN full_need_owner VARCHAR(16);
ALTER TABLE film ADD CONSTRAINT film_full_need_owner_check CHECK (full_need_owner IN ('NEEDED', 'NOT_NEEDED'));
