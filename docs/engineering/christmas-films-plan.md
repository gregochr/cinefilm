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

## Slice 4 detail (2026-11-19): add a film, built as three PRs; the preview is slice 5

Slices 1-3 are merged (#231, #233 API, #235, #239, #243). Slice 4 (add a film) was built as three PRs, all merged:
#246 (groundwork: states API, state helpers, results list), #259 (the desktop search panel), #260 (the phone sheet, the
toast hold, the restored copy). The Home preview below is **slice 5**; the text under "4a" describes the first design,
and the build differs in one way: the search is a plain textbox with a plain list of result buttons (no combobox or
listbox roles), because each row holds a button.

### 4a: Add a film (search to add)

- `api/christmas.js`: `getChristmasStates(filmIds)` -> `GET /api/admin/christmas/states?filmIds=1,2,3` (ids joined by comma; empty list answers `[]` without a request). Answer rows `{filmId, tmdbId|null, state}`, state one of `IN_KEYWORD | IN_ADDED | LEFT_OUT | NOT_IN | KEYWORDS_PENDING | NO_TMDB_ID`.
- `state/christmas.js` (no JSX): `addResultRow(state)` -> `{chip, line, action}` per state (copy from the design: "TMDB keyword" / "Added by me" / "Left out by me" chips; `NOT_IN` -> muted "Not a Christmas film" with Add; `KEYWORDS_PENDING` -> "Keywords not fetched yet" chip with Add; `NO_TMDB_ID` -> muted "Needs a match first" with a "Find the film..." button into Fix match; `IN_KEYWORD` -> Leave out; `IN_ADDED` -> Remove; `LEFT_OUT` -> Put back), unit-tested.
- Search source: reuse the client-side index (`state/searchIndex.js` + `state/search.js` `search(prepared, query)`, titles only: pass caps so only film titles come back, up to 8). Then ask `/states` for those film ids (debounced by query change; ignore stale answers). A result row with no state answer yet shows no button.
- Acting on a result calls the existing `useChristmas` write path (`change`/INCLUDE|EXCLUDE|null with the same Undo toast, 409 text, one write in flight per TMDB id) so the row updates in place and the panel stays open; the list and `/states` answers are re-derived from the new `data` (no second fetch needed: state for a film is recomputed from `data.inWeek/added/leftOut` by filmId where possible, else keep the last `/states` answer and patch it).
- UI: desktop: the search field "Add a film" (340px wide, 44px) at the right of the controls row; typing opens a results panel under it (440px, `#1A1713`, 64px rows; poster, "Title · year", chip or muted line, one button; `role="combobox"`/`listbox` pattern like the header search, arrows move, Esc closes and returns focus to the field). Phone (<= 600px): tapping the field opens a full-screen sheet with "Cancel" (design `06`). One tap acts and the panel stays open.
- Restore the design copy removed in 3: the rule line ("...You can also add any film yourself."), the under-5 line ("Add {5 - n} more and Home shows Christmas films."), the empty-state "You don't have to wait: add any film yourself." with the search field, the "No films in your library are tagged Christmas. Add any you count." copy and the left-out note as in the design; delete the "Deviations" bullet about adding from `docs/design/christmas-films/README.md`.
- "Find the film..." opens Fix match the way other Admin rows do (reuse `FixMatchPanel` via the Admin shell if reachable from this screen, else navigate to the Library todo list; builder checks `Admin.jsx` and picks the existing mechanism, no new Fix match code).
- Tests: states api (empty list, url), `addResultRow` for all six states, panel open/close/Esc/arrow keys, Add then in-place Remove and Undo, NO_TMDB_ID and KEYWORDS_PENDING rows, stale `/states` answer ignored, phone sheet opens and Cancel closes (follow how `SearchSheet` is tested), `userEvent`, no `container.querySelector`.

### Slice 5: Home preview and phone polish (was "4b")

- Preview inside the status card: label "Preview · Home in Christmas week", "Hide preview"/"Show preview" button (open by default, remembered in `localStorage` inside try/catch), the real Home row title and caption ("This week: Christmas" / "A new theme every Monday"), then the first 12 `inWeek` posters reusing Home's poster row (`FilmRow` + `Poster`, lazy, 2:3 reserved). When `fallsBack`: "Christmas week will show the usual weekly theme — under 5 Christmas films." Phone: a full-width 44px "Preview Home in Christmas week ›" button opening the row in a sheet.
- Phone "‹ Admin" back link above the eyebrow; phone cut line uses the short text if the design shows it (`05`).
- Tests: open/closed remembered (and storage throwing), fallsBack text, 12 posters max, sheet on phone, back link.
