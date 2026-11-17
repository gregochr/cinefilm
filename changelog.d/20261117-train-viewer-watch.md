### Added — What the train viewer reads and checks

The train viewer's module `scripts/replay/viewing.mjs` now holds what it will read from the player's page every five
seconds (whether the film plays directly, the "Signed out" card, "Playback was stopped from Admin", a "Try again"), the
buttons it will press and only those, what it checks before it plays (the site answers, signed in, the film exists and
is on Auto, with a dead link waited out for up to ten minutes), and the words and exit codes of each way a run ends.
Nothing uses it yet: the run comes in a later change.
