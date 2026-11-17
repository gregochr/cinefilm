### Added — The train viewer plays a film

`node scripts/replay/viewer.mjs --film <id> [--minutes <n>] [--place train|away]` opens the real player on the site in a
Chrome with no window, on the signed-in profile, and lets the film play, so that a journey gives a playback record with
nobody watching. It checks first that the site answers (a dead link is waited out, up to ten minutes), that it is
signed in and that the film is on Auto, answers "Where are you watching?", presses "Try again" if the player offers it,
and ends cleanly on the film's end, `--minutes`, Ctrl-C, a sign-out, a stop from Admin, a crashed page or Chrome going
away. Anything the page tries to change on the server is held back, as the guard does. This change keeps what it saw
in memory and says how the run ended; the results on disk come in the next one.
