### Fixed

The player's subtitle-timing tests no longer lose a track's load event when they fire it before the player listens,
the flake seen on CI. They now set the track's `readyState` first, as a browser does.
