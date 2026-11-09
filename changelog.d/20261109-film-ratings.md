### Added — IMDb and Rotten Tomatoes ratings

Film detail shows the IMDb score and the Rotten Tomatoes percentage under the meta line, each linking to the film's page
in a new tab, and the Library can be sorted by either (highest first, unrated films last). Ratings come from OMDb
(`OMDB_API_KEY`; blank switches them off), keyed by the IMDb id TMDB gives us (`V7__film_ratings.sql`; films already
in the library get theirs from TMDB's `external_ids`). A background refresh runs after each scan and Plex import and
daily at 05:30, refetches ratings older than 30 days, and stays under a daily request budget (900, counted per UTC day).
Admin's Library scan card shows when ratings were last refreshed and has a Refresh ratings button.
