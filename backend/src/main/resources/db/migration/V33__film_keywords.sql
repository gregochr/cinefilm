-- TMDB's keywords per film (for themed weeks such as Christmas). film_keyword mirrors film_genre: one row per keyword,
-- stored lower case and trimmed. keywords_fetched_at is when TMDB was last asked (null = not yet; a film TMDB no
-- longer has is stamped with no keywords, so it is not asked again). Rows already in the library stay NULL until the
-- keywords backfill fills them from TMDB.
ALTER TABLE film ADD COLUMN keywords_fetched_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE film_keyword (
    film_id BIGINT NOT NULL REFERENCES film (id),
    keyword VARCHAR(100) NOT NULL,
    PRIMARY KEY (film_id, keyword)
);
