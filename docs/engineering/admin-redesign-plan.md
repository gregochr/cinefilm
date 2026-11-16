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
- **Old stored scores.** Match candidates stored before 3b-1 hold rounded scores, and a tagged guard-refused match was
  stored at 60. `confident` needs a top score above 60, so those rows read as not confident until the next scan
  rewrites them: the safe side, a one-off. New rows hold floored scores and a tagged refusal is stored at 100.
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
  library now" is `variant="primary"`; a dialog's own confirm button (Fix match, Edit editions) keeps its
  primary style. This reads README's "In Admin, only Scan library now is primary" as the Admin pages' own buttons;
  dialogs keep their confirm button's style.
- **Copy is exact**, and `adminCopy.js` notes each string's source (`07`, an AR board, the README, or the plan's owner answers). `07` gives no
  hint for "Scan library now" or "Sign out"; those come from the README. "Stop this stream" uses 07's and AR4's
  "Ends playback on {device}; it can resume from here." since Stop ends playback (the "Stop ends playback" decision at 5a-1).
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
| 3b-1 | `feat(subtitles)` | `AdminTodoRules`, unresolved/beingChecked, originalLanguage, confident+score | Q3 |
| 3b-2 | `feat(admin)` | `GET /api/admin/summary` | 3b-1 |
| 3c-1 | `feat(admin)` | summary hook, rail counts, To do heading and status strip | — |
| 3c-2 | `feat(admin)` | the four To do groups, "Yes, it's…", empty state | 3c-1 |
| 4a | `feat(subtitles)` | Subtitles section: two tabs, owner choices, "Everything else" | Q3 |
| 4b | `feat(subtitles)` | Backend: edition-scoped OpenSubtitles search and download | — |
| 4c | `feat(subtitles)` | Picker dialog, wired into both tabs and To do | — |
| 5a-1 | `feat(admin)` | Playback: Now playing, "Stop this stream", the player's touch hint | — |
| 5a-2 | `feat(admin)` | Playback: "How films played" chart, per-minute view | 5a-1 |
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
  `has_problem` is exactly: `stallCount > 0`, `reconnectCount > 0`, `fellBack`, `netErrors > 0` or `timeouts > 0`;
  step-downs (`stepsDown`) do not count, being the adaptive quality working.
- **Backfill in Java** (a Flyway Java migration parsing `summary` with its own `JsonMapper` into a frozen private
  record, so it never depends on live classes), so it runs the same on H2 (tests) and Postgres; at most 2,000 rows.
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
  the "confident match" rule (top stored score ≥ `ACCEPT_SCORE + 1`, and the candidate alone or leading the second by
  ≥ `MARGIN + 1`; a tagged guard refusal is stored as one candidate at 100, and a title search may also leave a single
  candidate). The summary and both list services call it, so rail, To do and Subtitles agree.
  - **Full unresolved:** `NEEDED`, no full subtitle playing, and no automatic download due or pending for it
    (`DownloadPlan` Kind.FULL, OpenSubtitles full candidates not yet searched → "being checked"). `UNKNOWN` (no TMDB
    answer) is counted under "Everything else", not listed.
  - **Forced unresolved:** per Q3's answer.
- `GET /api/admin/summary` → `{ todo: { pickFilm, severalFiles, fullMissing, forcedQuestion }, subtitlesUnresolved,
  live, library: { films, lastScanFinishedAt }, playback: { films, smooth }, playingNow: [{ filmTitle, device,
  environment, quality }] }`. It reuses the services' in-memory rules (the attention and need lists are computed in
  Java today); not cached on the server; the frontend refetches on its `settledAt` key and on `changed()`.
- `full-need` and `forced-subtitles/films` rows gain `unresolved`, and the responses a `beingChecked` count;
  `FullNeedFilmDto` gains `originalLanguage`; attention items gain `confident` and the candidate `score`.
- Every controller test: 401 and missing-header cases. Unit tests per class with mocked collaborators.

### 3c · To do page

- Intro line exactly as the brief; "To do" + muted "{n} things need you".
- **Status strip:** Library "{n} films · scanned {ago}"; Playback "Last 30 days: {smooth} of {n} film sessions played
  smoothly"; Playing now "{film} · {device} · {quality}" or "Nothing playing". Each links to its section. On phone,
  per `06`, only the Library card shows.
- **3c-1 decision:** a failed re-read keeps the last good figures (rail counts, heading count, strip) with no banner; the
  next 30 s poll retries. The error banner shows only when no summary has been read yet.
- **5a-1 decision (built):** "Now playing" is the first card of Playback (title and "1 stream" from `04`; the README's
  Playback section says "Now playing" too), built from the summary's `playingNow` with no request of its own. The
  section reads `/api/admin/transcoding` once and shares it with the Transcoding card. Stop shows where a live session
  has the entry's `editionId` (the server keeps at most one per edition, `SessionManager.create` replaces any live one,
  so "newest" is only a defence) and uses `DELETE /api/admin/transcoding/sessions/{id}`. In flight the button is
  `aria-disabled` and a second click is ignored; a failure shows "Couldn't stop that stream. Try again." and keeps the
  entry; success moves focus to the section title if it was on that row and re-reads the live list and the summary
  (the Transcoding card's Stop re-reads the summary too). **Stop ends playback (owner 2026-11-15, built after 5a-1):** 5a-1 shipped Stop as an honest "Stops converting
  this stream on the server; if it's still playing, the player starts it again." because it only called `SessionManager.end`
  and the player's `recover()` made a new session under the same `playbackId`. The follow-up
  `feat(playback): Admin Stop ends playback on the device` closes that open item: the Admin stop route
  (`SessionService.stopFromAdmin`) records the session id and the `playbackId` it was created with (the session request
  now carries the viewing's id) in `StoppedPlaybacks` for 24 hours (the longest a quiet session is rebuilt, so a paused device resumed later still stops), in memory (a restart forgets it, and the player then
  recovers as after any restart); the session's master, media and segment routes and a new session for that
  `playbackId` answer 410 (`foundation-plan.md`). The player treats a 410 as a stop, not a failure (hls.js error or
  native master check or session request): it destroys the engine, pauses, and shows "Playback was stopped from Admin."
  with "Play again", which keeps the same player, video and hooks (nothing is remounted, so volume, quality, audio, subtitles and their timing, fullscreen and the answered environment question all stay) and restarts only the stream: `useHealthReport.newViewing()` starts a new health record (a new `playbackId`, the clock at zero) and `useStream.resume(atMs)` makes a new session at the position the stop caught (0 is a position too), reads PREPARING like a fresh start and hands focus back to the player (read before the engine is destroyed, because that resets the element to 0). The transcoding session rows carry the `playbackId` and Stop on a row targets the session of that row's viewing, never another device's playback of the same edition; the source is cleared, and play, seeking and quality changes are ignored while stopped. The Stop call sends the row's `playbackId`, so the server also ends a newer session of that viewing (one made after the list was read). A 404, no
  answer, a 5xx and a restart recover as before (tests pin each). The hint is therefore AR4's and 07's again:
  "Ends playback on {device}; it can resume from here" (no full stop, as in the handover; it drops "the" because a described device reads as a name; "that device" when the device is unknown). A stopped entry is
  hidden keyed on the stopped session id: only while its edition has no live session or still has that one; a session
  with another id brings it back with Stop and drops the hide for good (a later end of that session does not hide it again). The row has no poster, no year, no "1h 12m in" (none is in `playingNow`) and no operator in "(EE)".
  The touch hint is `client.touch` in the health report, stored in the report JSON (no migration: the report is a JSON
  text column and `ClientInfo` gained a field) and passed through to `playingNow.touch`; the log rows carry it inside
  `report.client`. `describeDevice(userAgent, touch)` reads a Macintosh user agent with `touch` as an iPad. Chart rows
  carry no `touch` yet (5a-2 adds it, below).
- **5a-2 decision (built):** "How films played" is the second card of Playback and the only reader of
  `GET /api/admin/playback-health/chart?since=` (30 or 90 days back, `since` computed when the read starts; a range
  switch reads again). **The environment filter is applied in the browser**, so switching it re-reads nothing: the route has no
  environment parameter and 90 days is a few hundred small rows. **The frontend applies no 3-minute filter:** the
  chart route already keeps only `watchedMs` ≥ `FILM_SESSION_MIN_MS` (179,999 / 180,000 is tested on the server), and
  AGENTS.md rule 3 forbids a second copy of the rule. Counts are sessions (tile "sessions watched"). Rung shares are
  fractions of the time on known rungs (`rungMs` keys the ladder does not know are ignored; no time means an empty
  column); stall dots are 8, 12 and 16 px at 0 < stall < 5 s, < 20 s and above. The selected session's film and facts
  come from the chart row; the figures line and the strip from `GET /api/admin/playback-health/{id}`, read on its own
  per selected id (an earlier id's request is aborted and a late answer ignored; the operator joins the heading when
  the record arrives, and the record's first `describePlayback` line is not repeated under that heading). A column
  is a button with a roving tabindex (arrows, Home, End; a mouse entering selects, a touch does not); columns are fixed
  at 22 px with a mouse and 44 px when any pointer is coarse (`any-pointer: coarse`, which also lifts the range and
  environment pills to 44 px), left-aligned, the row scrolling sideways when 90 days will not fit; the selected column
  is scrolled into view, since the newest is at the far end. **The 22 px mouse columns are an explicit exception to
  the 44 px rule** (WCAG 2.5.8: a 22 px column and its 6 px gap are a 28 px pitch, so a 24 px circle centred on any
  column meets no other column; the arrow keys are the other way in). The 100% / 50% / 0% and "stalls" labels of the board sit left of the columns. A column's stack is the
  share of the time **on known rungs** (`bucketShares`), while the mono line under the heading is the share of the
  **watched** time (`healthFormat.rungShares`); the two can differ by the few seconds that no rung covers. The stall
  dot and the "stalled" tile both key on `stallCount` > 0 (the dot sized by `stallMs`, never smaller than the small
  one). A 404 for the selected record (the table keeps 2,000) reads as "No minute by minute figures", with no retry. **Backend:**
  `ChartRow` gained `touch` (`@JsonInclude(NON_NULL)`, so older rows read as before). **Invented copy (planner, 5a-2):**
  the tile "sessions watched" (the board says "films watched"), the notes "all at home" and "all away" beside the
  board's "all on the train", the group names of the filters, the chart's summary sentence, the empty, loading and
  error lines, "No minute by minute figures for this session." Everything else is AR4's.
- **3c-2 decision (built):** groups 3 and 4 are read-only rows (title, a reason line) with the "All in Subtitles →"
  footer, always shown, since their actions arrive with 4a/4c. Groups 1 and 2 read the attention list; 3 and 4 the
  two need lists, each its own request with its own skeleton and error banner. The skeleton or banner shows until a
  source's first answer; after that a failed re-read keeps the last list with no banner, as the summary does in 3c-1.
  "Yes, it's…" hides its row at once on success, moves focus to the section title there and then, and the lists and
  summary re-read; "Show all" becomes "Show fewer" on the same button. On phone (`06`) the "To do" heading is visually
  hidden and the intro dropped, the group header shows a count where the wide one shows the why-line, and the footers
  read "k of n" with "Show all" and "All in Subtitles" (no count, no arrow). **Posters are deferred:** attention items
  carry none, so rows have no poster column until the API gives one. Copy the planner added is marked in
  `adminCopy.js` ("planner, 3c-2"). **3c is complete.**
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
- **4a decision (built):** the section is `screens/admin/AdminSubtitles.jsx`: lead (AR2), a real `tablist`
  ("Foreign-language films {n}", "Forced subtitles not found {n}": Q3; automatic activation, roving tabindex, arrows
  wrap, Home/End), the two panels, "Everything else". Rows are the server's `unresolved` rows only and the filter chips
  are gone (`FullNeedSection`/`ForcedNeedSection` deleted; their chip helpers in `fullNeed.js`/`forcedNeed.js` stay
  until the cleanup in 8b). Each list loads on its own: a skeleton until its first answer, an inline banner (never
  amber) with its own retry if that read failed, and a banner above the kept rows if a re-read fails; the other tab and
  "Everything else" never wait. Both panels are in the page (the inactive one `hidden`), so `aria-controls` is true.
  - **Actions built:** foreign tab "Add my own file…" (the existing attach route scoped to the film: it lists the
    owner's own full subtitle files not already on that film, then the edition and part; `AddOwnFile`,
    `useFilmFilePicker`) and, for unlabelled audio (`ORIGINAL_AUDIO_ASSUMED`), "It's English" (`NOT_NEEDED` through
    `PUT /api/admin/films/{id}/full-need`); forced tab "No foreign scenes" (`NOT_NEEDED` through the forced route).
    One request per film: a click on a row that is saving does nothing (the button is `aria-disabled`, still
    focusable). A saved choice calls `markChanged` (`onSaved` of `useNeedChoices`), so the summary, rail counts, To do
    groups and both lists re-read; the row leaves at once from the saved row. A failure keeps the row with the existing
    `needProblem` words. When the row that had focus leaves, focus moves to the tab panel (focus that is somewhere
    real is left alone, and a failed save keeps its focus).
  - **Deferred, hidden until 4c (the picker):** "Search for English subtitles…", "Find forced subtitles…" and
    "It's in {language} — find subtitles" (the last two open the picker too, so a button that does not find anything
    would lie). Their copy is already in `adminCopy.js`.
  - **Deferred, API:** "Use it anyway" / "Search again…". The API exposes no trust: `FullSubtitleStateDto` is `source`
    only (no `SidecarTrust` and no sidecar id), so the row cannot know a download is distrusted or which row to verify.
    Needs a backend field first (not added here).
  - **Posters deferred**, as in 3c-2: the need rows carry none.
  - **"Everything else":** the figures are the server's, none derived in the browser (AGENTS.md rule 3). `FullNeedFilmDto`
    gains a `subtitled` flag (browsable, NEEDED and a full subtitle plays: `AdminTodoRules.subtitledFull`, the complement of
    `inPlayFull`), `counts.subtitled` is the rows flagged (so the button's count equals the rows it opens, and the list is
    `rows.filter(r => r.subtitled)`), and `FullNeedCountsDto` gains `inEnglish` (browsable films whose full need is NOT_NEEDED, in the list's scope or
    out of it: English or `xx` or missing originals, `ENGLISH_AUDIO` dubs, the owner's "not needed"; a film with no TMDB
    language is UNKNOWN and not counted). "Browsable" is `Film.browsable` (one bulk read of every edition with its files, left-joined).
    "{n} foreign films have English subtitles" is `counts.subtitled`, "{n} films are in English" is `counts.inEnglish`,
    "{n} being checked online now" adds the two lists' `counts.beingChecked` and shows only once both lists have been
    read; each figure shows once its list has. "Show the {n} film(s)" is a read-only list of the rows the server flagged `subtitled`. "Show all
    subtitle files" mounts the old file list (card "Subtitle files", every per-file tool kept until 8b) only when asked,
    so it is read only then. It lists 50 at a time with "Show more ({n} left)": the files are grouped first and the
    first 50 taken across the groups as drawn, so an unmatched file is on page one. A per-file tool that changes
    something calls `markChanged`. The OpenSubtitles search status line moved here from the scan card.
  - **"Your choices ({n})" (undo):** deleting the old panels removed every way back from "It's English", "No foreign
    scenes" or an owner "needed", so "Everything else" has a quiet toggle, shown only while a list holds a row the server
    says the owner chose for (`ownerChoice` not null). It lists the film, which list and what was chosen, with "Let
    CineFilm decide" (07's words), which sends choice `null` through that list's own PUT. One request per film (the
    button is `aria-disabled` while saving); a failure keeps the row with the list's words; the row leaves at once and
    the page reads again; focus moves to the toggle, or to the section heading after the last one. Copy is "planner, 4a".
  - **`onSaved` runs once per chain that saved anything**, with the last saved row, even when a later queued write failed
    (that failure still shows on the row), and also when the owner left the page before the save came back
    (`markChanged` lives in the layout, so the rail counts and To do read again); the page then sets no state.
  - **Add my own file:** hint "Picks one of your subtitle files for this film" (planner, 4a; 07's names no fixed film).
    Cancel is `aria-disabled` while the attach is in flight; a loading line shows while the editions load and "This film
    has no edition a subtitle file can be added to." when it has none.
  - **Wording the planner added** (marked "planner, 4a" in `adminCopy.js`): the unlabelled-audio line says "CineFilm
    assumed {language}, the original language TMDB lists" because AR2's "CineFilm assumed English" is the reverse of
    what the server does (To do now says AR2's "nothing found automatically" too); the forced panel heading "Need forced subtitles"; the empty states; singulars; the
    load/refresh errors; the add-a-file form's words; "Hide the {n} films" / "Hide the subtitle files".
  - Also still to do from 3c-2: the To do subtitle rows get these actions only if the owner asks (they stay read-only
    with the "All in Subtitles →" footer).

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
  unconfigured → 503. A file that was fetched but cannot be a download of its kind (unreadable, a full file for a forced
  pick, too short for a full one) → 422; an edition with only premade renditions → 409. The full list is the HTTP
  contract in `foundation-plan.md`.
- Tests: service unit tests with mocked collaborators; slice tests incl. 401, missing header, foreign `fileId`,
  quota; fixture entries; contract.

### 4c · Picker

- A `useDialog` dialog listing results; choosing one downloads, closes and the row leaves; errors inline. Wired to
  the foreign tab, the forced "Yes…" and To do. Second click while downloading does nothing; closing mid-request
  ignores the late answer.
- **4c decision (built):** `components/SubtitlePicker.jsx` is a `useDialog` dialog (the Edit editions frame and error
  banner, never amber) hosted by the layout like Fix match and Edit editions: `openSubtitlePicker({ film, editionId,
  kind }, opener, { onPicked })` is in the Admin context, `picking` in `useAdminShell`, and the path changing closes it.
  The search and download state is `state/useSubtitlePicker.js` (the search is `useResource`, so a newer one aborts an
  older and its late answer is ignored); the wording of a result and of a failure is `state/subtitlePicker.js`
  (the server's `$.error` text for every refusal, else the 429 words, else one plain line per phase).
  - **Edition: a backend shape change.** Neither need list had an edition id. `FullNeedFilmDto` and `FilmNeedDto` gain
    `editionId` and `editionName`. Full rows: the edition that makes the film needed, by `FullNeed.pickerIndex` (the first
    present edition whose audio label is not English, else the first with no label, else the first; one rule with
    `FullNeed.isForeign`, which `decide` uses too), so a film with an English theatrical cut and a French director's cut
    is searched for the French cut. Forced rows: the film's first present edition (lowest id, part 1 present), only while
    `NEEDED` with no forced subtitle served (one `findAudioRows` query for those films), else null. `editionName` is
    that edition's name only when the film has more than one present edition (the dialog title then reads "Rashomon
    (1950) · Director's Cut"), else null. A null `editionId` means nothing to search and the button is left out.
    Foundation plan, contract tests and fixtures updated.
  - **Buttons.** Foreign rows: "Search for English subtitles…" (AR1's hint) or, for unlabelled audio with a known
    language, "It's in {language} — find subtitles", then "Add my own file…" and "It's English". Forced rows:
    "Find forced subtitles…" then "No foreign scenes". To do's English-subtitles and forced rows get the picker button
    only (the other choices stay in Subtitles). **Departure from the README and AR1, stated plainly:** the forced button
    is "Find forced subtitles…", not "Yes, find forced subtitles…", and it does not save `NEEDED` first. Q3's tab and
    To do group list only films that are already `NEEDED`, so there is no question to answer yes to and the save would
    change nothing.
  - **Not built: "Search again…" / "Use it anyway"** for a distrusted download: still no trust in the API (see 4a).
  - **A result row** shows what the DTO has: the release name (else the file name, else "File {id}"; long names wrap),
    downloads, fps, "Trusted uploader", "Hearing impaired", the upload year; the footer says how many more files the
    edition may be given. Results keep the server's order; nothing is ranked or filtered in the browser.
  - **One download at a time.** The buttons are `aria-disabled` (still focusable) while one runs and after one
    succeeded; the hook guards with a ref, so two clicks in one frame send one POST. A failure frees the picker.
  - **Closing mid-request (decided):** the POST is not aborted (the server may still save the file). A late failure is not shown (nobody is looking); a late success still calls `onDownloaded(answer, { open: false })`, which the layout turns
    into `markChanged`, so the lists read again and show the server's truth (a row leaves only because the server's list
    says so). The layout closes only the picker that is still the open
    one; the follow-up (`onPicked`, which To do uses to arm the focus fallback) runs either way, since the row leaves
    either way. A file that arrives during the 150 ms fade-out is reported at once (the dialog remembers it was
    dismissed), so the unmount that ends the fade cannot swallow it.
  - **Row leaving and focus.** A success closes the dialog, calls `markChanged` (summary, rail, both Subtitles lists
    and the To do lists read again) and the row leaves with the re-read, not before. Focus returns to the opener when the
    dialog closes; when the opener then leaves with its row, the Subtitles panel takes focus (its existing rule, now
    also set when a picker opens) and To do moves focus to the section title (`useFallbackFocus` now watches the rows on
    screen, not the attention list, so it runs when a subtitle row leaves).
  - **Dev mock:** edition ids on the fixture rows; a download settles the film whose row names the edition; the
    lowest-ranked forced file (5013) answers 429 and the second full file (6011) answers 502; every fixture refusal now
    carries its text in the body, as the API does.
  - **Copy** is in `adminCopy.js` `subtitlePicker`, all "planner, 4c" except the 429 text (the server's): the eyebrow
    and lead per kind, "Searching OpenSubtitles…", "Couldn't search OpenSubtitles. Try again.", "Couldn't download that
    file. Try again.", "Try again", "OpenSubtitles has no files for this film yet.", "OpenSubtitles results", "Use this
    file" / "Downloading…", "Cancel", "Close", "File {id}", the detail words ("{n} downloads", "{fps} fps", "Trusted
    uploader", "Hearing impaired", "Uploaded {year}") and "{n} more files can be added for this edition".

### 5a · Playback: now playing, chart, per-minute view

Split into **5a-1** (the first two bullets) and **5a-2** (the rest).

- Now playing from the summary's `playingNow`, "Stop this stream" + hint where an HLS session exists.
- The player reports a touch hint (`maxTouchPoints > 1` with a Macintosh UA) so an iPad isn't named a Mac.
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
- **5b decision (built):** no backend change. Playback is now Now playing, How films played, Playback log, Conversion,
  Remembered networks, each once; the old "Transcoding" card (its live-session list duplicated Now playing's Stop) and
  "Playback health" card (and `usePlaybackHealth`, `adminApi.playbackHealth`, the "Full record" link) are gone.
  **Log:** `usePlaybackLog` reads `GET /api/admin/playback-health?…&page=n&size=50` and nothing else; "Films only" sends
  `minWatchedMs=180000` (`FILM_SESSION_MIN_MS`), "With problems" `problems=true`, All only the paging. The browser never
  filters; a filter switch aborts the older request and ignores a late answer; "Show 50 more" reads page `pages` and
  appends, skipping a row id already shown (a new play pushes rows down a page); it stops once the pages read cover `total` (`pages` × 50 ≥ `total`; decided from the page offset, not the rows
  returned: the server leaves an unreadable stored report out of a page but counts it in `total`, and a new play pushes
  rows down a page, so the shown count can sit under `total`; a failed "show more" does not count as a page read). The
  "browsing" pill is `watchedMs < FILM_SESSION_MIN_MS` (the one rule, `isBrowsing`). Details are four lines of words from
  `state/playbackLog.js` (`reportDetails`: Player, Network, Quality, Interruptions, reusing `healthFormat`'s
  `speedText`, `rungParts`, `stallDuration`); "Copy raw report" copies the **list route's** report (it has no
  `minutes`, which only the one-record route returns) as indented JSON, and a toast ("Copied", or an honest failure
  line) comes from the layout's existing toast (`showToast` in the Admin context). The table is a real `<table>`; at 600px
  and below CSS stacks each row into a card (film and date on top, the labelled cells under it, the button full width).
  **Conversion** shows the encoder with "ready" (server found an encoder; "Unavailable" otherwise; the server reports
  no other status), "Seek index" ("412 of 538 films") and "Stream cache" (the old card's figure). **Omitted, no source
  in the API:** "Pre-made versions" (14 films · 38 GB of 100 GB) and the "VideoToolbox" encoder name (the server knows
  only `vaapi`, `software` and `unavailable`). **Remembered networks** are this browser's `localStorage`
  (`state/networks.js`), not an API: there is no route, so "Forget" calls `forgetNetwork`. They hold the operator and
  the answer (Home is never stored), so each row reads "Train · Example Rail", **without** AR4's "starts at {quality} ·
  seen {ago}" (not stored; the start quality is measured each time). A failed forget (storage refused) keeps the row
  and says so inline in the stall colour; success moves focus to the card's heading. The owner's Q4 line is shown
  under the list only while there are rows. **Invented copy (planner, 5b):** the networks subtitle "This browser
  remembers where you said you were watching" (AR4's "starts at the quality that worked last time" is untrue), "No
  networks remembered yet.", "Couldn’t forget that network. Try again.", the empty lines per filter, the load, "show
  more" and copy-failure messages, "Stream cache", "ready", the table and filter group labels, and the Details
  button's accessible name ("Details: Heat, 10 Nov 19:43").

### 6 · Library tidy-up

- Scan card: "Last scan {ago} · took {duration}", count tiles (protected / needs conversion / unreadable tiles link
  to a plain list), "Scan library now" + hint, progress replaces the button while scanning; grouping report as
  `<details>` "What the last scan changed · {n} split, {n} joined, {n} flagged", paginated at 25.
- Ratings line + "Refresh all ratings". Import from Plex: key-value block, "Import from Plex again", "Try connecting
  to Plex" when unreachable, "Last report" behind a quiet button.
- Cast & crew card: "{n} of {total} films have credits" (new backend count), "Import credits from Plex" when Plex is
  configured. The credits-dataset control only per Q1.
- **6 decision (built):** `AdminLibrary.jsx` composes four cards in order (`LibraryScanCard` `#scan`,
  `LibraryRatingsCard`, `LibraryPlexCard` `#plex-import`, `LibraryCastCard` `#cast-crew`, the count only); the grouping report is
  `LibraryChanges`, a `<details>` inside the scan card, 25 per page ("Show {n} more"). The Running/Idle pill is gone
  (the header line and the progress take its place). The three tile links go to `/admin/library/files/{protected,
  needs-conversion,unreadable}` (`AdminLibraryFiles`, `FILE_LISTS` in `state/adminRoutes.js`, also bare on phone):
  the existing `GET /api/admin/attention` filtered by `kind`, so no new list route. The new backend count is
  `counts.filmsWithCredits` on `GET /api/admin/scan` (`FilmRepository.findIdsWithCredits`, intersected with the
  browsable films, so it never exceeds `counts.films`). **Departures:** (0) "Import credits from Plex" stays in the Import from Plex card, below the import: it imports end-credits START markers, not cast and crew, so under "Cast & crew · 530 of 538" it would read as the way to raise the count (the same false premise as Q1). (0b) "What the last scan changed" is the grouping lines logged from the last finished scan's start to its finish, uncapped (`GET /api/admin/grouping-report` no longer returns the newest 20 of every writer), so the label is true and the 25-row paging is real. (0c) A failed first read of the Plex status shows the card with an inline error and Try again. (0d) The dev mock serves Plex through `?plex=unreachable|importing|none`. (1) no "Download TMDB credits" (Q1). (2) The
  board's hints for Ratings, Import from Plex and Import credits are replaced by 07's and the component's own:
  Plex's credits markers say where the end credits start, not who acted, so "Fills in actors and directors from Plex"
  would be false. (3) The board's server name ("dockermacmini · reachable") is not shown: `GET /api/admin/plex`
  carries no host, so the line is "Plex server: Reachable". (4) The header shows only the last scan's time and
  duration; "2 new, 0 missing" were already tiles. Invented copy ("planner, 6" in `adminCopy.js`): "Not scanned yet",
  "Couldn’t load what the last scan changed.", "Show {n} more", "IMDb and Rotten Tomatoes · not fetched yet", "Not
  imported yet", "Nothing yet", "Not reachable", "Importing", "No films in the library yet", "{n} of 1 film has
  credits", "‹ Library", "Nothing here.", "Couldn’t load these files.", "The last scan changed nothing.", and the list titles "Protected files", "Files
  that need converting", "Files that could not be read" (`FILE_LISTS`).

### 7 · Settings

- "Continue watching starts after" (existing, 0–30, blur or Enter); switches per Q2. Second card "Signed in on this
  browser" + "Sign out" + "Signing out here doesn't affect other devices." TMDB credit at the bottom.
- **7 decision (built):** the section already existed from phase 1, so 7 restyles it to AR5 (label and description on the
  left, control on the right, one column on a phone) and moves its copy to `settingsPage`. Left out of AR5: "Prepare
  films in Continue watching" and "Show missing films in collections" (Q2, not settings) and "Download subtitles
  automatically" (the README allows it only when the scheduler has an on/off, and none exists), and "Since 2 Nov." in
  the sign-out description (no sign-in date is kept). Kept beyond AR5: the "N films currently in progress" line, and the
  TMDB wordmark beside the credit. A failed save now shows the server's own `error` text when it sent one. Invented copy
  ("planner, 7"): the Continue watching region name, "Loading the settings", "Couldn’t load the settings.".

### 8a / 8b · Per-file tools leave Admin

- 8a: Film detail "Subtitles → Fix…" ("Use my file instead", "Add my own file…", "Preview lines", "Delete this
  download", "Let CineFilm decide"), and the player's **"Subtitles are wrong"** (deletes the download and blocks it,
  `DELETE /api/admin/subtitles/{id}` + `ForcedDownloadBlock`), so a bad download can still be reported.
- 8b: player "Earlier" / "Later" (and reset) on the existing offset route; Admin's "Show all subtitle files" becomes
  read-only. "Looks right" goes only here, after 8a.
- **8a decision (built):** one small backend change (review fix): `ForcedSubtitleDto` and `FullSubtitleDto` gain
  `sidecarIds` (the ids of the subtitle rows they are made of, the same ids the Admin list gives), because their
  `version` is a hash and does not identify a row. "Subtitles are wrong" deletes only rows whose id the film showed; if
  another download plays by now it deletes nothing and says so. The rest needed no backend change.
  `SubtitleAdminService.remove` already writes the `ForcedDownloadBlock`
  (edition, IMDb id, OpenSubtitles file id, kind) before it deletes the row, so `DELETE /api/admin/subtitles/{id}` is
  the whole of "Subtitles are wrong" (and answers 400 for an owner's file).
  - **Film detail.** A quiet "Fix…" (`aria-label` "Fix subtitles…") sits under the Subtitles field, also for a film
    with no subtitles. It opens `components/FilmSubtitlesFix.jsx`, a `useDialog` dialog (the Edit editions classes:
    focus trap, Escape, focus back on the opener, full-screen sheet on a phone). It reads `GET /api/admin/subtitles`
    through Admin's own `useSubtitles` (one copy of the rules and of the one-request guard) and lists the film's rows
    (`state/subtitleFix.js` `filmFiles`, over `filterSidecars`). Per file, `fileTools` decides: "Use my file instead"
    (an owner file a download replaced, not yet chosen; the `verify` route), "Let CineFilm decide" (`ownerAttached`;
    `detach`), "Preview lines" (`cues`, via the shared `SubtitleCueList`), "Delete this download" (downloads only;
    `RemoveDownload` with an inline confirm and the 4a focus rules). Panel level: "Add my own file…" is 4a's
    `AddOwnFile` scoped to the film. Each tool has its 07 hint under it (`ActionWithHint`). A change calls the page's
    reload, even if it finishes after the dialog was closed. **Departures:** "Attach to… / Move to…" is not a per-file
    button here ("Add my own file…" is the film-scoped attach, as AR7 says); "Looks right" and timing are not here
    (the plan says "Looks right" goes nowhere; timing is 8b's). Admin keeps its own words ("Show cues", "Remove
    download") until 8b; `SubtitleCueList` and `RemoveDownload` were lifted out of `SubtitlesPanel` with those words as
    the default so both screens share one copy.
  - **Player.** In the subtitle column of the Audio and subtitles panel, a "Subtitles are wrong" `menuitem` (a quiet
    action under a hairline, never amber) while `shownDownloadKind` says a download is on screen: the player's own
    `activeSubtitleKey` picks what shows, and the edition's `source` (`DOWNLOAD`) says it is a download, so the owner's
    files, embedded streams and Off-with-nothing never get it. On click `useSubtitleWrong` reads the list (so the row
    id is never stale), takes the rows of this film, edition and kind that are `served` downloads, and deletes them.
    **What the player does after (chosen):** the removed kind is left out of the viewing locally (its `<track>`
    unmounts; the film is not read again under a playing viewing); a removed full subtitle falls back to Off, where
    the forced lines show as they would on their own; a removed forced one lets the embedded forced stream (if any) show.
    A toast says "Subtitle download removed. CineFilm won't use it again." Nothing about the playback is touched (no
    session, seek, reload or source change), and the saved subtitle preference is left alone. Not built: if the owner's
    own file had been beaten by the download, it only plays after the next film load.
  - **Copy** is `state/subtitleFixCopy.js` (the Film and player words; `adminCopy.js` is Admin's and stays out of the
    other phases' way). The five renamed buttons and their hints are 07's. Planner, 8a: "Fix…"'s spoken name "Fix
    subtitles…", the dialog eyebrow "Fix subtitles", its lead "The subtitle files CineFilm has for this film. Timing is
    changed while you watch.", "Loading the subtitle files", "Couldn't load this film's subtitle files.", "Couldn't
    refresh the subtitle files.", "CineFilm has no subtitle file for this film yet.", "Subtitle files for this film",
    "Hide lines", "Delete download", "Your file", "Forced", "Subtitles are wrong", "Removing…", "Subtitle download
    removed. CineFilm won't use it again.", "Couldn't find that download to remove.", "Couldn't remove the download.
    Try again.", "This subtitle has changed since the film started. Reload to see the new one."
  - **Dev mock:** `detach`, `verify` (POST and DELETE) and `cues` routes added to `fixtureServer.js` and the default
    `mockApi.jsx` answers; fixture refusals carry `{ error }` in the body.
- **8b-2 decision (built):** Admin's file list is read-only and "Looks right" is in the Fix… panel. No backend change.
  - **"Looks right" rule** (`state/subtitleFix.js` `fileTools`, moved from Admin's `SubtitlesPanel`, where it lived in
    the cue details): a **forced** file (`!isFull`) that sits under a film (`filmId != null`), is not yet checked and is
    not an owner's file a download replaced offers `looksRight` (the `verify` route, `adminApi.verifySubtitle`); once
    checked it offers `undoCheck` ("You checked this: the lines look right." and "Undo", `unverifySubtitle`), as Admin
    did. A full subtitle never has either (it has no checks); an unattached file is not in the film's list anyway.
    **Split with "Use my file instead":** a replaced, unchecked owner file has `useMine` and never `looksRight`
    (Admin hid its own "Looks right" there for the same reason); once chosen it reads as checked, with the Undo, and
    undoing brings "Use my file instead" back. A download (forced, unchecked) does get "Looks right", as in Admin. The
    README puts it in the panel; AR7 says "gone"; the README and the plan line were followed. It uses `ActionWithHint`,
    the dialog's notice (focus there only when focus was lost) and `useSubtitles`' one-request guard.
  - **Read-only list** (`SubtitlesPanel`): reads `GET /api/admin/subtitles` with `useResource` (`version` still makes it
    read again), paginated at 50 with the same chips, grouping and states. Each file shows what it was: title or
    release, the Full / Checked / Playing tags, the served line, where it sits, the parse report, the new "Timing
    shifted +0.25 s" line (a file under a film whose offset is not 0; the old row showed the offset only inside the cue
    details), the OpenSubtitles check, the timing fit and the warnings. No button but the chips and "Show more". The
    note ("Subtitles → Fix… … or in the player") still fits and is unchanged.
  - **Deleted:** `AttachPicker.jsx`, `useAttachPicker.js`, the `Timing`/`Check`/attach/cues/remove parts of
    `SubtitlesPanel`, `useSubtitles`' `attach`, `nudge` and `resetTiming`, `adminRemoveLabels`, `RemoveDownload`'s
    `labels` and `hint` props (one wording now: `fixRemoveLabels` and `buttons.deleteDownload.hint`), the CSS only they
    used (`.subs__actions`, `.subs__timing`, `.subs__attach`, `.admin__item-actions`) and their tests. Kept:
    `nudgedOffset`/`MAX_OFFSET_MS`/`formatOffset` (shared with the player), `adminApi.setSubtitleOffset` (the
    player's timing uses it) and `attachSubtitle` (only `AddOwnFile` uses it), the fixture routes.
  - **Attaching an owner's forced file (review fix):** Admin's "Attach to… / Move to…" worked on every owner file,
    forced ones included, but "Add my own file…" listed only full files, so a loose or wrongly placed forced file had
    nowhere to go. In the Fix dialog only (`AddOwnFile allKinds` → `useFilmFilePicker(filmId, { allKinds })` →
    `fileChoices(files, filmId, true)`), it now offers the owner's files of both kinds (never downloads or missing files;
    this film's own are offered too, see below), unattached first, each as "{name} · Full|Forced · not attached to a film | on {film}"
    (existing `attachedTo`/`unattached` copy; "Full"/"Forced" are 8a's tag words). Admin's To do / full-need picker
    keeps full files only. Server: `SubtitleAdminService.attachLocked` takes any sidecar and only `retarget`s it to the
    edition's media file (and marks it owner-attached); the file's kind comes from its name and is untouched, so a
    forced file attached from a film's Fix dialog plays as that edition's forced subtitle unless another forced file
    already holds that place, in which case neither plays (a conflict) until one is moved. For that, `allKinds` also
    offers this film's own owner files, "on this film · {edition}, part {n}" (`subtitlesPage.addFile.onThisFilm`,
    planner, 8b-2), re-targeted by the same attach route; the conflict warning now reads "Move one of them with Add my
    own file… in the film's Fix… panel." (planner, 8b-2). The Undo notice follows the server's answer to the
    undo (`DELETE …/verify` returns the row recomputed): "The download plays again." when `replacedByDownload` is true
    in it, else "The check is taken away." (after "Use my file instead" the server serves the file, so the row no
    longer reads as replaced, and a deleted download is not brought back). In the picker, "Attach" stays inert, with
    "That file is already there. Pick another edition or part." (planner, 8b-2), when the target is exactly the
    file's current film, edition and part, since the server clears a file's check on any attach. With `allKinds` an
    empty list reads "None of your subtitle files are waiting. …" (planner, 8b-2); the To do path keeps its line.
    **Accepted losses (spec-sanctioned):** timing of a file that is not playing (the player nudges only the file on
    screen) and previewing the lines of an unattached file before attaching it (Preview lines is per file of the
    film). Dev mocks (`fixtureServer.js`, `mockApi.jsx`) now answer verify as the server does (a checked file stops
    reading as replaced; undoing gives it back) and clear `verified` on an attach or a retiming.
    `kindTag` (`state/subtitleFix.js`) is the one Full/Forced word. The dialog's tests send the
    attach for a forced file under another film end to end through the mocks. "Undo" after "Use my file instead" says
    "The download plays again." (`subtitleCheck.undoneReplaced`). `filmLabel` was dead and is deleted. `NUDGES` was deleted and then restored on the merge with 8b-1, which
    uses it (with `nudgedOffset`, `formatOffset`, `MAX_OFFSET_MS` and `adminApi.setSubtitleOffset`).
  - **Invented copy (planner, 8b-2):** `subtitleCheck.undoneReplaced` "The download plays again.",
    `subtitleCheck.hint` "Marks the lines as checked: you’ve seen they’re right.",
    `subtitleCheck.done` "Marked as checked.", `subtitleCheck.undone` "The check is taken away." (in
    `subtitleFixCopy.js`, its own block), and "Timing shifted {offset}" (`subtitlesPage.files.offset`). "Looks right",
    "Undo" and "You checked this: the lines look right." are Admin's existing words.

- **8b-1 decision (built):** player "Earlier" / "Later" / "Reset timing"; Admin keeps its own nudges until 8b-2 removes
  them. One small backend change (review fix): `ForcedSubtitleDto` and `FullSubtitleDto` gain `offsetsMs` (the offset
  of each row in `sidecarIds`, same order), built beside `sidecarIds` from the same pick, so the player needs no
  `GET /api/admin/subtitles` (every row of every film: too heavy for a weak link).
  - **Where.** In the subtitle column of the Audio and subtitles panel, a `role="group"` "Subtitle timing" of three
    `menuitem` buttons (quiet, never amber, `aria-disabled` while saving so focus stays) between the choices and
    "Subtitles are wrong", with a `role="status"` readout (always rendered, empty while unknown) and a `menu__problem`
    alert line. Offered while `shownFileKind` (`state/subtitleFix.js`) says a subtitle *file* shows (owner file or
    download, forced or full: `activeSubtitleKey` decides, never an embedded stream or Off-with-nothing). The rows and
    their offsets are the film DTO's `sidecarIds` / `offsetsMs` for that kind.
  - **Step and words.** One step, Admin's finer nudge: `NUDGES` "earlier-025" / "later-025" (0.25 s), reused with its
    spoken names; the clamp is Admin's `nudgedOffset` and the readout its `formatOffset`. The rest is `subtitleTiming`
    in `subtitleFixCopy.js`, all "planner, 8b-1": "Subtitle timing", "Timing: …", "differs by part", "Saving…",
    "Couldn't change the timing. Try again.", "Couldn't load the new timing. The old timing stays."; "This subtitle has
    changed since the film started. Reload to see the new one." repeats 8a's line.
  - **State.** `state/useSubtitleTiming.js` (lives in `PlayerView`, so an answer outlives the panel) over the pure
    `state/subtitleTiming.js`. It reads nothing: the readout and the next press work from the film's `offsetsMs` and
    from each PUT answer's `offsetMs` (kept per file). A press sends one absolute
    `PUT /api/admin/subtitles/{id}/offset` per row that moves, one after the other. **Departure from the brief's "the
    same new offset for each row":** each row moves by the step from *its own* offset (a multi-part film's rows can
    differ, the timing check sets them per file) and Reset sets every row to 0; the readout says "differs by part".
  - **Async.** One press per file at a time (a ref): the buttons are `aria-disabled` with the readout "Saving…". An
    answer belongs to the file it was for: its problem is dropped when another file shows, forgotten when the shown file
    changes, and nothing is set or told after the player unmounts. A press that fails half way still loads the new
    timing (rows did change) and shows the offsets that did.
  - **A nudge can change what is served (found in review).** `SubtitleAdminService.changed()` clears the owner's
    "Looks right" on any offset change, deliberately (`forced-subtitles-plan.md` "A nudge, an attach, and any change to a
    file on disk clear the owner's check"; `SubtitleSidecar.clearVerification` "it changed, moved or was nudged"), and
    the offset is part of the served `version`; a proven file can drop to unchecked and `ForcedChoice` may then serve a
    proven download or the embedded track instead. **Not changed on the backend (owner decision needed).** The player
    guards instead: the PUT answer's `served` is computed after the change; if any changed row is no longer served the
    track is not touched, and the viewer is told the subtitle changed.
  - **Loading the new timing without touching playback (chosen).** The PUT answers a `SidecarDto` whose `version` is
    when the row last changed; the token is each answered row's `version_offsetMs`. The player mounts a second
    `<track>` of that kind beside the showing one, `hidden`, with `?v=<film version>.<token>` (the server ignores `v`),
    and swaps once its `load` event fires: the new one becomes the only `showing` track and the old element unmounts. On
    `error` the new one is dropped, the old one keeps showing and the viewer is told. A newer change replaces a pending
    one. A kind that stops showing (the viewer chose another subtitle) keeps both its tracks mounted, both
    `disabled`: its pending one loads hidden once the kind shows again, or finishes a fetch it had already started, so its
    load or error may arrive while nobody listens. The 20 s timer runs only while the kind shows. A press with nothing to
    send (Reset at 0) while the screen shows other timing than the server (a failed swap) retries the last token's track
    and leaves the failed-load line alone otherwise. When the kind shows
    again the old one is `showing` and the pending one `hidden` again, and the listener reads the pending element's
    `readyState` on attaching (2 loaded: it takes over; 3 error: dropped with the line), else waits for its events. A
    pending track that neither loads nor errors within 20 s is dropped (old timing stays, line shown). The mode effect
    is a layout effect, so a swap never shows a frame without lines. (`state/subtitleTrackSwap.js`: the reducer and the
    track modes.) **The readout describes the timing on screen:** the offsets of the track that shows (the film's own
    until a swap; kept in the swap state with each token), "Saving…" while the new one loads; the next press still works
    from the server's offsets. After a `served: false` answer the row loop stops at that row, no later row is sent, and
    that file's timing buttons stay `aria-disabled` for the rest of the viewing, the subtitle-changed line saying why.
    A change clears the owner's "Looks right" by design, so another file may play after the next film load. Only
    `<track>` elements change: no session, seek, `load()`, video `src`, `useStream` or hls code; the film is not read
    again. Elements are replaced rather than their `src` changed because a `src` change on a live track is unreliable in
    Safari. A load that never fires or errors leaves the pending track hidden until the next change replaces it.
  - **Tests.** `subtitleTiming.test.js`, `subtitleTrackSwap.test.js`, `useSubtitleTiming.test.jsx`,
    `PlayerMenus.test.jsx` (also the arrow keys from a disabled item), `subtitleFix.test.js`, `fixtureServer.test.js`,
    `screens/PlayerSubtitleTiming.test.jsx` (no list read, absolute offsets per row, gating, the hidden-then-swap load
    with `load`/`error` events dispatched, the video's `src`, `currentTime`, `load()`, `pause()` and `play()`
    untouched, `served: false`, double click, closed panel, late answer after switching, error then recovery), and the
    backend DTO unit and API tests for `offsetsMs`.

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
