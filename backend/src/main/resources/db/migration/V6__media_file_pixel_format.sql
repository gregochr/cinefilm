-- The video stream's pixel format (ffprobe pix_fmt, for example yuv420p), expand-only and nullable: rows scanned
-- before this migration stay NULL (unknown) until the file is probed again. Adaptive streaming copies the original
-- only for 8-bit 4:2:0 H.264, so an unknown format is treated as "cannot copy".
ALTER TABLE media_file ADD COLUMN pixel_format VARCHAR(20);
