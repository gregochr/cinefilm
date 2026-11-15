-- owner_chosen_at is when the owner picked this OpenSubtitles download in the Admin picker (null for every other row).
-- A chosen download is proven whatever a comparison says, plays before other downloads, and is never cleared by a
-- nudge, a reset, an undo or a change of the edition's parts (owner_verified_at is: it is a different statement).
ALTER TABLE subtitle_sidecar ADD COLUMN owner_chosen_at TIMESTAMP WITH TIME ZONE;
