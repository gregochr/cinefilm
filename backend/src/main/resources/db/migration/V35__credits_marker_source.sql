-- Where an edition's credits marker came from. Nullable: existing rows keep a null source and nothing reads it yet.
ALTER TABLE edition ADD COLUMN credits_marker_source VARCHAR(10);
ALTER TABLE edition ADD CONSTRAINT edition_credits_marker_source_check
    CHECK (credits_marker_source IN ('AUTO', 'PLEX', 'OWNER'));
