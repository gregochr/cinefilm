### Added — Which films need forced subtitles (backend)

CineFilm now keeps TMDB's original and spoken languages for every film (new `film` columns, `V14`), filled in by every
scan, Plex import and Fix match, and by a background backfill for the films already in the library. A new rule works
out whether a film needs forced subtitles from the owner's choice, an attached forced `.srt`, an embedded forced
English track and TMDB's languages. `GET /api/admin/forced-subtitles/films` lists every film with its answer, and
`PUT /api/admin/films/{id}/forced-need` records the owner's choice. There is no screen for it yet.
