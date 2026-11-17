### Added — The train viewer's sign-in

`node scripts/replay/viewer.mjs --sign-in` opens a Chrome window on the site, on a profile the viewer keeps under
`~/cinefilm-viewer/`; sign in there, press Return, and the viewer says whether the site took the sign-in. This is the
first half of the train viewer (`docs/engineering/train-viewer-plan.md`): the run that watches a film comes in a later
change. It is a tool run by hand, not part of the app.
