### Changed — Three small tidy-ups in the train viewer

`viewer.mjs` uses `Number.NaN`, `writePrivate` removes its temporary file through `io.remove` like the rest of the
module, and the fake world in `viewing-run.test.mjs` names the wait limit that was actually applied. No behaviour change.
