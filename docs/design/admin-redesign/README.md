# Task: redesign Admin into sections, with "To do" first

Admin (`frontend/src/screens/Admin.jsx`) has grown into one long page: ten stacked cards in `.admin__grid`, three of them unbounded lists, with status, actions, settings, history and diagnostics all mixed together. I can't find things, and I often can't tell what a button does.

Split it into **five sections, each with its own route**, and open on a short **To do** list of only the things CineFilm can't sort out by itself.

Facts to design around:
- I'm the only user, and I'm English.
- The only subtitles I care about are **English full subtitles for films not in English** and **English forced subtitles for English films with foreign-language scenes**.
- A playback counts as a film session from **3 minutes watched**. Anything shorter is me browsing or testing.

Plan this as a multi-phase change with a plan doc in `docs/engineering/admin-redesign-plan.md`, following CLAUDE.md. Suggested PRs are at the end.

## What's in this bundle

| File | Shows |
|---|---|
| `screens/01-todo.png` | `/admin`, the To do landing page: status strip plus four bounded groups |
| `screens/02-subtitles.png` | `/admin/subtitles`: two tabs, a list of only the unresolved films, and counts for everything else |
| `screens/03-library.png` | `/admin/library`: scan, ratings, Plex import, cast & crew |
| `screens/04-playback.png` | `/admin/playback`: now playing, the "How films played" chart with a per-minute view of one session, the playback log, conversion and remembered networks |
| `screens/05-settings.png` | `/admin/settings` |
| `screens/06-iphone-todo.png` | Phone layout (≤ 600px) |
| `screens/07-buttons-renamed.png` | Every current button → its new label → what it does. **This table is the copy spec.** |
| `reference/AR*.dc.html` | Static source of each board, with exact values and copy. Sample data in a design-tool format: read it for values, then build idiomatic React with the existing tokens and components. Don't port the format. |

## Navigation and routes
- **Routes:** `/admin` (To do), `/admin/subtitles`, `/admin/library`, `/admin/playback`, `/admin/settings`. Use nested routes under the existing `<Route path="admin">` in `App.jsx`.
- **Desktop:** a sticky left rail (220px).
  - The page title "Admin" in Instrument Serif 40px.
  - One 44px link per section. The current one gets the `#15120F` background and a 2px amber left bar; that's the "current-nav" amber use.
  - Counts in a mono pill: To do 9, Subtitles 6, Playback "1 live" while something streams.
- **Phone:** "‹ Home", the "Admin" title, then the sections as a horizontal tab row that scrolls. No auto-scrolling.
- **Old links:** `/admin#library-scan` (from `StateMessages.jsx`) redirects to `/admin/library#scan` and scrolls there. The header gear and the `FilmDetail.jsx` link still go to `/admin`.
- **Rail counts:** each count comes from one cheap call. The To do count is the sum of the To do groups. Don't fetch every section's data to draw the rail.
- **Fix match and Edit editions** stay modal dialogs, opened from whichever section shows the row, with focus returning to the opener as now.

## Button rule (applies everywhere in Admin)
- A button says **what it does to what**: "Find the film…", not "Fix match".
- **"…"** means it opens something first. No dots means it acts straight away.
- Where the result isn't obvious, a **visible hint line** sits under the button: 12px, `#8A8173`, right-aligned in rows and left-aligned on phone. It's not a tooltip, because it has to work on the phone. Link it with `aria-describedby`.
- **Amber stays rare.** In Admin, only "Scan library now" is primary. Row actions are secondary pills (`#1A1713` fill, `#4A4339` border) or quiet text buttons.
- Use `07-buttons-renamed.png` for every label. Put the label and hint strings in a `state/` module (no JSX) so other clients reuse them.

## 1 · To do (`/admin`)
- **Intro line:** "Only things CineFilm can't sort out by itself. Everything else carries on automatically. When this list is empty, there's nothing to do."
- **Status strip:** three small cards.
  - **Library:** "{n} films · scanned {ago}".
  - **Playback:** "Last 30 days: {smooth} of {n} films played smoothly". This counts only sessions of 3 minutes or more.
  - **Playing now:** "{film} · {device} · {quality}". When nothing is playing, show "Nothing playing".
  - Each card links to its section.
- **Groups, in this order.** Each is a card with a heading and a muted "why" line, up to **5 rows**, and a footer reading "Showing 5 of {n}" with a link to "Show all {n}" or to the section.
  1. **Pick the right film**
     - From `attention` with kind `needs-match`.
     - When there's a confident guess, the row offers "Yes, it's {Title} ({year})" (confirms through the existing match POST) plus a quiet "Find the film…".
     - Otherwise it offers "Find the film…" (the existing Fix match dialog).
  2. **Sort out several files**
     - Kinds `multiple-files` and `parts-mismatch`.
     - "Sort out editions…" opens the existing Edit editions dialog.
     - The row shows the scanner's suggestion line.
  3. **English subtitles missing:** the first rows of the Subtitles "Foreign-language films" tab, with the same actions.
  4. **Forced subtitles: is there foreign dialogue?:** the first rows of the other tab, with the same actions.
- **Hide a group with no rows.** When every group is empty, show a quiet "Nothing to do." with the status strip still above it.
- **Kinds `protected`, `needs-conversion` and `unreadable` are not To do items.** There's nothing to click. They appear as counts in the Library scan tiles ("Can't play (protected)"), and the tile links to a plain list.

## 2 · Subtitles (`/admin/subtitles`)
- **Lead copy:** see the screen; it explains the two kinds in one sentence.
- **Tabs:**
  - "Foreign-language films {n}": rows from `subtitles/full-need` that are needed and have no full subtitle playing.
  - "English films with foreign scenes {n}": rows from `forced-subtitles/films` that are needed with no forced subtitle, or unsure after the online check has finished.
- **What the list holds:** only films where automatic failed or couldn't decide.
  - Films still being checked aren't listed. They're counted ("2 being checked online now").
  - The old filter chips go.
- **Row:** poster thumb, title · year, then a reason line in plain words, built from the existing `reasonText`, `fullNeedReason` and the language names. Actions:
  - **Foreign-language tab:**
    - "Search for English subtitles…": opens a picker of OpenSubtitles results for this film; choosing one downloads it and it plays.
    - "Add my own file…": the existing attach flow, scoped to this film.
    - When a download exists that the checker distrusts: "Use it anyway" plus "Search again…".
    - When the audio is unlabelled and CineFilm guessed: "It's in {language} — find subtitles" and "It's English". "It's English" saves `NOT_NEEDED` as the owner's choice.
  - **Forced tab:**
    - "Yes, find forced subtitles…": saves `NEEDED`, then the same picker filtered to foreign-parts-only files.
    - "No foreign scenes": saves `NOT_NEEDED`. It's marked done and never asked again.
- **"Everything else" card:**
  - Counts of foreign films that have English subtitles, films in English, and films being checked now.
  - "Show the {n} films" (a read-only list) and a quiet "Show all subtitle files". The file list is diagnostic, paginated at 50.
  - A note pointing to the film page and the player for fixing a film's subtitles.
- **Moved out of Admin**, as a separate later PR:
  - Timing nudges and Reset timing → the player's subtitle panel ("Earlier" / "Later").
  - "Looks right", "Use my file", "Attach to…/Move to…", "Show cues" (→ "Preview lines"), "Remove download" (→ "Delete this download") and "Back to automatic" (→ "Let CineFilm decide") → a "Subtitles → Fix…" panel on Film detail.
  - Until that PR lands, keep them reachable from "Show all subtitle files", so nothing is lost meanwhile.
- **New backend, check first:**
  - Today OpenSubtitles runs from `OpenSubtitlesScheduler`, and there's no per-film interactive search. If that's right, add `GET /api/admin/films/{id}/opensubtitles?kind=full|forced` (ranked with `OpenSubtitlesRanking`) and `POST …/download`.
  - Respect the quota: on `OpenSubtitlesQuotaException`, show "OpenSubtitles daily limit reached — try again tomorrow."

## 3 · Library (`/admin/library`)
- **Library scan** (`id="scan"`):
  - Header: "Last scan {ago} · took {duration}".
  - Count tiles from `countTiles`.
  - **"Scan library now"** (amber, the page's only primary button), with the hint "Looks through your movie folders for new, changed and removed files. A few minutes; you can keep watching."
  - While scanning, show the existing progress line in place of the button.
  - The grouping report becomes a collapsed `<details>`: "What the last scan changed · {n} split, {n} joined, {n} flagged". Expanding it shows the existing list, paginated at 25.
  - The subtitle search line leaves this card. Its status belongs to Subtitles.
- **Ratings:** "IMDb and Rotten Tomatoes · updated {date}", plus "Refresh all ratings" with its hint.
- **Import from Plex:**
  - A key-value block: server and reachable state, and what was brought across.
  - "Import from Plex again" with its hint.
  - When Plex is unreachable: "Try connecting to Plex" (was "Check again").
  - The last report sits behind a quiet "Last report" button.
- **Cast & crew:**
  - "{n} of {total} films have credits".
  - "Import credits from Plex": the existing `PlexCreditsImport`.
  - "Download TMDB credits": wires the existing `credits/dataset` GET/start/stop, which has no UI today. Show progress and a "Stop" while it runs.

## 4 · Playback (`/admin/playback`)
- **Now playing:** the live transcoding sessions, each with "Stop this stream" and the hint "Ends playback on the {device}; it can resume from here". When nothing is playing: "Nothing playing."
- **How films played (new chart):**
  - **Data:** only playbacks with **`watchedMs ≥ 180000`**. Make that a named constant in `state/healthFormat.js` and mirror it server-side.
  - **Filters:** a segmented 30 days / 90 days control, and All / Home / Away / Train (from `report.environment`).
  - **Tiles:**
    - films watched
    - played without a stall
    - stalled, with a note when they cluster ("all on the train")
    - typical start (median `startupMs`)
    - % of time at Original or 1080p (from `rungMs`)
  - **Chart:** one column per session, oldest to newest.
    - The column is stacked by share of watched time per rung. Use neutral tones: Original `#EDE6DA`, 1080p `#BFB5A5`, 720p `#8E8576`, 480p or lower `#5E574C`. **No amber.**
    - Below the columns, a stall dot in `#F0876A`, sized by `stallMs`.
    - Under the dot, the environment letter (H / A / T).
    - A legend as shown. Never rely on colour alone: each column has an `aria-label` sentence, and the whole chart has a text summary.
  - **Selecting a column** (hover, click or keyboard) shows that session below:
    - film, date, device, environment, time watched
    - a per-minute strip from `report.minutes`: a speed line, a quality band underneath, and stall dots at their minute
    - the existing `describePlayback` line in mono
  - The latest session is selected by default.
  - **Backend:** the endpoint currently returns only `findTop20…`, while the store keeps 2,000 rows (`MAX_ROWS`). Add `GET /api/admin/playback-health?since={iso}&minWatchedMs=180000` returning the rows the chart needs, or a lean projection of them.
  - **Check the `minutes` tuple layout** (`[minute, kbps, ?, rungMs?, stalls?]`) in the player's reporter before relying on it, and document it in `foundation-plan.md`.
- **Playback log (the full audit):**
  - A table of every playback, newest first: Started, Film, Watched, Where (environment · device), Start, Stalls, Quality.
  - Rows under 3 minutes get a muted **"browsing"** pill.
  - Filters: All / Films only (3 min+) / With problems (stalls, reconnects, fell back, errors or timeouts).
  - Paginate 50 at a time from the same endpoint, without the minimum.
  - "Details" expands a readable two-column breakdown (Player, Network, Quality, Interruptions). **Never show the raw JSON on screen.**
  - "Copy raw report" copies the JSON to the clipboard and shows a "Copied" toast.
- **Conversion:** encoder and status, pre-made versions (count, storage used against the limit), seek index coverage.
- **Remembered networks:** one row each, showing name, starting quality and last seen, with a "Forget" button. Under the rows: "Forget makes the next playback on that network start at a safe quality and measure again."

## 5 · Settings (`/admin/settings`)
One card of label / description / control rows:
- **Continue watching starts after:** the existing minimum, 0–30 minutes, default 5. It still saves on blur or Enter.
- **Prepare films in Continue watching:** the existing switch.
- **Show missing films in collections:** the existing switch.
- **Download subtitles automatically:** add it only if the scheduler already has an on/off. Otherwise leave it out and note it in the plan.

A second card:
- "Signed in on this browser", plus "Sign out" with "Signing out here doesn't affect other devices."

The TMDB credit line moves to the bottom of Settings.

## Layout and tokens
- **Desktop:**
  - Content max 1360px with 40px gutters; grid `220px minmax(0,1fr)`, gap 48px.
  - Cards: `#15120F` with a 1px `#2A251F` border and 12px radius. Card header 18px × 22px padding. Rows `44px 1fr auto`.
- **Phone:**
  - 16px gutters.
  - Row actions stack full width under the text at 44px tall, with the hint left-aligned.
  - The playback log becomes stacked cards: film and date on top, then the mono line.
- **States:**
  - Static skeletons, no shimmer.
  - Each section loads on its own. A section that fails shows an inline error banner in the Edit editions error colours, never amber.
- **Focus ring everywhere:** `0 0 0 2px #0E0C0A, 0 0 0 4px #EDE6DA`.

## Done when
- The five routes work.
- `/admin#library-scan` lands on the scan.
- The rail and the phone tabs mark the current section.
- To do shows only actionable groups, at most 5 rows each, and the empty state works.
- Every Admin button matches `07` and has a hint where the table gives one.
- Subtitles lists only unresolved films, and "It's English" / "No foreign scenes" remove the row and stay removed after a reload.
- The chart shows only sessions of 3 minutes or more. Selecting a column shows its per-minute view. The log shows everything, with the browsing pill and readable details.
- The phone layout matches `06`.
- **Tests cover:**
  - the To do grouping and caps
  - hiding empty groups
  - the redirect from `#library-scan`
  - the 3-minute filter on both sides
  - the chart's data shaping (rung shares, stall sizing, environment filter) as pure `state/` functions
  - the log filters
  - "Copy raw report"
  - each subtitle owner choice round-tripping
  - focus returning after Fix match and Edit editions

## Suggested PRs (small, each green on its own)
1. **Shell:** nested routes, rail and tabs, the redirect. The existing panels move into their sections unchanged.
2. **Button copy and hints** across all panels: a `state/adminCopy.js` module plus the tests.
3. **To do** page and status strip.
4. **Subtitles:** the two tabs, owner choices and the "Everything else" card. Then the backend OpenSubtitles search and pick, and the picker UI.
5. **Playback:** the filtered health endpoint, the chart and per-minute view, and the playback log.
6. **Library tidy-up:** collapse the grouping report, add the TMDB credits dataset UI.
7. **Settings** page.
8. **Film detail "Subtitles → Fix…" and the player timing nudges,** then remove the old per-file tools from Admin.
