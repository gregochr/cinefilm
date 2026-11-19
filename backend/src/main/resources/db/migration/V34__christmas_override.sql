-- The owner's say over which films count as Christmas films, by TMDB id. One row per film, so a film cannot be both
-- included (shown although TMDB's christmas keyword misses it) and excluded (hidden although the keyword catches it).
-- No seed: the table starts empty and the keyword alone decides.
CREATE TABLE christmas_override (
    tmdb_id BIGINT PRIMARY KEY,
    choice VARCHAR(8) NOT NULL CHECK (choice IN ('INCLUDE', 'EXCLUDE')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
