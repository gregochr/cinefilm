### Added — credits classifier: FilmScore and CreditsCalibration

Adds `FilmScore`, the pure offline score of one film (the segmenter's verdict and start, the simulated dense start on the stored frames, coverage, alignment, early and goal errors, the opening at the start, the margin band and the negative's violation), computed in two parts so a grid runs fast. Adds `CreditsCalibration`, the 20,000-point grid, the seven-level ranking and the nested selection of a training run and the six segmenter numbers, with running counts per point. Nothing in the server calls either.
