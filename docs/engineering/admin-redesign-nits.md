# Admin redesign: nits still open

Cleanups A and B (Admin, Film detail, the player menus, Playing now, the backend and the dev mock) are done. What is
left is below: notes kept for the record, the owner questions, and the Admin items that were deliberately not done,
each with the reason.

## Still open

Record only:

- `EditionOpenSubtitlesService.search` loads the edition twice (`findWithFilmById` + `hasOriginalFile`); fine at this
  scale, left.
- `ScanService`'s own 409 texts for a scan that cannot start ("A subtitle is being changed") were not reworded.
- 8b-1 edge: the owner clears the check in Admin while a pending track loads, so the swapped track may be the
  replacement file under the old readout.
- The player's "Subtitles are wrong" and timing failures still show their own generic line on a 409 (the lock text is
  shown by Fix match, Edit editions, the need rows, Admin to do and the subtitle picker and panel only).

Bugs:

- A 401 on `DELETE /api/session` shows "Couldn't sign out" instead of the sign-in card (`client.js` skips the expiry
  event for `/api/session`).

Owner questions:

- OWNER QUESTION (8b-1): a timing nudge clears the owner's "Looks right" check by documented design
  (`forced-subtitles-plan.md:508`). Nudging a proven forced file in the player can demote it so another file serves
  on the next film load; the player now keeps the old track and says "changed since the film started". Should a nudge
  from the player keep the check?
- Open owner item: Admin Stop only ends the server session (see the Stop-ends-playback work).

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
