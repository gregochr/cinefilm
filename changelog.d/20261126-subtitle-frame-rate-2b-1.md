### Added — the subtitle retime job also puts stored OpenSubtitles references on the film's timeline

Behind `cinefilm.subtitles.retime` (still off by default, so nothing changes yet). A stored reference is rewritten by
the same factor as a download, but only when every original part of every edition of its film has one agreed frame
rate; a film whose editions disagree keeps its references as they were, and `restore` puts them back. The job visits
references before downloads. Every check and timing fit that names a changed reference, in every edition of the film,
is marked outdated (it keeps deciding until a new verdict replaces it) and is made again in the same run. A check or
fit that read a reference's cues before they changed drops its save (`cues_version`, a column added earlier). A
reference fetched again is never marked as already retimed.
