### Docs — forced subtitles plan

Added `docs/engineering/forced-subtitles-plan.md`: forced English subtitles for every film, in four phases. Phase 1
uses the forced `.srt` files beside the films. Phase 2 decides which films need forced subtitles (TMDB and
OpenSubtitles) and checks the existing files against OpenSubtitles. Phase 3 checks the timing. Phase 4 downloads what
is missing. It includes the library inventory of 2026-11-11.
