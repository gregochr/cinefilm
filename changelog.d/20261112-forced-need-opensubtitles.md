### Added — Search OpenSubtitles for forced English subtitles

CineFilm now asks OpenSubtitles which films have English "foreign parts only" subtitles, for every film with an IMDb id:
after each scan and Plex import, at start-up and daily at 06:00. Machine and AI translations and other films' results
are ignored, and a film with none is searched again after 30 days. A film with such subtitles is now marked as needing
forced subtitles (reason "OpenSubtitles") unless you chose, or a subtitle file is already attached. The search needs an
`OPENSUBTITLES_API_KEY` in `.env` (off while blank), only searches (no download, no login), and the key is never logged.
Admin's scan status also reports when the search last ran and whether it is running.
