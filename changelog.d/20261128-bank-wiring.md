### Added — the film bank is wired into the player (not yet driven by anything)

The player's engine now builds the film bank's store, fetcher, decider and loaders, and has a new `bankInput`
method for the look-ahead that a later change will call. With a bad stretch within 30 minutes the bank arms and
keeps copies of what hls.js loads; within 20 minutes, with the playlists held, the film ahead below what the
stretch needs and the buffer covered, it turns on, fetches film in front of hls.js and answers hls.js from it. It
turns off when the stretch is passed or the look-ahead lapses, and a seek forward past what it holds releases it
at once. Nothing calls `bankInput` yet, and with the quality pinned the bank is never built, so playback is
unchanged until the look-ahead lands.
