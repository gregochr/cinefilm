### Fixed — full English subtitle downloads are measured before the forced checks

The measurement of full English subtitle downloads (phase 5d-1) now runs before the forced subtitle checks in the same
job. Until now it ran last, and on 15 and 16 November the forced checks and the downloads of missing subtitles had used
the whole day's comparison budget, so no full download was ever measured. A spent budget in the measurement still does
not stop the forced checks: they run on stored references for free and stop at their first needed download.
