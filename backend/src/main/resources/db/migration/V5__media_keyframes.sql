-- Keyframe index per media file (expand-only): the HLS segment start times, so the server can serve playlists
-- before anything is encoded. Re-indexed when the file's size or modification time changes.
-- cuts holds comma-separated segment start times in milliseconds; VARCHAR (not TEXT) so Hibernate's schema
-- validation matches on PostgreSQL and on H2; 32000 characters is about 4,000 segments, over four hours of film.
CREATE TABLE media_keyframes (
    media_file_id BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    cuts VARCHAR(32000) NOT NULL,
    max_gap_ms BIGINT NOT NULL,
    copy_ok BOOLEAN NOT NULL,
    source_size BIGINT NOT NULL,
    source_mtime BIGINT NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fps DOUBLE PRECISION
);
