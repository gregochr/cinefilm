-- IMDb and Rotten Tomatoes ratings (expand-only, all nullable): the film's IMDb id (from TMDB), the IMDb score and
-- vote count, the Rotten Tomatoes percentage and page, and when OMDb was last asked (null = never; an unrated film is
-- stamped too, so it is not asked again every day). Rows already in the library stay NULL until the ratings refresh
-- backfills their IMDb id from TMDB.
ALTER TABLE film ADD COLUMN imdb_id VARCHAR(16);
ALTER TABLE film ADD COLUMN imdb_rating NUMERIC(3, 1);
ALTER TABLE film ADD COLUMN imdb_votes INTEGER;
ALTER TABLE film ADD COLUMN rt_score INTEGER;
ALTER TABLE film ADD COLUMN rt_url VARCHAR(512);
ALTER TABLE film ADD COLUMN ratings_fetched_at TIMESTAMP WITH TIME ZONE;

-- The OMDb request budget spent per UTC day, so a restart does not hand out a fresh daily allowance.
CREATE TABLE ratings_budget (
    budget_day DATE    PRIMARY KEY,
    used       INTEGER NOT NULL
);
