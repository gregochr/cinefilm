### Added — The home replay's driver, and what two replays showed

The home replay rig is complete: `node scripts/replay/drive.mjs` builds the app, opens the real Player screen in
headless Chrome and has it watch the stand-in film at the pace of the 12 November train journey, and
`scripts/replay/analyse.mjs` says whether a finished run can be believed. `docs/engineering/home-replay.md` now
covers running it and records two replays of the whole journey: between 5 seconds and three and a half minutes
stalled, where the journey itself stalled for 33 and a half. It is still a measuring tool run by hand, not part
of the app.
