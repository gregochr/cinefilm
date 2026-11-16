# Admin redesign: nits left for cleanup B

Cleanup A (the Admin and Film detail frontend, the fixtures and mocks, the admin CSS and the plan docs) is done. What
is left is below: the items that touch the player, the backend or the Playing now panel (cleanup B), and the Admin
items A deliberately did not do, each with the reason. Owner questions and "record only" notes are kept as they were.

## Cleanup B: backend

Done in the cleanup B backend PR. Note only:

- `EditionOpenSubtitlesService.search` loads the edition twice (`findWithFilmById` + `hasOriginalFile`); fine at this
  scale, left.
- The dev mock `frontend/src/api/fixtureServer.js` still returns the old developer wording for the Continue watching
  range 400 (unreachable through the UI); change it with the next frontend PR that touches it.
- `ScanService`'s own 409 texts for a scan that cannot start ("A subtitle is being changed") were not reworded.

## Cleanup B: player, menus and Playing now

### 8a / 8b-1 (player, `PlayerMenus`, subtitle hooks)

- `role=alert` `<p>` and a heading `div` inside `role=menu` (`PlayerMenus`); the timing status/alert inside the
  `role=menu` group has the same ARIA-required-children problem: move them outside the menu.
- `.menu__problem` hard-codes `#f0876a` (no token; `styles/player.css`).
- `PlayerSubtitlesWrong.test.jsx` ~210-230: set `sidecarIds` [70..75] so the `wrongTargets` filters are proven at
  player level. `useSubtitleWrong`: a partial-overlap test (shows [61,62], serves [61,63] -> DELETE 61 only); line 32
  should throw `Changed` (not `LookupFailed`) when no download is served and the ids are absent. Plan doc 8a: one long
  line; changelog clause.
- 8b-1: a press at +/-10 minutes is a silent no-op.
- `subtitleTrackSwap.js`: the cancel doc comment line is overlong.
- Record only (8b-1 edge): the owner clears the check in Admin while a pending track loads, so the swapped track may be
  the replacement file under the old readout.
- OWNER QUESTION (8b-1): a timing nudge clears the owner's "Looks right" check by documented design
  (`forced-subtitles-plan.md:508`). Nudging a proven forced file in the player can demote it so another file serves
  on the next film load; the player now keeps the old track and says "changed since the film started". Should a nudge
  from the player keep the check?
- MERGE NOTE: 8b-1 imports `NUDGES`/`nudgedOffset`/`formatOffset`; 8b-2 deleted `NUDGES` and `useSubtitles`'
  `nudge`/`resetTiming`. When 8b-2 merged main after 8b-1, `NUDGES` had to stay (check it did).

### 5a-1 (Playing now)

- `PlayingNowPanel.test.jsx` `live()`, `playingNowFormat.test.js` `session()` and `admin-transcoding.json` lack
  `unavailable` (`SessionRow` serialises it).
- `PlayingNowPanel.jsx:68` `useCallback(leaveRow, [])` is redundant.
- Add a 16,000/16,001 summary-size boundary case with `touch` present.
- `usePlayingNow.js` JSDoc line over 120 chars.
- Open owner item: Admin Stop only ends the server session (see the Stop-ends-playback work).

### Sign-out and 401

- `useSignOut.test.jsx:40`: `finish` may be undefined if the client awaits before fetch; add `waitFor`. The 204 branch
  is better pinned in the client tests.
- Product: a 401 on `DELETE /api/session` shows "Couldn't sign out" instead of the sign-in card (`client.js:114` skips
  the expiry event).

### 5a-2 (needs `player/health.js`)

- `RUNG_OF_CODE` in `state/playbackChart.js` copies `player/health.js` `RUNG_CODES`: export that one and reuse it.

## Left in Admin on purpose (not done in A)

- **5a-2 per-column record cache or hover delay** (`usePlaybackRecord`): a cache by id would show a stale record for a
  session that is still playing, and a 150 ms delay slows every selection and flashes the skeleton; needs a decision
  on live sessions first.
- **5a-2 `SelectedSession`**: the operator is shown in the heading and again in the network line, and AR4 joins the mono
  lines with " · " on one line. A layout call against the AR4 screen; left.
- **5a-2 `chartSummary` literals**: the tile strings are repeated as literals in the summary; harmless, left (the
  column label now reuses `healthFormat.stallText`).
- **4a `AdminTabList` tab panels** use `tabIndex={-1}`; APG says 0 when the first content isn't focusable. Every panel
  starts with a heading or a button, so accepted.
- **4a `adminSubtitles.js` `foreignLine`**: the `fullSubtitle` branch looks unreachable for an unresolved row, but
  `adminTodo.fullLine` documents the case (a subtitle plays and the row is still unresolved); the server rule has to be
  confirmed before removing it.
- **4a `useFilmFilePicker`**: no `announceLibraryChanged()` after an attach; the hosts re-read through `onAttached`
  and the POST already cleared the cache. Accepted.
- **4a per-film "Show files" filter** (dropped; paging at 50): accepted, a film filter is a feature, not a nit.
- **4a dev mock `fixtureServer.js`**: `filmsWithOwnFile.add` also runs for a forced attach, but every file in the
  fixture is forced, so limiting it to FULL would remove the only dev path that settles a film. Do it when the fixture
  gets a full file.
- **`resources.js` `downloadOpenSubtitles`** takes no `options`, unlike `searchOpenSubtitles`: on purpose, a charged
  download must never be aborted (its POST neighbours take none either).
- **4a copy**: `noneFound` "nothing found automatically on OpenSubtitles" blends AR2 and AR1; mention it in a PR body
  when the copy is next touched.
- **3c-2 hint under both buttons**: the To do hint is under "Yes" only; AR1 right-aligns it under both. A layout
  change in `ActionWithHint`'s row; left.
- **6 `LibraryScanCard`**: "12 min ago" is computed at render and goes stale while the card is open (needs a ticking
  hook); thousands separators ("1318" vs the board's "1,318") and the Plex "Last import 10 Nov" date format; the scan
  409/start notice unmounts with the actions row when the status flips to running; `AdminLibraryFiles` keys include the
  index and the tile link's name reverses the visible order (label-in-name is fine).
- **6 owner note**: the Plex import's own split/join changes are no longer shown anywhere (documented in the
  foundation plan).
- **7 Settings**: the "min" unit sits outside the input; AR5 draws it inside.
- **8a Fix dialog**: toggling "Preview lines" to "Hide lines" drops that button's hint (a layout shift); accepted.
