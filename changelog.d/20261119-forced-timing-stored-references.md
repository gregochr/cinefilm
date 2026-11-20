### Fixed — the forced timing check now uses full English files already stored, and measures while OpenSubtitles' limit holds

The forced timing check never measured or fitted anything in production: the day's OpenSubtitles downloads were always
spent, and the check stopped at that limit before measuring even the files already stored. It now measures stored
files with fetching off when the limit holds. It also counts any stored full English file of a film (300 lines or more,
under the film's current IMDb id) as a reference, not only the files among the film's current OpenSubtitles
candidates, so films whose full files came from the older forced comparison downloads, or that were never searched for
full subtitles, are measured and fitted. The reference is still never the sidecar's own file, and the fit is still
display only. Fetching still tries the candidates only.
