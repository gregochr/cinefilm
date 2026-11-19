### Added — TMDB keywords stored per film

Each film now keeps its TMDB keywords (lower case, for example `christmas`), in a new `film_keyword` table with a `keywords_fetched_at` stamp on the film. A background backfill, run at start-up and after every scan and Plex import, fetches them for films that have none yet and retries after a TMDB failure; a rematch forgets the old film's keywords. Nothing reads them yet: this is the data for the holiday-themed weeks.
