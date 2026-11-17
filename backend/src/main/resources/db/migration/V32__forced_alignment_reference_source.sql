-- Fitting a forced subtitle file to a full English OpenSubtitles file when the film has no English track of its own
-- (phase 3c-2a, forced-timing-reference-plan.md; expand-only: two new columns on forced_subtitle_alignment).
--
-- reference_source says what the fit is against: EMBEDDED (the film's own English track, every row so far) or
-- OPENSUBTITLES (a stored full English file; reference_os_file_id is its OpenSubtitles file id, null for EMBEDDED).
-- A fit against OpenSubtitles never counts as proof and never sets an offset.
ALTER TABLE forced_subtitle_alignment ADD COLUMN reference_source VARCHAR(14) NOT NULL DEFAULT 'EMBEDDED';
ALTER TABLE forced_subtitle_alignment ADD CONSTRAINT forced_subtitle_alignment_reference_source_ck
    CHECK (reference_source IN ('EMBEDDED', 'OPENSUBTITLES'));
ALTER TABLE forced_subtitle_alignment ADD COLUMN reference_os_file_id BIGINT;
