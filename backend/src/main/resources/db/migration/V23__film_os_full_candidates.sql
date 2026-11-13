-- OpenSubtitles results for FULL English subtitles (phase 5d of the subtitle work; expand-only, all nullable).
--
-- film.os_full_candidates: the single-file full (not foreign-parts-only) English results a download may use, as a JSON
-- array in the search's ranked order (hearing-impaired files last), the same shape as os_candidates. '[]' = searched,
-- none usable; null = never searched for full subtitles. os_full_searched_imdb_id is the IMDb id that search was for
-- (a result counts only while it equals the film's current imdb_id) and os_full_searched_at is when. A change of the
-- film's IMDb id resets all three to null. Only films whose full-subtitle need is NEEDED are searched.
ALTER TABLE film ADD COLUMN os_full_candidates TEXT;
ALTER TABLE film ADD COLUMN os_full_searched_imdb_id VARCHAR(16);
ALTER TABLE film ADD COLUMN os_full_searched_at TIMESTAMP WITH TIME ZONE;
