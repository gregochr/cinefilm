### Changed — downloaded subtitles are now put on the film's own timeline by default

`cinefilm.subtitles.retime` now defaults to `on`: the job retimes downloaded subtitles stated for 23.976/24 fps on a 25 fps film (and the reverse) at start-up and daily at 05:45, and the Admin shows each download's frame-rate facts. Turn it off with `CINEFILM_SUBTITLES_RETIME=off` in `~/cinefilm/.env`; to roll the release back, set `restore` first (`docs/engineering/operations.md`).
