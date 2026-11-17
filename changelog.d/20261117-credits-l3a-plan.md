### Docs — credits classifier plan, phase L3a specified

Phase L3a of `docs/engineering/credits-classifier-plan.md` is now specified from measurements on the development
films: how each film's credits open (dark, plate or lit), the 33 picture features with exact formulas and boundary
tests, which credits frames are left out of training, and the tool that writes the features. L3a is split into four
PRs plus a test-film switch, with a release after them so training runs before the segmenter is planned. The plan's
own gate against Plex's markers replaces phase 2b's gate in `credits-detection-plan.md`, which is marked superseded
and was never run. The offline evaluation now scores every early metric at the earliest the dense step could return,
because the stored dense frames are not the product's, so offline the −5 s margin constrains the coarse step; the
simulated dense result is used only for the ±10 s and ±60 s goals.
