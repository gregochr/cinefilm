# Task: an Admin screen for managing Christmas films

In Christmas week, Home's weekly theme becomes "This week: Christmas". PRs #227 and #228 built the rule:
- `HolidayWeeks`: the Monday on or before 18 Dec, through Sunday.
- `WeeklyThemeSelector`: TMDB keyword `christmas`, at most 12 films (`MAX_FILMS`), falling back to the usual theme when fewer than 5 (`MIN_FILMS`). Sorted by year, then sort title.
- The owner's overrides were TMDB ids in a `HolidayOverrides` record set by the `CINEFILM_HOLIDAY_CHRISTMAS_INCLUDE` / `_EXCLUDE` environment variables; that mechanism was removed (see Deviations in the build). Exclude beats include, and include beats the keyword.

Editing environment variables and restarting isn't something I want to do in December. I need a screen to:
- see what the rule picks
- correct it
- be confident about what Home will show

My rule: Die Hard, Gremlins and Trading Places are Christmas films; films that merely mention Christmas are not.

Plan it in `docs/engineering/christmas-films-plan.md` (and update `holiday-themes-plan.md` and `foundation-plan.md`), following CLAUDE.md.

## What's in this bundle

| File | Shows |
|---|---|
| `screens/01-in-christmas-week.png` | The main view: status card with the 12-slot meter, keyword-fetch progress, preview of the Home row, the numbered list with the cut line after 12, and the undo toast |
| `screens/02-added-by-me-adding.png` | The "Added by me" tab with the search open: library results, one marked "Keywords not fetched yet" |
| `screens/03-left-out-by-me.png` | The "Left out by me" tab with "Put back" |
| `screens/04-states.png` | Loading (static skeleton), empty (keywords still being fetched), error with "Try again", and under 5 (falls back) |
| `screens/05-phone.png` | The phone layout |
| `screens/06-phone-adding.png` | Phone, full-screen search after adding a film |
| `reference/XM*.dc.html` | Static source with the exact values and copy. Sample data in a design-tool format: read it, then build idiomatic React with the existing tokens and components. Don't port the format. |

The titles and years are sample data. Which films TMDB actually tags will decide the real lists.

## Backend

### 1. Store the overrides in the database
- **Table:** add a table via a Flyway migration, e.g. `christmas_override(tmdb_id bigint primary key, choice varchar not null check (choice in ('INCLUDE','EXCLUDE')), created_at)`.
  - Key it on the TMDB id, as now, so a rematch or rescan doesn't lose a choice.
  - One row per film, so a film can't be both added and left out.
- **(Dropped: see Deviations in the build.) The environment variables become a one-time seed:** on start-up, if the table is empty and either variable has ids, insert them, with exclude winning a clash, and log it. After that the database is the truth. Say this in `operations.md`.
- **`WeeklyThemeSelector`** reads its overrides (`ChristmasOverrides`) from the table (a small service builds it), keeping the precedence and the `pick()` ordering exactly as they are. **The screen and Home must use the same code path.**

### 2. API (under `/api/admin/christmas`, signed-in only)
- **`GET`** returns:
  - **`week`:** `{ start, end }` of this year's Christmas week. Once that week has ended, return next year's.
  - **`limits`:** `{ max: 12, min: 5 }` from the selector's constants.
  - **`keywords`:** `{ fetched, total, running }` (films with a TMDB id; `keywordsFetchedAt` set; `KeywordsRefresher.isRunning()`).
  - **`inWeek`:** every film that counts, in the selector's order, **without the 12 limit**. Each has `{ filmId, tmdbId, title, year, posterUrl, reason: 'KEYWORD' | 'ADDED' }`. The client marks rows 13 and after as cut.
  - **`added`:** included films that TMDB doesn't tag.
  - **`leftOut`:** excluded films, with `taggedByTmdb` so the row can say "TMDB tags it “christmas”".
  - **`fallsBack`:** true when `inWeek` has fewer than `min`.
- **`PUT /api/admin/christmas/{tmdbId}`** with `{ choice: 'INCLUDE' | 'EXCLUDE' | null }`. `null` deletes the override, which is what both "Put back" and "Remove" do. Return the same body as `GET`, so the screen redraws from one response. **Undo** is just another `PUT` with the previous choice.
- **Library search:** reuse the existing search (the `searchIndex` / search work) if it can return poster, title, year and `tmdbId`. Each result also needs its Christmas state: `IN_KEYWORD`, `IN_ADDED`, `LEFT_OUT`, `NOT_IN`, or `KEYWORDS_PENDING` (has a TMDB id but no `keywordsFetchedAt`) and `NO_TMDB_ID`.
  - **A film with no TMDB id can't be added,** because overrides are by TMDB id. Show it with a muted "Needs a match first" and a "Find the film…" button that opens Fix match, instead of Add.
- **Edge case:** a film that's added *and* later gets tagged by TMDB shows as "TMDB keyword", and the redundant INCLUDE row is deleted on the next write (not at start-up). Leaving it out sets EXCLUDE.
- **409 from the scan lock:** if writes are refused while a scan runs, say "A scan is running — try again when it finishes" in the toast, and don't change the list.

### 3. Tests (backend)
- **(Dropped: no seed.) The seed:** an empty table with variables set gets seeded, a non-empty table is ignored, and a clash makes exclude win.
- **Precedence:** exclude beats include beats keyword.
- **Same order as Home:** the `GET` order is the same as the selector's on the same library, including the year/sort-title tie-break (e.g. two 1988 films).
- **Week dates:** across years, including the case where 18 Dec is a Monday.
- **`fallsBack`** at 4 and 5 films.
- **The `PUT`:** round-trip and undo.
- **The `NO_TMDB_ID` rule.**

## Frontend

### Where it lives
- **Route:** `/admin/christmas`, titled "Christmas films".
- **If the Admin redesign shell has landed** (left rail), add a rail item between Library and Playback.
- **If it hasn't,** add the route plus one plain card in the current Admin that links to it: "Christmas films · {n} will show in Christmas week".
- **The page header** is the amber eyebrow "Admin" over the 44px Instrument Serif title. This page has no big "Admin" heading in the rail; the eyebrow carries it.

### Layout (desktop; see `01`)
1. **Rule line,** in 15px `#D5CCBE`: "A film counts if TMDB tags it with the “christmas” keyword, unless you leave it out. You can also add any film yourself."
2. **Status card:**
   - **Text:**
     - "**{n} films** will show in Christmas week (Mon 14 Dec to Sun 20 Dec)."
     - Under it: "Home shows up to 12, oldest first. Under 5 and it falls back to the usual weekly theme."
     - Use proper dates from `week`. Say "1 film" for one.
   - **The 12-slot meter** on the right:
     - 12 small segments: filled amber for each place used, `#2A251F` when empty.
     - Then one hollow segment (`#4A4339` border) per film over 12.
     - Caption in mono: "12 places on Home" … "{k} over".
     - It's decorative (`aria-hidden`); the sentence carries the meaning.
   - **The keyword strip** across the bottom of the card, shown only while `fetched < total`:
     - A small amber progress fill, then "Keywords fetched for {fetched} of {total} films. The other {total − fetched} are on their way, so this list may still grow."
     - Poll every 30 s while `running`.
3. **Preview, inside the same card:**
   - The label "Preview · Home in Christmas week" with a "Hide preview" text button. Remember open or closed in `localStorage`, in a try/catch, and default to open.
   - Then **the real Home row title and caption**: "This week: Christmas" / "A new theme every Monday".
   - Then the first 12 posters at 2:3 in a 12-column grid.
   - **Reuse the Home row's poster component** so it really looks like Home. Lazy images, with the aspect ratio reserved.
   - When `fallsBack`, the preview instead reads "Christmas week will show the usual weekly theme — under 5 Christmas films."
4. **Controls row:**
   - **A segmented control:** "In Christmas week {n}", "Added by me {n}", "Left out by me {n}". It's 44px tall. It's tabs (`role="tablist"`), and arrow keys move between them.
   - **On the right, the search field** "Add a film" (340px wide, 44px tall).
5. **List card:**
   - **Rows** are 76px. Columns: number (mono, only in the "In" tab), poster 40×60, "Title · year", reason chip, one action button (≥ 44px tall, min 104px wide).
   - **The cut line** goes after row 12: "Home shows 12. These {k} are in, but won't fit. Leave out an older film to make room." Rows below it use the dimmed `#120F0C` background and `#8A8173` text, but still work.
   - **Chips (outline, 12px, 24px tall):**
     - "TMDB keyword": `#4A4339` border, `#A39A8B` text.
     - "Added by me": `rgba(237,230,218,.45)` border, `#EDE6DA` text.
     - "Left out by me": a dashed `#4A4339` border.
     - "Keywords not fetched yet": no border, `#211C16` fill.
   - **The single action per row (no menus):**
     - keyword film → "Leave out"
     - added film → "Remove"
     - left-out film → "Put back"
     - search result not in → "Add"
   - **Extra lines:**
     - "Added by me" rows: "TMDB doesn't tag it, so it's only in because you added it."
     - "Left out by me" rows: "TMDB tags it “christmas”. Put back and it shows again."
6. **Toast:**
   - Bottom centre, a pill with the message and an "Undo" button.
   - Messages:
     - "Left out {title}. It won't show at Christmas."
     - "Put back {title}. It'll show at Christmas."
     - "Added {title}."
     - "Removed {title}."
   - It stays 8 s, and a new action replaces it. It's `role="status"`.
   - Undo restores the previous choice and moves focus back to that row's button.

### Adding (see `02`, `06`)
- **Desktop:** typing opens a results panel under the field (440px wide, `#1A1713`, 64px rows):
  - poster, "Title · year", then either a state chip or the muted line "Not a Christmas film"
  - the button for that state
- **Behaviour:**
  - **One tap acts.** The row updates in place (Add becomes "Remove" with an "Added by me" chip) and the panel stays open, so I can add several.
  - **Keyboard:** Esc closes the panel; arrow keys move through the results. After a change made in the search panel the result's own button (Remove, Leave out, Add…) is the keyboard undo; the toast's Undo is for the pointer and for list rows.
  - The search matches as you type and allows typos, like the existing header search.
- **Phone:** tapping the field opens a full-screen search with "Cancel".

### Phone (≤ 600px; see `05`)
- **Top:** "‹ Admin" back link, then the eyebrow, title and rule line, with 16px gutters.
- **Status card:** stacked, with the meter under the text and the keyword strip wrapping.
- **Preview** becomes a full-width 44px button, "Preview Home in Christmas week ›", that opens the row in a sheet.
- **Segmented control:** three equal segments with short labels ("In week", "Added", "Left out") and the count under each.
- **Rows:** poster | the number and title wrapping, with the chip under them | the action button.
- **The toast** spans the width, 16px from the edges.

### States (see `04`)
- **Loading:** a static skeleton of the status card and five rows, with no shimmer. The header shows straight away.
- **Empty while keywords are being fetched:** shown when `inWeek` and `added` are empty and `fetched < total`.
  - "No Christmas films yet"
  - "CineFilm finds them from TMDB keywords, and it's still fetching those: {fetched} of {total} films so far. Films appear here as their keywords arrive, usually within the hour."
  - A progress fill with a mono "{fetched} / {total}".
  - "You don't have to wait: add any film yourself.", with the search field.
  - If keywords are complete and there's still nothing: "No films in your library are tagged Christmas. Add any you count." followed by the search.
- **Error:** the error banner (background `#2A1712`, border `#5A2A1E`, text `#F3B4A2`, heading `#F7CABB`) with `role="alert"`: "**Couldn't load Christmas films.** CineFilm didn't answer. Your choices are safe; nothing was changed." Then a "Try again" button that refetches.
- **Under 5:** the status card says "**{n} films** are in, which is under 5, so Christmas week shows the usual weekly theme instead." and "Add {5 − n} more and Home shows Christmas films."

### Rules
- **Amber only on:** the eyebrow, the current-nav marker, the slot fills and the keyword progress fill. **No amber buttons:** every action here is equal.
- **Focus ring:** `0 0 0 2px #0E0C0A, 0 0 0 4px #EDE6DA` on everything. Every target is at least 44px.
- **Motion:** only state changes (fade the toast and panel in about 150 ms; none with reduced motion).
- **No JSX in `state/`:** the derived counts, the cut index, the toast wording, the meter segments and the week-date wording go in `state/christmas.js` (no JSX) with unit tests. The API calls go in `api/`.

## Done when
- I can leave out, put back, add and remove without touching configuration, and every change undoes.
- The "In Christmas week" order is exactly what Home shows, and the first 12 are marked with the cut line after them.
- The preview matches the real Home row.
- A film without keywords says so in search, and a film without a TMDB id points me to Fix match.
- The overrides are managed here; there is no environment-variable seed and no `CINEFILM_HOLIDAY_*` setting (see Deviations in the build).
- The phone layout matches `05` and `06`. Every state in `04` works.
- **Frontend tests cover:**
  - tab counts
  - the cut line at 12 and at 13+
  - each action and its undo
  - the add-in-place behaviour
  - the `NO_TMDB_ID` and `KEYWORDS_PENDING` rows
  - the empty, error and fallback copy
  - keyboard use of the tabs and the search results

## Suggested PRs
1. **Overrides table:** the table and the selector reading from it. No UI; behaviour is unchanged.
2. **Christmas API:** the GET, the PUT and the search states.
3. **The screen:** the list, tabs, toast and undo, plus the Admin entry point.
4. **Adding:** the search panel and the phone sheet.
5. **Preview and states:** the preview, the empty, error and fallback states, and the polish.

## Deviations in the build

- **No environment-variable seed.** The variables were never released or set, so there was nothing to migrate; `HolidayOverrides`, `HolidayConfig` and the two properties were removed (owner, 2026-11-19).
- **The add search is a plain textbox with a plain list of result buttons**, not a combobox/listbox, because each result row holds a button (recorded in `christmas-films-plan.md`).
- **A redundant INCLUDE row is deleted on the next write only**, not at start-up.
- **The 409 can name any holder of the library lock** (a scan, a Plex import, a credits save), not only a scan; `/states` accepts at most 50 distinct ids and leaves out films that browse hides; `PUT` answers 404 when no browsable film has the TMDB id (`foundation-plan.md`).
- The empty state ("No Christmas films yet") shows only when In Christmas week, Added by me **and** Left out by me are all empty, not when In and Added alone are. A film left out must stay reachable, with its Put back button.
- After Leave out, Put back or Remove on a list row, focus goes to the same row's button if it is still listed, else the
  next row's button, else the tab panel. It does not go to the toast's Undo (that sent a keyboard user back through the
  whole list after every change, and held the toast while focus stayed on Undo). Undo stays reachable by Tab and the
  pointer, and using it returns focus to the row. The search panel and sheet keep their own focus behaviour.
- On a phone the rank number sits inline before the title (design `05`), not in its own column; the year is muted in
  list and search rows.
- Singular wording: "This 1 is in, but won't fit" / "This 1 won't fit" and "The other 1 is on its way". When the
  server says keywords are not being fetched (`keywords.stalled` true or `keywords.enabled` false), the strip and the
  empty copy say "Keywords aren't being fetched right now." instead of promising they are on their way; with the
  fields absent nothing changes.
