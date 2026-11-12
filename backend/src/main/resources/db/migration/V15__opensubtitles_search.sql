-- OpenSubtitles evidence for the forced-subtitle need (expand-only, all nullable).
-- os_forced_count is how many English foreign-parts-only subtitles OpenSubtitles had for the film on the first page of
-- results, after dropping machine and AI translations and other films' results (null = never searched; 0 = searched,
-- none found). os_searched_at is when, and os_searched_imdb_id is the IMDb id the result belongs to: a count only
-- counts while it equals the film's current imdb_id. os_best is the top-ranked result as JSON
-- ({id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired}); null when there are none. A change of the
-- film's IMDb id resets all four to null. Rows already in the library stay NULL until the search backfills them.
ALTER TABLE film ADD COLUMN os_forced_count INTEGER;
ALTER TABLE film ADD COLUMN os_searched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE film ADD COLUMN os_searched_imdb_id VARCHAR(16);
ALTER TABLE film ADD COLUMN os_best VARCHAR(2000);
