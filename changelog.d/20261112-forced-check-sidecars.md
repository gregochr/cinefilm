### Added — check the forced subtitle files against OpenSubtitles

CineFilm now compares each forced `.srt` file it plays with OpenSubtitles' file for the same film (the best English
"foreign parts only" result that is one whole file, never part of a film): how many lines each has, and how many of
the file's lines land within 2 seconds of one of theirs after the best timing shift. Under 70% matching, under half
as many lines, or more than twice as many gives `CHECK` with the reason ("OpenSubtitles has 41 lines, this file has
12"); anything else `AGREES`. The best shift is shown as a hint ("OpenSubtitles' file is 1.2 s later") and never
applied. Needs `OPENSUBTITLES_USERNAME` and `OPENSUBTITLES_PASSWORD` besides the API key (downloads are off while
either is blank); at most 90 downloads a day, each file downloaded once and kept (`V16`). The check is stored per
edition against exactly what is served, so an attach, move, nudge or changed file makes it stale; it appears as
`check` on `GET /api/admin/subtitles`. Nothing about playback changes yet.
