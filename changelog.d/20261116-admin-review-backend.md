### Changed — Admin's 30-second summary poll reads far less

`GET /api/admin/summary` no longer loads every film (with its large OpenSubtitles and match-candidate text columns) and
every edition lazily, twice over. The library figures come from two counts, the last finished scan, a projection of each
edition's film and one read of the editions with their files; the forced-subtitle figure from the new `ForcedNeedService.unresolvedCount()`, which gives
the list's `counts.unresolved` without building every row. The answers and the response shape are unchanged. The API's 409 for
a refused Plex import now names what holds the library lock (Admin's own copy follows in the frontend), and the test-only
constructors and one repository query are gone from the production code. The foundation plan's `ScanStatus`,
`AttentionItem` and session 404 wording are corrected.
