### Added — credits classifier: `--look=L5` on CreditsFeaturesMain

Adds the `--look=L5` flag to `CreditsFeaturesMain`. With it the tool reads test films as well: their rows have side `test` and no fold, their records join `frame-labels.json`, `summary.json` counts them apart in a `test` block, and the run writes `look.json` (the flag, the UTC time, the git SHA and the count of test films, no ids). `datasetSha256` still covers development films only. Without the flag nothing changes, and excluded and unlabelled directories are never opened either way. Nothing in the server calls it.
