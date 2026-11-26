### Added — new subtitle downloads and references are retimed as they arrive

Behind `cinefilm.subtitles.retime` (still off by default, so nothing changes yet). While the setting is `on`, a
download is retimed right after it is stored, by the job's own entry, once the store's transaction has committed and
the library lock been given back; a busy library or a failure leaves it as stored, for the job to retime later (a
forced download on a film with an English track waits for the timing fit, as for the job). A reference is retimed as
soon as it is stored, so the check that follows compares the cues now stored. With `off` or `restore`, arrival
changes nothing.
