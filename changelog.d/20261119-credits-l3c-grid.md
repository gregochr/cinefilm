### Changed — credits calibration grid widened: λ up to 5, δ up to 20 (42,000 points)

The credits calibration grid (`CreditsCalibration`) now tries `λ` of 0, 0.5, 1, 2, 3, 4 and 5 and `δ` of 0, 2, 5, 10, 15 and 20, so it has 42,000 points instead of 20,000 (steps 1 to 3 run 168 times per film instead of 80). `G`, `θ`, `σ` and `θ_d` and the ranking are unchanged. `calibration.json` records the wider `grid`. Nothing in the server calls the tool.
