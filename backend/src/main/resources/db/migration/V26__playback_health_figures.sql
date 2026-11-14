-- Playback health, queryable figures (docs/engineering/admin-redesign-plan.md, phase 3a; expand-only: new columns and
-- an index). The report stays in summary as JSON text; these columns copy the few figures Admin filters and charts by
-- (HealthFigures writes them on every report, and V27 fills them for the rows stored before this migration).
--
-- watched_ms, stall_count, stall_ms: the report's watchedMs, stallCount, stallMs (0 until a report says otherwise).
-- startup_ms: the report's startupMs; null when the first frame never came.
-- has_problem: true when the report shows a stall, a reconnect, a fall back to direct play, a network error or a
--   timeout (stallCount, reconnectCount, fellBack, netErrors, timeouts). A step down to a lower rung is not one.
-- environment: the report's environment (home, train, away, unknown); null for a row whose report could not be read.
ALTER TABLE playback_health ADD COLUMN watched_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE playback_health ADD COLUMN stall_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE playback_health ADD COLUMN stall_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE playback_health ADD COLUMN startup_ms BIGINT;
ALTER TABLE playback_health ADD COLUMN has_problem BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE playback_health ADD COLUMN environment VARCHAR(16);
CREATE INDEX playback_health_started_at_idx ON playback_health (started_at);
