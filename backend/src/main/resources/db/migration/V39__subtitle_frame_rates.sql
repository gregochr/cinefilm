-- The frame rate each OpenSubtitles file was timed for, and the columns the retime (docs/engineering/
-- subtitle-frame-rate-plan.md) will use. Expand-only: every column is nullable or has a default, and nothing reads or
-- writes them yet except timed_fps, which new downloads and references store.
--
-- timed_fps: the rate OpenSubtitles states for the file (null when it states none). Set on new rows; V40 fills the
--   rows stored before.
-- retimed_by: the factor k the stored cues were multiplied by (null = never retimed); retimed_for_fps: the film rate k
--   came from. original_cues_text: the cues before they were retimed (null until then).
-- owner_timed_fps: the rate the owner says the file was timed for; it wins over timed_fps.
-- subtitle_sidecar.retime: null = undecided; AUTO = retime it, OFF = the owner's hand-set timing is left alone,
--   OFF_FITS = its original cues already fit the film.
-- opensubtitles_reference.cues_version: raised on every change of cues_text, so a check that read older cues can drop
--   its save.
-- stale_since: when a verdict row was made stale because the cues it was made on changed (null = not stale).
ALTER TABLE subtitle_sidecar ADD COLUMN timed_fps DOUBLE PRECISION;
ALTER TABLE subtitle_sidecar ADD COLUMN retimed_by DOUBLE PRECISION;
ALTER TABLE subtitle_sidecar ADD COLUMN retimed_for_fps DOUBLE PRECISION;
ALTER TABLE subtitle_sidecar ADD COLUMN original_cues_text TEXT;
ALTER TABLE subtitle_sidecar ADD COLUMN owner_timed_fps DOUBLE PRECISION;
ALTER TABLE subtitle_sidecar ADD COLUMN retime VARCHAR(10);
ALTER TABLE subtitle_sidecar ADD CONSTRAINT subtitle_sidecar_retime_check
    CHECK (retime IN ('AUTO', 'OFF', 'OFF_FITS'));

ALTER TABLE opensubtitles_reference ADD COLUMN timed_fps DOUBLE PRECISION;
ALTER TABLE opensubtitles_reference ADD COLUMN retimed_by DOUBLE PRECISION;
ALTER TABLE opensubtitles_reference ADD COLUMN retimed_for_fps DOUBLE PRECISION;
ALTER TABLE opensubtitles_reference ADD COLUMN original_cues_text TEXT;
ALTER TABLE opensubtitles_reference ADD COLUMN owner_timed_fps DOUBLE PRECISION;
ALTER TABLE opensubtitles_reference ADD COLUMN cues_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE forced_subtitle_check ADD COLUMN stale_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE forced_download_check ADD COLUMN stale_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE forced_subtitle_alignment ADD COLUMN stale_since TIMESTAMP WITH TIME ZONE;
