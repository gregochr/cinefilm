-- Credits detection, phase 1b (docs/engineering/credits-detection-plan.md; expand-only: one new table).
--
-- credits_detection: what the background analysis found for the LAST original part of an edition, one row per media
-- file. offset_ms is where the credits start inside that file (null when none was found). fingerprint is the file's
-- size and modification time when it was analysed (as embedded_cue_starts), so a changed file is analysed again.
-- verdict FOUND / UNSURE / NOT_FOUND; reasons are plain sentences joined by newlines. Nothing reads these rows to change
-- playback yet: they are stored so they can be measured against the real library first.
CREATE TABLE credits_detection (
    media_file_id BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    fingerprint VARCHAR(100) NOT NULL,
    verdict VARCHAR(20) NOT NULL,
    offset_ms BIGINT,
    reasons VARCHAR(2000) NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT credits_detection_verdict_ck CHECK (verdict IN ('FOUND', 'UNSURE', 'NOT_FOUND'))
);
