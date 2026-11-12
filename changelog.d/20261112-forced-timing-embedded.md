### Added — Forced subtitle timing is checked against the film's own English track

In the background, CineFilm now checks the timing of each forced `.srt` file it plays against the full English subtitle
track inside the film's own file (picture tracks such as PGS and DVD subtitles included: only their cue times are read,
no text recognition). The forced lines are a subset of the full track in time, so this is a far stronger reference than
OpenSubtitles. A file that lines up is `ALIGNED` and its timing offset is set automatically when it is 0.1 s or more
out, unless the owner has verified the file or set its offset (a reset to 0 counts): the owner's word is never
overridden. Only reliable subtitle codecs are used as the reference (text, mov_text, PGS, DVD; not webvtt). A file that does not line up is `CHECK` with plain reasons; too few lines, or a film with no full English
track, are left Unchecked. Each film file is read once (a few minutes over SMB, niced, only while nothing is
transcoding), after each scan, at start-up and daily at 07:00; switch off with `cinefilm.subtitles.timing-check.enabled`.
The Admin subtitle list carries `alignment` and `offsetSource` (V17, expand-only); the Admin display is the next change.
