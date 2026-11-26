### Added — after the subtitle retime job rewrites or restores subtitles, the touched films are checked again at once

Behind `cinefilm.subtitles.retime` (still off by default, so nothing changes yet). For each film whose downloaded
subtitles were just rewritten or restored, the timing fit is made again first and then the subtitle checks, stored
OpenSubtitles files first, so a download that failed is judged again in the same run. A busy library is tried again a
few times and then left for the next pass. The line `Subtitle retime (...) finished` is now logged after this
re-check, so it marks the end of a run, which the rollback in `docs/engineering/operations.md` waits for.
