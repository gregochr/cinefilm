### Added — new subtitle downloads and references are retimed as they arrive

Behind `cinefilm.subtitles.retime` (still off by default, so nothing changes yet). While the setting is `on`, a
download is decided and retimed in the same transaction and under the same library lock as its store (a forced
download on a film with an English track waits for the timing fit, as for the job), and a reference is retimed as soon
as it is stored, so the check that follows compares the cues now stored. With `off` or `restore`, arrival changes
nothing.
