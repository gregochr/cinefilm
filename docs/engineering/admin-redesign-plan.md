# Admin redesign: five sections, To do first — plan

Written 2026-11-14 from the owner's design bundle (`docs/design/admin-redesign/`: `README.md` is the brief,
`screens/*.png` the targets, `reference/AR*.dc.html` the exact values and copy, `screens/07-buttons-renamed.png` the
copy spec for every button). Revised the same day after an adversarial review (24 findings, all folded in below).
Admin today (`frontend/src/screens/Admin.jsx`) is one page of ten stacked cards. It becomes five routed sections that
open on a short **To do** list of only what CineFilm can't settle itself.

The bundle is the spec for layout, copy and behaviour. Where it guessed at the code and the code says otherwise, this
plan wins and says why ("Checked against the code"). Anything that needs the owner is in "Open questions"; phases
that depend on an answer say so and are not started until it comes.

## Checked against the code (2026-11-14)

- **`/api/admin/credits/dataset` is not TMDB credits.** It is the end-credits *classifier's* frame dataset job
  (`credits/learn/CreditsDatasetJob`, `credits-classifier-plan.md` L2b). The bundle's "Download TMDB credits" would
  start a long frame-extraction job under a false label. Not built as specified (Q1).
- **Cast & crew has no "films with credits" count.** `PlexCreditsStatus` is `running`, `progress`, `lastImport`
  (`plex/PlexCreditsDtos.java`). Phase 6 adds the count. Cast & crew renders today inside the Plex card, which is
  hidden when Plex isn't configured; in phase 6 it becomes its own card, shown without Plex (the count still means
  something), with "Import credits from Plex" only when Plex is configured.
- **Match confidence exists but is dropped.** `MatchOutcome.Candidate` carries an `int score` (points: accept at 60
  with a lead of 10, `TmdbMatcher`); `LibraryIngest` stores candidates with it, a guard-refused match at
  `REFUSED_SCORE = 60`; `AdminService` maps to `CandidateDto` without it. Phase 3b adds `confident` to the
  attention item. The board's "82% sure" has no honest mapping from points, so the percentage is **dropped**; the row
  says "best guess: {Title} ({year})".
- **`minutes` rows are already documented** (`foundation-plan.md`, "Playback health API"): seven figures
  `[minute, median kbit/s, rung played, ms moved, ms stalled, seconds buffered ahead, rung being fetched]`, or five
  in older records. The bundle's guessed layout is wrong. The list route strips `minutes`; the per-minute view
  fetches the one record (`GET /api/admin/playback-health/{id}`) for the selected column.
- **Playback health is stored as JSON text** (`summary VARCHAR(16000)`, `V11__playback_health.sql`); `watchedMs` and
  the stall counts are not queryable, and `replaceIfNewer` (a JPQL bulk update) sets only `summary`, `seq`,
  `updatedAt`. Phase 3a adds real columns and keeps them written on every report.
- **`report.environment`** is `home | train | away`; anything else is "Unknown", shown only under "All".
- **Full subtitles have no online "check".** `FullNeed` has no online state (`UNSURE` is unused). "Being checked" for
  the foreign tab is defined in 3b from the download pipeline (`DownloadPlan` Kind.FULL, the OpenSubtitles full
  candidates), not invented.
- **Forced `UNSURE` only means "OpenSubtitles not asked yet".** After a search a film is `NEEDED` (`OPENSUBTITLES`)
  or `NOT_NEEDED` (`NO_FORCED_ONLINE`). So the bundle's "unsure after the online check has finished" can never match,
  and the forced tab as specified is just `NEEDED` with no forced subtitle: films already answered "yes" (Q3).
- **Unlabelled audio** is `NEEDED`/`ORIGINAL_AUDIO_ASSUMED`; the language to name is TMDB's original language, which
  `FullNeedFilmDto` lacks (the forced DTO has `originalLanguage`). Phase 3b adds it.
- **Distrusted full downloads aren't exposed.** `FullSubtitleStateDto` is `source` only; "Use it anyway" is in
  effect the existing verify route ("Looks right"). See 4a.
- **OpenSubtitles:** searches run from `OpenSubtitlesScheduler` (06:00), downloads from `ForcedDownloadScheduler`
  (06:45 and hourly deferred), capped by `DownloadPlan.MAX_DOWNLOADS = 3` per edition, `DailyDownloadLimit`,
  `ForcedDownloadBlock`. There is no per-film interactive search. Ranking is per edition
  (`OpenSubtitlesRanking.rankFor/rankForFull(results, editionName)`), searches need an IMDb id. The scheduler has
  **no runtime on/off**, so Settings gets **no** "Download subtitles automatically" switch, as the brief allows.
- **Now playing has no device.** `SessionRow` carries no user agent, and live sessions are HLS only (direct play
  doesn't appear). "Playing now" comes from the newest `playback_health` rows updated in the last 2 minutes
  (`report.client`, environment, rung), which covers direct play too; "Stop this stream" shows only where an HLS
  session exists for that edition.
- **Remembered networks** (`state/networks.js`) hold `{ operator, environment }` only, and Forget only makes the
  player ask "Where are you watching?" again; the start quality comes from a per-session probe
  (`transcode/StartRung`). The `07` hint "starts at a safe quality and measures again" is false (Q4).
- **Settings** holds only `continueWatchingMinMinutes`. "Prepare films in Continue watching" and "Show missing films
  in collections" are not settings ("Missing films" is a Library filter chip; pre-made versions aren't built) (Q2).
- **Conversion:** `/api/admin/transcoding` gives the encoder, keyframe-index progress, HLS bytes and live sessions;
  no pre-made versions to count, so that row waits until they exist.
- **The player has no "Subtitles are wrong".** 8a builds it before 8b removes Admin's per-file tools.

## Shared rules (every phase)

- **Data and UI stay separable.** Labels, hints and pure rules live in `frontend/src/state/` (no JSX).
- **Button rule:** label says what it does to what; "…" means it opens something first. A hint is a visible `<p>`
  (12px, `#8A8173`, right-aligned in rows, left on phone) linked by `aria-describedby`. On the Admin pages only "Scan
  library now" is `variant="primary"`; a dialog's own confirm button (Fix match, Edit editions, attach) keeps its
  primary style. This reads README's "In Admin, only Scan library now is primary" as the Admin pages' own buttons;
  dialogs keep their confirm button's style.
- **Copy is exact**, and `adminCopy.js` notes each string's source (`07`, an AR board, the README, or the plan's owner answers). `07` gives no
  hint for "Scan library now" or "Sign out"; those come from the README. "Stop this stream" uses 07's hint until the
  device is known (phase 5a).
- **Each section loads on its own**, static skeletons; a failed section shows an inline error banner in the Edit
  editions error colours (never amber). Deliberate exception: the scan and Plex status pollers live in the layout
  (below), because their timestamps drive every section's reloads.
- **Focus ring** `--cf-focus-ring` everywhere; every new interactive element has a 44px target.
- **Fix match and Edit editions stay dialogs**, hosted by the layout, opened from any section; focus returns to the
  opener (re-tested in each section that opens them).
- **The 3-minute rule:** `FILM_SESSION_MIN_MS = 180_000` in `state/healthFormat.js` and as a constant in
  `PlaybackHealthService`, both introduced in 3a. Used by the chart, the status strip, the log's "Films only" filter
  and the "browsing" pill. Counts are **sessions**, and the copy says "sessions" where a film could count twice
  ("27 of 28 film sessions played smoothly"; the tile "films watched" becomes "sessions").
- **Coverage:** 95/90 per file **and** the global floor in the frontend coverage check; untested inline handlers in
  new section files trip the functions floor. **Every new or changed route gets a `src/api/fixtureServer.js` entry**
  so the dev mock API and its tests keep up.
- Every changed request or response shape updates `foundation-plan.md` and the HTTP contract tests.

## Phases and PRs

One PR per phase, each from updated `main`, built by Sonnet, gated by CLAUDE.md steps 2–6. Keep each PR small.
1 and 2 come first; 3a before 3b and 5a; 3b before 4a; 4b before 4c; 8a before 8b.

| # | Commit | Content | Waits on |
|---|---|---|---|
| 1 | `feat(admin)` | Shell: nested routes, `AdminContext`, rail and phone tabs, redirects; panels move unchanged | — |
| 2 | `refactor(admin)` | `state/adminCopy.js`, `ActionWithHint`, every Admin label/hint | Q4 for one hint |
| 3a | `feat(health)` | Health columns + backfill, filtered/paged query, `FILM_SESSION_MIN_MS` both sides | — |
| 3b | `feat(admin)` | `GET /api/admin/summary`, the shared unresolved rule, `confident`, `originalLanguage` | Q3 |
| 3c | `feat(admin)` | To do page: status strip, four bounded groups, empty state, rail counts | — |
| 4a | `feat(subtitles)` | Subtitles section: two tabs, owner choices, "Everything else" | Q3 |
| 4b | `feat(subtitles)` | Backend: edition-scoped OpenSubtitles search and download | — |
| 4c | `feat(subtitles)` | Picker dialog, wired into both tabs and To do | — |
| 5a | `feat(admin)` | Playback: now playing, "How films played" chart, per-minute view | — |
| 5b | `feat(admin)` | Playback log, conversion, remembered networks | Q4 |
| 6 | `feat(admin)` | Library tidy-up incl. Cast & crew count | Q1 |
| 7 | `feat(admin)` | Settings section | Q2 |
| 8a | `feat(film)` | Film detail "Subtitles → Fix…" and the player's "Subtitles are wrong" | — |
| 8b | `feat(player)` | Player "Earlier / Later"; Admin's file list becomes read-only | — |

### 1 · Shell

**Routes** (`App.jsx`): `<Route path="admin" element={<AdminLayout />}>` with `index` → To do, `subtitles`,
`library`, `playback`, `settings`. Unknown `/admin/foo` falls through to the existing `*` NotFound (tested). Section
components go in `frontend/src/screens/admin/`.

**`AdminLayout`** (`screens/Admin.jsx` becomes it) provides an `AdminContext` (`state/`-side hook, no JSX in the
logic) holding:
- the single `useScan(visible)` and `usePlexImport()` pollers (moved, not duplicated);
- `settledAt`, the version string built today from the two `finishedAt`s, plus the subtitle-search and
  subtitle-check timestamps as today (`Admin.jsx:547-551, 621`);
- `openFixMatch(item, opener)` and `openEditEditions(film, opener)`; the dialogs, the Toast and the `applied`/`edited`
  handlers live in the layout;
- `changed()`: a counter bumped after a match is applied or editions are saved. Sections fold it into their reload
  keys (attention, grouping, scan reload as today), and 3c's summary re-reads on it.

**Redirect:** `/admin#library-scan` matches the index route, so `AdminLayout` checks
`useLocation().hash === '#library-scan'` first and returns `<Navigate replace to="/admin/library#scan" />` before
rendering any section (To do never mounts or fetches). Library scrolls once the card has rendered:
`hash === '#scan' && scanShown`, the effect from `Admin.jsx:577-582` moved; it also handles `#plex-import`.
`StateMessages.jsx` links to `/admin/library#scan`, and its "Import from Plex" link to `/admin/library#plex-import`.
The header gear and `FilmDetail.jsx` keep `/admin`.

**Desktop rail (≥ 601px):** sticky with `top` clearing the sticky app header; 220px; "Admin" in Instrument Serif
40px (a `<p>`-styled title, not a heading); `<nav aria-label="Admin sections">` of `NavLink`s, 44px each, the To do
link with `end`; current link: `#15120F` background, 2px amber left bar, `aria-current="page"`. The muted "Each
section has its own address…" note under the links. Counts arrive in 3c.

**Phone (≤ 600px), per `06`:** Admin hides the app Header and the bottom TabBar (a flag `Layout` reads from the
matched route's `handle`, e.g. `handle: { bareOnPhone: true }`, applied as a class that the phone CSS uses).
In their place: "‹ Home" link, the "Admin" title, then the same `nav` as a horizontally scrollable row of links (no
auto-scroll); current link marked with an amber underline and `aria-current="page"`. Same `aria-label`, so only one
of rail/row is in the accessibility tree at a time (CSS `display:none` on the other).

**Headings:** each section's `<h1>` is its name ("To do", "Subtitles", "Library", "Playback", "Settings"); cards keep
their `<h2>`.

**Layout:** content max 1360px, 40px gutters (16px on phone), grid `220px minmax(0,1fr)`, gap 48px.

**Panels move unchanged:** To do ← Films needing attention; Subtitles ← `SubtitlesPanel`; Library ← Library scan
(now `id="scan"`), Grouping report, Plex import (`id="plex-import"`); Playback ← Transcoding, Playback health;
Settings ← Continue watching, Session, the TMDB credit line at the bottom.

**Tests:** every `renderApp('/admin')` in the eleven `Admin*.test.jsx` files, `App.test.jsx`, `screens.test.jsx` and
`StateMessages.test.jsx` changes to the route its panel now lives on; skeleton-count assertions are re-derived per
section (`Admin.test.jsx:182` counts 7 today). New tests: each route renders its section and only its link carries
`aria-current` (e.g. `/admin/library` marks Library, not To do); `/admin#library-scan` → pathname `/admin/library`,
`scrollIntoView` called once, no `/api/admin/attention` request; `#plex-import` scrolls; `/admin/foo` is NotFound;
the phone row renders at a phone width with Header and TabBar hidden; Fix match and Edit editions return focus.

### 2 · Button copy and hints

- `state/adminCopy.js`: one entry per Admin button, `{ label, busyLabel?, hint?, source }`, plus intros, group
  headings and why-lines from the boards, and both footer variants (desktop "Showing {k} of {n}", phone "{k} of {n}").
- `components/ActionWithHint.jsx`: button + hint `<p>` with `aria-describedby`; row variant right-aligned, phone
  variant full-width 44px buttons with the hint left-aligned.
- Rename every button that still renders in Admin; "Scan now" → "Scan library now", the only primary.
- **Tests:** `adminCopy` entries match `07` row by row; each renamed button is found by its new name with its hint
  linked.

### 3a · Playback health columns and query (backend)

- Flyway migration adding nullable/defaulted `watched_ms`, `stall_count`, `stall_ms`, `startup_ms`, `has_problem`
  (stalls, reconnects, fell back, errors, timeouts), `environment`; index on `started_at`.
- **Backfill in Java** (a Flyway Java migration parsing `summary` with the app's `JsonMapper`), so it runs the same
  on H2 (tests) and Postgres; at most 2,000 rows.
- `create()` and `replaceIfNewer` write every new column from the same parsed report.
- `GET /api/admin/playback-health?since={iso}&minWatchedMs={n}&problems={bool}&page={p}&size={n}`: ordered by
  `started_at` desc, `since` compares `started_at`; answers `{ playbacks, total }`. With no parameters it keeps
  today's 20-row answer **and shape** (no `total`) so nothing else changes. A lean chart projection
  `GET /api/admin/playback-health/chart?since={iso}` returns every session ≥ `FILM_SESSION_MIN_MS` since then with
  only the chart's fields (no paging; 90 days is small).
- `FILM_SESSION_MIN_MS` in `PlaybackHealthService` and `state/healthFormat.js`.
- Tests: migration on H2 (ApiTest) and in `integration/`; column writes on create and on a newer report; 179,999 /
  180,000; `since`; `problems`; paging; fixture entries; contract in `foundation-plan.md`.

### 3b · Summary endpoint and the shared rules (backend)

- One pure backend class `AdminTodoRules` with `unresolvedFull(row)`, `unresolvedForced(row)`, `beingChecked…`, and
  the "confident match" rule (top candidate score ≥ `ACCEPT_SCORE` with the lead, or the single guard-refused
  candidate). The summary and both list services call it, so rail, To do and Subtitles agree.
  - **Full unresolved:** `NEEDED`, no full subtitle playing, and no automatic download due or pending for it
    (`DownloadPlan` Kind.FULL, OpenSubtitles full candidates not yet searched → "being checked"). `UNKNOWN` (no TMDB
    answer) is counted under "Everything else", not listed.
  - **Forced unresolved:** per Q3's answer.
- `GET /api/admin/summary` → `{ todo: { pickFilm, severalFiles, fullMissing, forcedQuestion }, subtitlesUnresolved,
  live, library: { films, lastScanFinishedAt }, playback: { films, smooth }, playingNow: [{ filmTitle, device,
  environment, quality }] }`. It reuses the services' in-memory rules (the attention and need lists are computed in
  Java today); cached on `settledAt` so the rail is cheap.
- `full-need` and `forced-subtitles/films` rows gain `unresolved`, and the responses a `beingChecked` count;
  `FullNeedFilmDto` gains `originalLanguage`; attention items gain `confident` and the candidate `score`.
- Every controller test: 401 and missing-header cases. Unit tests per class with mocked collaborators.

### 3c · To do page

- Intro line exactly as the brief; "To do" + muted "{n} things need you".
- **Status strip:** Library "{n} films · scanned {ago}"; Playback "Last 30 days: {smooth} of {n} film sessions played
  smoothly"; Playing now "{film} · {device} · {quality}" or "Nothing playing". Each links to its section. On phone,
  per `06`, only the Library card shows.
- **Groups** (copy from `AR1`), up to **5** rows, footer as in `adminCopy` with "Show all {n}" (expands in place) or
  "All in Subtitles →":
  1. Pick the right film — `needs-match`. `confident` → "Yes, it's {Title} ({year})" (the existing match `POST`) +
     quiet "Find the film…"; else "Find the film…".
  2. Sort out several files — `multiple-files`, `parts-mismatch`; "Sort out editions…"; `detail` as the suggestion.
  3. English subtitles missing / 4. the forced group — first rows of the Subtitles tabs; until 4a lands they link to
     `/admin/subtitles`.
- Empty groups hidden; all empty → "Nothing to do." under the strip. `protected`, `needs-conversion`, `unreadable`
  are not To do items.
- Rail counts from the summary: To do = sum, Subtitles = `subtitlesUnresolved`, Playback "{n} live".
- **Tests:** `state/adminTodo.js` grouping, kind routing, the 5 cap and footers; hidden groups; all-empty; "Yes,
  it's…" posts and the row leaves; focus returns after both dialogs from To do; rail counts and their re-read on
  `changed()`.

### 4a · Subtitles section

- Lead copy from `AR2`. A real `tablist` (these switch panels in place, unlike the nav): "Foreign-language films {n}"
  and the forced tab, rows = `unresolved` only; filter chips go; "{n} being checked online now".
- Row: poster thumb, title · year, plain reason line (`reasonText`, `fullNeedReason`, a `state/` language-name helper).
- Foreign: "Search for English subtitles…" (4c; hidden until then), "Add my own file…" (existing attach flow scoped
  to the film); unlabelled audio → "It's in {language} — find subtitles" and "It's English" (`NOT_NEEDED` owner
  choice via `PUT /api/admin/films/{id}/full-need`). "Use it anyway" / "Search again…" for a distrusted download only
  if 3b exposes trust (`SidecarTrust` FAILED + the sidecar id); "Use it anyway" calls the existing verify route.
- Forced: per Q3.
- "Everything else": counts, "Show the {n} films" (read-only), quiet "Show all subtitle files" (the existing list,
  paginated at 50, **keeping every per-file tool until 8b**), and the note pointing to the film page and player. The
  subtitle search status line moves here from the scan card.
- **Tests:** each owner choice round-trips (saved, row leaves, still gone after a reload from the mock API); counts.

### 4b · Edition-scoped OpenSubtitles search and download (backend)

- `GET /api/admin/editions/{id}/opensubtitles?kind=full|forced` → ranked by `OpenSubtitlesRanking` for that
  edition's name; forced filters to foreign-parts-only. 400 when the film has no IMDb id.
- `POST /api/admin/editions/{id}/opensubtitles/download` `{ fileId, kind }`. The server accepts only a `fileId` from a
  search it ran for that edition and kind in the last 15 minutes (short-lived cache), else 400. It goes through the
  existing download path (`ForcedDownloadService`, `DailyDownloadLimit`, `DownloadShares`), respects
  `MAX_DOWNLOADS` and `ForcedDownloadBlock`, pins the pick so the nightly plan doesn't replace it, and refuses with
  409 during a scan as the choice routes do.
- Errors: `OpenSubtitlesQuotaException` → 429 `OpenSubtitles daily limit reached — try again tomorrow.`;
  `OpenSubtitlesChargedException` and `OpenSubtitlesUnavailableException` their own statuses and messages;
  unconfigured → 503.
- Tests: service unit tests with mocked collaborators; slice tests incl. 401, missing header, foreign `fileId`,
  quota; fixture entries; contract.

### 4c · Picker

- A `useDialog` dialog listing results; choosing one downloads, closes and the row leaves; errors inline. Wired to
  the foreign tab, the forced "Yes…" and To do. Second click while downloading does nothing; closing mid-request
  ignores the late answer.

### 5a · Playback: now playing, chart, per-minute view

- Now playing from the summary's `playingNow`, "Stop this stream" + hint where an HLS session exists.
- `state/playbackChart.js` (pure): environment filter, rung shares from `rungMs` (Original, 1080p, 720p, ≤480p),
  stall-dot size from `stallMs`, tiles (sessions watched, without a stall, stalled + "all on the {env}" when every
  stalled session shares one environment, median `startupMs`, % at Original or 1080p), column `aria-label`, chart
  summary text.
- Chart from the 3a projection: 30 / 90 days, All / Home / Away / Train; columns oldest → newest, neutral stacks
  (`#EDE6DA`, `#BFB5A5`, `#8E8576`, `#5E574C`, **no amber**), stall dot `#F0876A`, environment letter, legend.
  Columns are buttons with roving tabindex; hover, click or arrows select; latest selected first.
- Selected session: film, date, device, environment, time watched; per-minute strip from the fetched record (speed
  line, quality band from "rung played", stall dots where "ms stalled" > 0), five- and seven-figure rows; the
  `describePlayback` line in mono. A newer selection ignores an older fetch's late answer.
- **Tests:** shaping functions, the 3-minute filter, keyboard selection, both row lengths, the late-answer race.

### 5b · Log, conversion, networks

- Log: table newest first; "browsing" pill under 3 min; All / Films only / With problems as **server-side** filters
  (3a), 50 per page. "Details" expands Player / Network / Quality / Interruptions — **never raw JSON on screen**.
  "Copy raw report" → clipboard + "Copied" toast. Phone: stacked cards.
- Conversion: encoder and status, seek index coverage, HLS storage. Remembered networks: operator and Train/Away,
  "Forget", and the line under them per Q4.
- **Tests:** filters, the pill at the boundary, copy (clipboard mock + toast), Details never rendering JSON.

### 6 · Library tidy-up

- Scan card: "Last scan {ago} · took {duration}", count tiles (protected / needs conversion / unreadable tiles link
  to a plain list), "Scan library now" + hint, progress replaces the button while scanning; grouping report as
  `<details>` "What the last scan changed · {n} split, {n} joined, {n} flagged", paginated at 25.
- Ratings line + "Refresh all ratings". Import from Plex: key-value block, "Import from Plex again", "Try connecting
  to Plex" when unreachable, "Last report" behind a quiet button.
- Cast & crew card: "{n} of {total} films have credits" (new backend count), "Import credits from Plex" when Plex is
  configured. The credits-dataset control only per Q1.

### 7 · Settings

- "Continue watching starts after" (existing, 0–30, blur or Enter); switches per Q2. Second card "Signed in on this
  browser" + "Sign out" + "Signing out here doesn't affect other devices." TMDB credit at the bottom.

### 8a / 8b · Per-file tools leave Admin

- 8a: Film detail "Subtitles → Fix…" ("Use my file instead", "Add my own file…", "Preview lines", "Delete this
  download", "Let CineFilm decide"), and the player's **"Subtitles are wrong"** (deletes the download and blocks it,
  `DELETE /api/admin/subtitles/{id}` + `ForcedDownloadBlock`), so a bad download can still be reported.
- 8b: player "Earlier" / "Later" (and reset) on the existing offset route; Admin's "Show all subtitle files" becomes
  read-only. "Looks right" goes only here, after 8a.

## Owner answers (2026-11-14)

All four defaults chosen: **Q1** leave the credits dataset out of Admin; **Q2** drop both switches; **Q3** (a) the
forced tab lists `NEEDED` films with no forced subtitle found, titled "Forced subtitles not found", actions "Find
forced subtitles…" / "No foreign scenes" (To do group 4 and its why-line follow); **Q4** the networks line is
"Forget makes CineFilm ask where you're watching next time on that network." No phase now waits on the owner.

## Open questions for the owner (answered above)

- **Q1.** "Download TMDB credits" in the bundle points at the end-credits classifier's frame dataset, not cast &
  crew. Leave it out of Admin (default), or show it truthfully under Library as "Credits detection · Prepare training
  frames" with progress and "Stop"?
- **Q2.** "Prepare films in Continue watching" and "Show missing films in collections" aren't settings. Drop them
  (default), or specify them for a later phase?
- **Q3.** The forced tab can't hold "unsure after the online check": once searched, a film is decided. Either (a) the
  tab lists `NEEDED` films with no forced subtitle found, retitled "Forced subtitles not found" with "Find forced
  subtitles…" / "No foreign scenes" (default), or (b) the rule changes so `NO_FORCED_ONLINE` films whose TMDB
  spoken languages include a foreign language surface as the question.
- **Q4.** Forget doesn't reset start quality; it makes the player ask where you are again. Use "Forget makes CineFilm
  ask where you're watching next time on that network." (default)?

## Risks

- Phase 1 rewrites the route in ~14 test files; keep the moves mechanical so the diff reads as moves.
- 3b's unresolved rule must match today's filters (`needed-missing`, `needed`) or rows vanish; its tests pin both.
- The 3a backfill runs inside a migration; the Java migration must tolerate a malformed `summary` (leave defaults).
