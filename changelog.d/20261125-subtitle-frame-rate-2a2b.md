### Added — the other subtitle comparisons know how to compare a subtitle that has been retimed

The choice of a download to promote and the timing measurements against OpenSubtitles' files now follow the same rule
as the file check: a retimed subtitle is compared on the right timeline, or not at all when the frame rates are not
known well enough. The log-only fit of a full download to a second OpenSubtitles file passes over a retimed one until
the next phase. Nothing is retimed yet, so nothing visible changes: every comparison gives the same answer as before.
