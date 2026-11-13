-- Full English subtitle files beside a film (phase 5b of the subtitle work; expand-only: new columns with defaults).
--
-- subtitle_sidecar.kind is FORCED (every row so far: a forced-only English .srt, or a forced download) or FULL (a
-- full English .srt beside the film, X.en.srt). One table holds both, and every forced reader asks for FORCED
-- explicitly, so a FULL row is never played, checked, timed, counted or listed as forced.
--
-- forced_download_block and forced_check_candidate are per kind as well (the same OpenSubtitles file id can be a
-- candidate for both), so their uniqueness key includes kind. Existing rows are FORCED.
ALTER TABLE subtitle_sidecar ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'FORCED';
ALTER TABLE subtitle_sidecar ADD CONSTRAINT subtitle_sidecar_kind_ck CHECK (kind IN ('FORCED', 'FULL'));

ALTER TABLE forced_download_block ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'FORCED';
ALTER TABLE forced_download_block ADD CONSTRAINT forced_download_block_kind_ck CHECK (kind IN ('FORCED', 'FULL'));
ALTER TABLE forced_download_block DROP CONSTRAINT forced_download_block_uq;
ALTER TABLE forced_download_block ADD CONSTRAINT forced_download_block_uq
    UNIQUE (edition_id, imdb_id, os_file_id, kind);

ALTER TABLE forced_check_candidate ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'FORCED';
ALTER TABLE forced_check_candidate ADD CONSTRAINT forced_check_candidate_kind_ck CHECK (kind IN ('FORCED', 'FULL'));
ALTER TABLE forced_check_candidate DROP CONSTRAINT forced_check_candidate_uq;
ALTER TABLE forced_check_candidate ADD CONSTRAINT forced_check_candidate_uq
    UNIQUE (edition_id, imdb_id, os_file_id, kind);
