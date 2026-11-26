### Added — the subtitle retime job leaves a subtitle that already fits the film alone

Behind `cinefilm.subtitles.retime` (still off by default, so nothing changes yet). On a film with an English subtitle
track of its own, a downloaded subtitle whose original times already fit that track is now left as it is, whatever
frame rate it states. The job also runs after each pass of the keyframe index, when a film's frame rate may have
arrived, and the timing measurements forget a subtitle whose times were rewritten. The rollback order (set `restore`,
restart, wait for the run to end, then roll back the image) is in `docs/engineering/operations.md`.
