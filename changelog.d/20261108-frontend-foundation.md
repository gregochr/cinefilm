### Added — web frontend foundation

The React 19 + Vite web frontend in `frontend/`: tokens and self-hosted fonts, every browse screen from the handover
(Home, Library with live search, Film detail with editions, Person, Collection, Admin placeholder) at desktop and phone
widths, the §5 states (static skeletons, empty library, no results, session-expired sign-in card), and the direct-play
player with custom controls, scrubber, quality and Audio & Subtitles menus, auto-hide, end-of-film overlay, resume and
progress reporting. Includes Vitest coverage, ESLint/Prettier, a fixture mode (`VITE_FIXTURES=1`) for working without
the backend, and a frontend CI workflow.
