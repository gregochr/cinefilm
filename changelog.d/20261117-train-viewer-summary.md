### Added — The train viewer's results, its summary and its manual

A run of the train viewer now writes what it saw to `~/cinefilm-viewer/runs/<date>-<time>/` as it goes (with the first
sample, then every minute, then at the end, each file whole), and when it ends it prints a summary and keeps it as
`summary.txt`: the film, how long it played and how it ended, the latest playback record's totals (stalls, time
stalled, start-up, steps, errors, the time on each rung), the samples, the presses and what was held back.
`docs/engineering/train-viewer.md` says how to sign in, what to do before and on a journey, and what each result file
holds.
