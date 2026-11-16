### Changed — Admin's 30-second summary poll reads far less

`GET /api/admin/summary` no longer loads every film (with its large OpenSubtitles and match-candidate text columns) and
every edition lazily, twice over. The library figures come from two counts, one read of every edition with its files
and the last finished scan; the forced-subtitle figure from the new `ForcedNeedService.unresolvedCount()`, which gives
the list's `counts.unresolved` without building every row. The answers and the response shape are unchanged. A Plex
import refused because the library is busy now names who holds it, as the other 409s do, and the test-only
constructors and one repository query are gone from the production code. The foundation plan's `ScanStatus`,
`AttentionItem` and session 404 wording are corrected.
