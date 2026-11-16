-- Where the last cue of a stored OpenSubtitles file ends, in milliseconds, kept when the file is fetched so that
-- judging whether a full English reference is a whole film (phase 3c-1, forced-timing-reference-plan.md) never parses
-- the cue text. Expand-only: a nullable column. A row stored before this has none (null); a reader works the figure out
-- from the cue text and writes nothing back. cue_count (V16) is the other figure the judgement uses.
ALTER TABLE opensubtitles_reference ADD COLUMN last_cue_end_ms BIGINT;
