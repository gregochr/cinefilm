### Docs — credits detection plan, phase 2

Phase 2 ran v0.6.1 on the server, then its pure classes on frames from 30 films, measured against Plex's own credits
markers. As shipped it finds almost nothing, because real credits fail its text test. With the fixes, all 23 films that
reach the dense step are `FOUND`, none more than 2 s before Plex's marker, though some are up to about 4 minutes after
it. The plan (`docs/engineering/credits-detection-plan.md`) adds phase 2c, a one-off read-only import of Plex's
markers, built first because Plex is going, and phase 2b, the detector fixes, with stored rows analysed again and a
gate against Plex's markers plus a stills check of films Plex has no marker for. Phase 3 now applies markers in this
order: the owner's, then Plex's, then the detector's result plus 20 s (only after the 2b gate passes), then the 97%
fallback. "A late overlay is better than an early one" (owner, 2026-11-13).
