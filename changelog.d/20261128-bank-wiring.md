### Added — the film bank is wired into the player (not yet driven by anything)

The player's engine now builds the film bank and has a `bankInput` method for the look-ahead that a later change
will call: the bank arms with a bad stretch within 30 minutes, turns on within 20 and answers the player from film it
fetched ahead, and turns off when the stretch is passed. Nothing calls `bankInput` yet, and with the quality pinned
no bank is built, so playback is unchanged.
