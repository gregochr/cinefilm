### Added — forced subtitles with no English track in the film are measured against full English files

After the checks of the owner's forced subtitle files, each check run now fetches a full English file from OpenSubtitles
for the films the search just found (see the entry on the search): at most 2 files a run and 2 a day (counted in memory,
so a restart may allow 2 more), at most 3 stored per film, within the comparison share of the daily download budget. It
tries the best candidate for the edition's cut first, a hearing-impaired file only when no other is left, and leaves out
a file whose frame rate is not about 23.976 (within 0.01). It moves on to the next candidate when a file is under 300
lines, ends more than 60 seconds after the runtime (a longer cut) or more than 10 minutes before the detected credits
start, matches under 30% of the forced file's lines, or fits best more than 30 seconds out (another cut).

Every forced file (the owner's, a download, or a pick) is then measured against every usable stored file, and one log
line says the verdict, offset, share and residual, the full file's release, frame rate, hearing-impaired flag and last
cue against the runtime and credits. It stores no fit and changes nothing in playback, trust, offsets or Admin yet: the
lines are what the next phase is decided from.
