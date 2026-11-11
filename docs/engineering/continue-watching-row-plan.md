# Continue watching: one row, a minimum watch time, and an In progress filter — plan

Written 2026-11-10 from the owner's request ("only one row of resume, five films in total") and the Claude Design
handoff `cinefilm-continue-watching-row.zip` (`PROMPT.md` and six screens). Reviewed adversarially the same day;
the review's changes are folded in and listed at the end. **Not delegated yet**: the owner asked to hold the Sonnet
build while the machine is under load.

After the Plex import 91 films are in progress, and Continue watching on Home wraps into 20+ rows of stills. Home
will show **at most five** in-progress films (the hero and one row of four). "See all" leads to the rest in Library,
and a **minimum watch time** (default 5 minutes, set in Admin) keeps barely-started films out.

## What exists today (read before changing anything)

- **The rule** (`BrowseRules.continueWatching`): a film is in Continue watching when its **last played edition**
  (`FilmView.lastPlayedEdition`, skips editions hidden from browse) has `positionMs > 0` and is not
  `hiddenFromContinueWatching`. Sorted by `lastPlayedAt`, newest first. There is **no watched check**: reaching the
  credits marker, or Mark as watched, resets the position to 0 (`PlaybackService.saveProgress` / `setWatched`). A
  rewatch of a watched edition keeps `watched = true` while the position is above 0, so **a "not watched" test would
  drop every rewatch out of Continue watching**. Saving progress also clears the hidden flag.
- **Home** (`HomeService.home`): the hero is item 0 and the row is **all the rest** (no cap, no total). `HomeDtos.Home`
  is `(hero, continueWatching, recentlyAdded, notWatchedInYears, weeklyTheme)`.
- **Library** (`BrowseRules.filterAndSort`, `LibraryQuery`): `show` is `all | unwatched | watched` (by
  `FilmView.watched()`, meaning any edition watched); `sort` is `title | year | added | imdb | rt`. `FilmCard` carries
  `watched` and `progress` (`FilmView.progress()`: any position > 0, **even on a watched film**) but no last-watched
  time or in-progress flag.
- **Settings**: there is **no stored server-settings mechanism**. Config is Spring properties (`ScanConfig`,
  `TranscodeConfig`, …) and per-film `FilmPreference`. The handoff's "store it with the other server settings" has
  nothing to attach to; this plan adds a table.
- **Frontend**: `Home.jsx` renders the hero and the whole row through `FilmRow` (variant `stills`, CSS
  `repeat(auto-fill, minmax(280px, 1fr))`; phone: flex scroller, `240px` cards, snap). `useContinueWatching` holds
  the row and the hero with optimistic Remove / Mark as watched, one undoable action, 8 s Undo
  (`UNDO_MS`), serialised per edition. `FilmRow`'s "See all" uses `.row__all` (13px, `--cf-text-muted`) with the
  label "See all: {title}". `Poster` has no progress bar or menu. `LibraryFilms.jsx` keeps `show`/`sort` in the URL.
  `fixtureServer.js` mirrors the API for frontend tests and must change with it.
- **Not built**: pre-made travel versions (the handoff's "films in Continue watching are prepared automatically").
  When built, the queue must use the **in-progress rule from this plan**, not the visible five.

## Settled decisions (go into the handover README as decision 17 in phase A)

17. **Continue watching: one row and a minimum watch time.**
    - **In progress:** the film's last played edition has a position at or above the minimum watch time (and above
      0). Watched status does not matter (a rewatch is in progress); the position is 0 once watched.
    - **Minimum watch time:** `continueWatchingMinMinutes`, 0–30, default 5, set in Admin. It is applied at query
      time; no progress is changed or deleted. A film below it keeps its resume position (Film detail still offers
      "Resume from …") but counts as unwatched, or watched if it was watched before.
    - **Home:** the hero (newest in progress) and **at most 4** cards, at every width. Hidden films are left out.
      The caption is "{total} in progress", where the total includes the hero and leaves hidden films out. "See all"
      shows only when the total is over 5.
    - **Library Show:** All / In progress / Unwatched / Watched, mutually exclusive. **In progress** follows the
      rule above and **includes films removed from Continue watching**. **Watched** means any edition is watched
      and the film is not in progress. **Unwatched** is the rest.
    - **Library Sort:** adds **Last watched** (`last-watched`, newest first; never-played films last, by title).
      Choosing In progress switches Sort to Last watched.
    - A "+N more" tile was considered and rejected.

## API changes

- `FilmCard` gains `inProgress: boolean`, `lastWatchedAt: Instant|null` (the last played edition's
  `lastPlayedAt`) and `hiddenFromContinueWatching: boolean` (that edition's flag; phase C's Library menu needs it). Clients never apply the minimum themselves: they read `inProgress`. The card still carries
  `progress` for any position above 0, because Film detail and the player resume below the minimum too.
- `Home` gains `continueWatchingTotal: int` (the hero included). `continueWatching` becomes **the next 12** after
  the hero, not all of them: 4 shown and 8 spare for backfill.
- `GET /api/films?show=in-progress&sort=last-watched` (with `filterAndSort`'s error messages updated).
- `GET /api/admin/settings` → `{ continueWatchingMinMinutes, inProgressCount }`.
  `PUT /api/admin/settings` with `{ continueWatchingMinMinutes }` validates 0–30 (400 otherwise) and returns the
  same shape with the recomputed count. `inProgressCount` uses the Library rule (hidden films included).

## Phases and PRs

One PR per phase from the updated `main`. Sonnet builds, the planner reviews, CI and Codex gate, then squash-merge.
Each phase is releasable on its own: A changes only the rule, the payload and new endpoints, and the old frontend
ignores the new fields; B and C are frontend only.

### Phase A — backend: the rule, the setting, the payload (`feat(api)`)

1. **Migration `V12__server_setting.sql`:** a single-row `server_setting` table (`id smallint primary key check
   (id = 1)`, `continue_watching_min_minutes int not null default 5 check (… between 0 and 30)`), with the row
   inserted. Use a typed single row, not a key/value table: there is one setting, and further ones become columns.
   Add the `ServerSetting` entity and repository, and a `ServerSettingsService` with `minWatchMs()` and
   `update(minutes)`.
2. **`BrowseRules`:** `inProgress(FilmView, long minMs)` (position ≥ max(minMs, 1) on the last played edition);
   `continueWatching(views, minMs)` = in progress and not hidden, newest first; `filterAndSort` takes `minMs`, adds
   `in-progress` to `show` with the exclusive definitions above, and adds `last-watched` to the sort. Keep the class
   pure (no Spring): callers pass `minMs`.
3. **`FilmMapper.card`** takes `minMs` and fills `inProgress`, `lastWatchedAt` and `hiddenFromContinueWatching`. Thread `minMs` through every
   caller (`HomeService`, `FilmService` library list, person and collection pages, anything else that builds a
   card). Look for them with a search, not from memory.
4. **`HomeService`:** hero = first; row = `subList(1, min(13, size))`; total = size.
5. **`AdminController`** (or a small `SettingsController` under `/api/admin`): GET and PUT as above.
6. **`fixtureServer.js` and `test/fixtures/home.json`:** the new fields, the new show and sort values. Keep frontend
   tests green with no UI change.
7. **Handover README:** add decision 17 (planner writes it, in this PR).
8. **Tests:**
   - `BrowseRulesTest`: the threshold at 4:59.999 / 5:00 / 5:00.001 with the default; minimum 0 (any position above 0
     counts, position 0 never does); minimum 30; a rewatch (watched and position above the minimum) is in progress;
     a watched film below the minimum is Watched, not Unwatched; a hidden film is in Library In progress but not in
     Continue watching; the three non-All filters are disjoint and cover every film; Last watched order with nulls
     last and ties by title; the bad-value error messages list the new values.
   - `HomeServiceTest`: 0 / 1 / 5 / 6 / 20 films (the hero, a row of at most 12, the total including the hero).
   - Settings: the default is 5; PUT with -1, 31 or null returns 400; a PUT changes `inProgressCount` and the Home
     total straight away (no cache).
   - Integration (CI only): V12 applies on Postgres 17, and the check constraint rejects 31.
   - `changelog.d/` entry: "Minimum watch time for Continue watching (Admin, default 5 minutes); Library In progress
     filter and Last watched sort."

### Phase B — Home: one row, backfill, iPhone (`feat(home)`)

1. **`Home.jsx`:** show `row.slice(0, 4)`. Sub-line `${total} in progress`; `seeAllTo` =
   `/library?show=in-progress&sort=last-watched` only when `total > 5`; the accessible name is
   "See all {total} films in progress". `FilmRow` gains an optional `seeAllLabel` prop (the other rows keep
   "See all: {title}").
2. **`useContinueWatching`:** keep the full 12-item list and the server's total as state. Remove and Mark as watched
   take the film out of the list and decrement the total; Undo re-inserts it at its index and increments the total.
   Because the screen shows `slice(0, 4)`, backfill and "the backfilled card leaves again on Undo" follow with no
   extra state. Keep the existing per-edition serialisation, versioning and rollback; the rollback also restores the
   total. When the spares run out, the row shows fewer cards (the next reload refills it).
3. **Focus after removal:** the card now in the removed card's slot; else the previous card; else the row heading
   (`tabIndex={-1}` on the `h2`); if the row has gone, Undo (as today).
4. **CSS (`browse.css`):**
   - `.row__grid--stills`: `repeat(4, minmax(0, 1fr))`, gap 20px, so fewer than four stay left-aligned at the same
     width. Check that the tablet widths still look right: the auto-fill used to drop to 2–3 columns, and four
     columns at 768px make about 170px stills. If that is too small, use 2 columns at tablet width and stay within the
     cap of 4. The planner decides from a screenshot.
   - The incoming card: `@keyframes` from `translateX(16px)` and opacity 0, 200 ms ease-out, applied only to a card
     that entered after the first render. The shift of the remaining cards is a grid reflow; animating it would need
     FLIP. **Build the slide-in only**: the shift is instant, and that is a deliberate departure from the design, in
     line with "fast and quiet". `prefers-reduced-motion: reduce` turns off the animation.
   - Phone (`@media` block at line ~775): stills cards `280px` (from 240), gap 12px, the existing snap; the header
     puts the caption under the title and "See all" top-right, with a tap target of at least 44px.
5. **"See all" colour:** the handoff gives `#D5CCBE` and also says "match the other rows" (`--cf-text-muted`). Match
   the other rows: one link style for all of Home. If the token is visibly dimmer than the design, change the token
   for every row in this PR, not just this one.
6. **`HomeSkeleton`:** a hero and exactly 4 still placeholders.
7. **Hero after watching elsewhere:** Home refetches on mount, so a film finished in the player or marked on Film
   detail is gone from the hero on return. Test it; build nothing new.
8. **Tests:** the cap at 4 with 12 in the payload; the hero is not repeated in the row; "See all" at total 5 (absent)
   and 6 (present), with its accessible name; the caption after Remove (91 → 90) and after Undo; backfill order
   (Remove card 2, then card 5 is fourth); two Removes within 8 s, then Undo of the second; Undo after a backfill
   puts the original back and pushes the backfilled card out; a failed Remove rolls back the total; the focus targets
   in all four cases; 1 film (hero, no row) and 0 (no hero, no row, Home starts with Recently added); the
   reduced-motion class.

### Phase C — Library filter, in-progress posters, Admin card (`feat(library)`, `feat(admin)`)

1. **`LibraryFilms.jsx`:** `SHOWS` gets In progress (value `in-progress`) after All; `SORTS` gets Last watched
   (`last-watched`); `SORT_PHRASE['last-watched'] = 'last watched'`. Choosing In progress also sets
   `sort=last-watched` in the same `setParams`; choosing another Show leaves Sort as it is. Heading: "{n} films in
   progress · sorted by …" when `show=in-progress`. Back, forward and reload come from the URL, as today. The
   collections tab's `show` picker (`LibraryCollections.jsx`) **does not get In progress** (a collection is not in
   progress). Grouping with In progress: turn grouping off while the filter is on, because a fan of posters would
   hide which film is in progress. Off unless the owner says otherwise (see "Open for the owner").
2. **`Poster`:** when `film.inProgress`, show a 3px amber fill along the bottom edge (reuse the ContinueCard fill
   class) and the captions "{title} · {year}" and "{time} left" (muted, `formatLeft`). Only `inProgress` turns this
   on, never `progress`, so a film below the minimum looks unwatched.
3. **The ⋯ menu on in-progress posters:** reuse `ContinueMenu` and `UndoToast`. Extract the optimistic core of
   `useContinueWatching` into a list-agnostic hook (`useUndoableFilmActions(list, total, reload)`) that Home and
   Library both use, rather than copying it. In Library, **Remove from Continue watching** is offered only when the
   film is not already hidden (`FilmCard.hiddenFromContinueWatching`, added in phase A). On Library, Remove leaves the poster in place, since
   the film is still in progress. Mark as watched takes it out of the In progress grid (with Undo).
4. **Admin:** a "Continue watching" card in the existing `admin__` style: the label "Minimum watched before a film
   counts as in progress", a number input (0–30, step 1) with a "minutes" suffix, the help text "Films you've
   watched for less than this stay out of Continue watching. Their place is still saved.", and under it
   "{n} films currently in progress". It saves on blur or Enter (no Save button; there are no other settings to batch)
   and shows a status line on success. If the input is out of range it shows an inline error and sends nothing. The
   count updates from the PUT response. State in `frontend/src/state/useAdmin.js` (no JSX), API in
   `api/resources.js`.
5. **Tests:** the four Show values and the auto-switch to Last watched; the deep link (`?show=in-progress&sort=last-watched`
   renders that state); the heading copy; the poster bar for `inProgress` true / false-with-progress; the Library
   menu (Remove hidden when the film is already hidden; Mark as watched removes and Undo restores); the Admin input
   (save, out-of-range error, count updated, API failure message); the collections tab unchanged.

## Gates (every phase)

The two commands in `CLAUDE.md`, gated on exit codes, plus 90% diff coverage in CI on both sides. Phase A: also boot
the `dev` profile against Homebrew Postgres (V12 applies, Home loads, `GET /api/admin/settings` returns 5). Phases B
and C: screenshots of Home at 1440 / 768 / 390 and Library In progress at 1440, compared with
`screens/01`, `02`, `03` and `05`.

## Adversarial review: what changed

1. **"Not watched" in the handoff's definition would have broken rewatches** (the watched flag stays true while a
   watched edition is replayed). Replaced by "position ≥ minimum on the last played edition", which is today's rule
   plus the minimum.
2. **Mutually exclusive filters needed a precedence rule:** In progress wins over Watched (a rewatch), and Watched
   means "watched and not in progress".
3. **Backfill needs spares in the payload.** Without them the row empties after four removals until a reload. The
   server sends 12; the list is capped so a 91-film payload is not shipped to draw 4.
4. **No settings mechanism exists.** Added `server_setting` (typed single row) instead of inventing a key/value store.
5. **Below-minimum films must not look in progress anywhere.** That is why the server sends an `inProgress` flag and
   `Poster` keys on it rather than on `progress`, which stays for resuming.
6. **Count mismatch, accepted:** Home's total leaves out films removed from Continue watching, while Library's
   In progress includes them (as the handoff specifies). After one Remove, Home says "90" and Library says "91".
   **Owner confirmed this as designed (2026-11-10).**
7. **The shift animation is dropped** (FLIP for a 200 ms reflow is not worth it, and "fast and quiet" favours none);
   the slide-in stays.
8. **Tablet width:** a fixed 4-column grid at 768px gives small stills; the planner checks screenshots, and the
   fallback is 2 columns at tablet width.
9. **The future pre-made-versions queue** must use this rule, not the visible five; noted under "Not built".

## Open for the owner

- Grouping collections while In progress is on: off (recommended, and the default if not answered by phase C) or
  as usual.
