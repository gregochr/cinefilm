-- Playback across the parts of a split film: a part starts where the previous part's VIDEO stream ends, not where its
-- container ends (an AAC track can outlast the video by a second or more). Expand-only: a nullable column. NULL means
-- the file has not been probed since this column existed (the library scan probes it once more and stores a value);
-- 0 means it was probed and ffprobe gave no video stream duration, so the container duration is used instead.
ALTER TABLE media_file ADD COLUMN video_duration_ms BIGINT;
