-- "Remove from Continue watching": a flag on the watch-progress record, expand-only. The resume position, watched
-- state and last-played time are never touched by it; the next progress report clears it again, so a film that is
-- played again reappears in the row. Existing rows stay visible (FALSE) with no hidden-at time.
ALTER TABLE playback_state ADD COLUMN hidden_from_continue_watching BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE playback_state ADD COLUMN hidden_at TIMESTAMP WITH TIME ZONE;
