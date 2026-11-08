### Added — frontend test standards and coverage gates

The frontend now has written test standards (`docs/engineering/test-standards.md`, frontend section) and a coverage
gate. `npm run test:coverage` fails unless every file in `frontend/src` reaches 95% lines and 90% branches on its
own and the whole run stays above a global floor set at the measured value (lines 99, statements 99, functions 98,
branches 97, raised only, never lowered). CI runs it in place of the plain test run. Every component, hook and
helper that had no test of its own (Scrubber, player menus and overlays, Controls, Header, TabBar, Poster,
CollectionCard, Skeleton, SignInCard, StateMessages, the state hooks, the API resources and the fixture server) now
has one, and the player and each screen cover their error, empty, keyboard and session-expiry paths. A few
decorative elements gained `data-testid`s so tests no longer need `container.querySelector`.
