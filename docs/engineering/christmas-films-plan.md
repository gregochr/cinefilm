# Christmas films admin screen plan

Status: plan, owner request 2026-11-19. Builds on `holiday-themes-plan.md` (PRs #227, #228).

## What the owner wants

An Admin screen to see which films Home's Christmas week will show, correct the list (leave a film out, put it back,
add one TMDB doesn't tag, remove an added one), and be sure what Home will show. **The legacy `.env` override
implementation is removed**, not seeded (owner, 2026-11-19: "I don't like the file solution... remove the legacy
config"). The design bundle's suggestion of a one-time seed from `CINEFILM_HOLIDAY_CHRISTMAS_*` is dropped: the
variables were never released or set anywhere, so there is nothing to migrate.

The UX spec is the design bundle `cinefilm-christmas-films.zip` (owner's Downloads; `PROMPT.md`, `screens/01-06.png`,
`reference/XM1-6.dc.html`). It is not in the repo, so **a slice-3 builder copies the six PNGs into
`docs/design/christmas-films/screens/` and the six `.dc.html` into `docs/design/christmas-films/reference/`** (all of it
is design output the owner made, no secrets). The numbers below are settled; copy and values come from the PNGs and
`.dc.html`.

## Decisions

1. **Data:** table `christmas_override(tmdb_id bigint primary key, choice varchar(8) not null check (choice in
   ('INCLUDE','EXCLUDE')), created_at timestamptz not null)`, Flyway `V34` (check the next free number). Keyed on the TMDB
   id so a rematch or rescan keeps the choice; one row per film so it cannot be both added and left out.
2. **Rule unchanged:** exclude beats include beats the `christmas` keyword; order and `MAX_FILMS` (12) / `MIN_FILMS`
   (5) come from `WeeklyThemeSelector`. **The screen and Home use the same code path** (one service builds the
   candidate list; Home takes the first 12).
3. **Removed:** `HolidayOverrides` as a `@ConfigurationProperties` record, `config/HolidayConfig`, the two
   `application.properties` keys, the `operations.md` paragraph, and `HolidayOverridesTest`. The selector takes the
   overrides as plain `Set<Long>` include/exclude (or a small record that is not Spring-bound).
4. **Search to add:** reuse the client-side search index (`/api/search-index`, fuzzy, already shipped to the browser)
   to find films, then ask the server for each result's Christmas state:
   `GET /api/admin/christmas/states?filmIds=1,2,3` -> `[{filmId, tmdbId|null, state}]`, state one of `IN_KEYWORD`,
   `IN_ADDED`, `LEFT_OUT`, `NOT_IN`, `KEYWORDS_PENDING` (has a TMDB id, `keywordsFetchedAt` null), `NO_TMDB_ID`.
   A film with no TMDB id cannot be added (overrides are by TMDB id): the row says "Needs a match first" with a
   "Find the film..." button into Fix match.
5. **Undo** is just another `PUT` with the previous choice. A 409 while a scan holds the write lock shows "A scan is
   running — try again when it finishes" and changes nothing.
6. **Admin entry point:** the Admin shell (left rail) has landed; add "Christmas films" between Library and Playback,
   route `/admin/christmas`, title "Christmas films".
7. **Week:** `HolidayWeeks` gives this year's Christmas week; once it has ended the API returns next year's.

## API (`/api/admin/christmas`, signed in, CSRF header on writes; every controller gets a MockMvc test with the 401 and
missing-CSRF cases, AGENTS.md rule 5)

- `GET` -> `{ week:{start,end}, limits:{max,min}, keywords:{fetched,total,running}, inWeek:[...], added:[...],
  leftOut:[...], fallsBack }`.
  - `inWeek`: every film that counts, selector order, **no 12 cap** (client marks rows 13+ as cut). Each
    `{filmId, tmdbId, title, year, posterUrl, reason:'KEYWORD'|'ADDED'}`.
  - `added`: included films TMDB does not tag. `leftOut`: excluded films, with `taggedByTmdb`.
  - `keywords`: films with a TMDB id, those with `keywordsFetchedAt` set, and `KeywordsRefresher` running state.
  - `fallsBack`: `inWeek.size() < min`.
- `PUT /{tmdbId}` `{choice:'INCLUDE'|'EXCLUDE'|null}`; null deletes the override ("Put back" and "Remove"). Returns the
  same body as `GET`.
- `GET /states?filmIds=...` as in decision 4.
- A film that is included and later tagged by TMDB shows as "TMDB keyword"; the redundant INCLUDE row is deleted on
  the next write (not a startup job).
- Update `foundation-plan.md` (API contract) in the PR that adds the endpoints.

## Slices (each a PR of 1,500 changed lines or fewer; Sonnet builds, full gates, fresh reviewer, Codex on the PR)

1. **Overrides table, no UI.** Migration, `ChristmasOverrides` service (reads/writes the table, builds the selector's
   input), `HomeService` and the selector use it, removal of everything in decision 3, `operations.md` paragraph
   deleted, tests (precedence, Home reads the table, migration integration test). Home behaves as before; the only way
   to set overrides is now the table (the screen follows).
2. **Christmas API.** `GET`, `PUT`, `/states`, the shared candidate-list service, scan-lock 409, week dates incl. the
   year a Monday 18 Dec, `fallsBack` at 4 and 5, order identical to the selector including the year/sort-title
   tie-break, `NO_TMDB_ID` and `KEYWORDS_PENDING`, contract doc, MockMvc tests.
3. **The screen.** Copy the design bundle into `docs/design/christmas-films/`. `frontend/src/api/christmas.js`,
   `frontend/src/state/christmas.js` (no JSX: counts, cut index, toast wording, meter segments, week-date wording,
   unit-tested), route and rail item, rule line, status card with the 12-slot meter and keyword strip (poll 30 s while
   running), segmented tabs (`role="tablist"`, arrow keys), list rows with reason chips and one action each, the cut
   line after 12, toast with Undo (8 s, `role="status"`, focus back to the row's button), loading skeleton, error
   with "Try again", empty and under-5 copy. Desktop and phone layouts per `01`, `03`, `04`, `05`.
4. **Adding and preview.** The "Add a film" search (desktop panel, phone full-screen sheet per `02`, `06`), states per
   result, in-place updates, Esc/arrow keys; the Home-row preview (reuse Home's poster row component; open/closed in
   `localStorage` inside try/catch; "Preview Home in Christmas week" sheet on phone; fallback text when `fallsBack`).

Slice 1 can be built now; 2 can start once 1 is merged (same files); 3 needs 2; 4 needs 3.

## Rules for every slice

Amber only on the eyebrow, current-nav marker, meter fills and keyword progress; no amber buttons. Focus ring
`0 0 0 2px #0E0C0A, 0 0 0 4px #EDE6DA`; every target at least 44px; no `any` verifies or sleeps; `frontend/src/api`
and `state` contain no JSX; no `container.querySelector`; `userEvent`, not `fireEvent.click`.

## Done when

The owner can leave out, put back, add and remove films with no config; every change undoes; the "In Christmas week"
order is exactly Home's with the cut after 12; the preview matches Home; a film without keywords or without a TMDB id
says so in search; both layouts and every state in `04` work; no reference to `CINEFILM_HOLIDAY_*` remains.
