### Added — forced subtitles with no English track in the film are fitted to a full English file and the fit is stored

For a film whose forced subtitles have no English track inside the film to be timed against, the timing check now fits
each forced file to the full English OpenSubtitles files already stored for the film (usable ones only, never the
file's own) and keeps the best fit: an aligned one first, then the larger share of lines matched, then the smaller
typical error, then the lower file id. The result is stored with the file it was made against, and Admin's subtitle list
now carries `alignment.source` (`EMBEDDED` or `OPENSUBTITLES`) and `alignment.referenceRelease`.

The fit is shown but never counts as proof: it does not make a file trusted, does not change which subtitle plays, does
not set an offset, and a file of 300 lines or more (or with more than half the full file's lines) is flagged as looking
like full subtitles. A new stored full file makes the fit be made again. The 1 second limit for an aligned fit is
provisional until the measurement lines from the last release are read.
