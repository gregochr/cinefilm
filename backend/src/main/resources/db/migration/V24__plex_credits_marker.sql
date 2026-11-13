-- Credits detection, phase 2c (docs/engineering/credits-detection-plan.md; expand-only: one new table).
--
-- plex_credits_marker: where Plex says the credits start, copied once from Plex for each CineFilm media file that
-- pairs with a Plex item, so nothing needs Plex afterwards. start_ms is inside the file (Plex's marker, moved later
-- by how much longer this copy is than the shortest Plex measured). fingerprint is the file's size and modification
-- time when it was imported (as credits_detection): a row whose fingerprint no longer matches the file is ignored,
-- because the file changed after Plex measured it. Nothing reads these rows to change playback: they are stored to
-- be compared with the detection first.
CREATE TABLE plex_credits_marker (
    media_file_id BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    fingerprint VARCHAR(100) NOT NULL,
    start_ms BIGINT NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL
);
