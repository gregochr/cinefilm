# Credits phase 3b: Admin "End credits" — plan

Written 2026-11-21, after 3a merged (#274, #276, #278) and 0.10.0 started applying `PLEX` markers. It specifies the 3b
section of `credits-phase3-plan.md`. That plan's precedence, lock rules and the accepted in-progress behaviour stand
unless this file says otherwise.

## What the owner gets

Admin → Library gains an **End credits** card that links to a list of every edition with its end-credits marker:
where the marker came from, the time, and why there is none when there isn't. Opening a row shows two stills, so a
glance shows "credits" or "still the film". Two actions:
- **Set** an owner time, source `OWNER`. Nothing automatic ever overrides it.
- **Clear** an owner time, back to automatic. Today that means Plex's marker when one is usable, otherwise none.

The name is "End credits", not "Credits": Library's "Cast & crew" card already means TMDB's credits.

## The test split stays untouched

Version 2's verdict, time and reasons appear only for editions whose last original part has **no `plex_credits_marker`
row at all**. The API omits them for every other edition, with no flag for "hidden", and no still is ever requested at
version 2's time for those editions.

The reason: the labelled set (development and test alike) is the files with a Plex row, so this rule never puts a
version 2 result beside its Plex answer for any film, test side included. That holds whether or not the row's
fingerprint still matches, and for films that join later. The database holds no split, and this rule doesn't need
one. 3c's own gate rules are unchanged.

## 3b-1: the API (backend only)

**`GET /api/admin/credits?filter=&page=&size=`**
- `filter` takes four values:
  - `none`, the default: editions with no marker;
  - `plex`: source `PLEX`;
  - `owner`: source `OWNER`;
  - `all`.
- `page` starts at 0; `size` defaults to 25, with a maximum of 100. Any other value of a parameter is a 400 (as
  `PlaybackHealthController` does).
- Order: the film's `sortTitle` (case-insensitive), then the film's year, then the edition's name and id.
- Hidden editions (`hiddenFromBrowse()`) are left out of every filter and count.
- Answer, in `CreditsAdminDtos`:

  ```
  CreditsList { counts: { none, plex, owner, all }, page, size, total, items: [CreditsRow] }
  ```

  `total` is the count for the filter asked for.
- **`CreditsRow`**:
  - `editionId`, `filmId`, `title`, `year`, `editionName`;
  - `runtimeMs`, the edition's timeline total (the same number the still endpoint and the guard use);
  - `markerMs`, `source` (null and null when there is no marker);
  - `plex` (`PlexStatus`):
    - `state` is one of:
      - `NONE`, no row;
      - `STALE`, the fingerprint differs from the last part's current one;
      - `OUTSIDE`, `toGlobal` refuses the offset;
      - `REFUSED`, `EditionGuard.plausible` fails;
      - `USABLE`;
      - `NO_TIMELINE`, an original part with no duration, or a last part with no fingerprint;
    - `markerMs`: Plex's edition time, set for `USABLE` and `REFUSED`, null for the other states;
  - `detected` (`DetectedStatus`), present only when `plex.state` is `NONE` (see "The test split stays untouched"):
    - `state` is one of:
      - `CURRENT`, a `credits_detection` row for the last part with the current fingerprint and
        `analysis_version` equal to `CreditsTuning.ANALYSIS_VERSION`;
      - `OUT_OF_DATE`, a row exists but is not current;
      - `NOT_ANALYSED`, no row;
    - only when `CURRENT`: `verdict`, `markerMs` (edition time, through `EditionGuard.apply`, so a time the guard
      refuses comes back without a time and with its reason) and `reasons` (the row's lines).
- **Queries:** a fixed number per page, never one per edition: the page of edition ids, those editions with their files,
  their Plex rows, their detection rows, and one grouped count query.

**`PUT /api/admin/editions/{id}/credits` `{ markerMs }`**
- `markerMs` must be whole seconds' worth, at least 1,000 ms and below the timeline total. Anything else is a 400
  `{error}` saying the time is outside the film.
- The guard does **not** apply: the owner may set any time inside the film.
- On success it sets the marker and source `OWNER`, and answers the new `CreditsRow`.
- 404 for an unknown or hidden edition.
- 409 `busyText()` when the library lock is held. It takes the lock once with `tryAcquire(CREDITS)`, as every Admin
  writer does, and never waits.

**`DELETE /api/admin/editions/{id}/credits`**
- Under the same single lock attempt, it runs `CreditsApply.apply` for that edition as though its source were null,
  then writes both fields together.
- It answers the new `CreditsRow`, and behaves the same whatever `cinefilm.credits.apply.enabled` says.
- On a non-`OWNER` edition it changes nothing and answers the row. Running the pass's own rule keeps the two in step.

**Code shape**
- `credits/CreditsAdminController`, thin, over `credits/CreditsAdminService`.
- `CreditsApply` gains a pure `plexStatus(parts, lastFingerprint, plexRow)` that returns the state and time above.
  `apply` uses it for step 2, so the list and the pass can never disagree.
- One edition's apply is shared with `CreditsApplier.applyBatch` rather than copied: a package-visible static
  `CreditsApply.forEdition(Edition, PlexCreditsMarker)`, or the builder's equivalent that both call.
- No migration; `PlexCreditsMarker`'s Javadoc stops saying nothing applies it.

**Tests (Mockito unit, plus one integration)**
- `CreditsApplyTest`: every `plexStatus` state, at the guard's bounds and one either side; `apply` still passes its
  existing cases.
- `CreditsAdminServiceTest`:
  - each filter's selection and counts;
  - hidden editions left out;
  - `detected` present only when there is no Plex row: a `STALE` row hides it too;
  - `OUT_OF_DATE` on either a fingerprint or a version mismatch;
  - a guard-refused detected time;
  - `PUT` at 999 / 1,000 ms, at total − 1,000 / total, a non-whole second, and an unknown or hidden edition;
  - `DELETE` restoring `PLEX` from a usable row and clearing without one;
  - both writes answering 409 without writing when the lock is held, and releasing it after a failure.
  - Assert the saved marker and source, never repository call counts.
- `CreditsAdminControllerTest` (MockMvc): parameter validation (each bad value a 400), the 400 and 409 bodies, and
  the JSON field names the frontend reads.
- `CreditsAdminIntegrationTest` (Testcontainers, CI): seeded editions in all four filters; order and paging across a
  page boundary; a fixed statement count per page; a `PUT` then a `DELETE` round trip.

## 3b-2: the screen (frontend)

**Where**
- Library gets an **End credits** card beside the Plex card, holding:
  - three count tiles: "No marker", "From Plex", "Yours";
  - a secondary button **Review end credits…**, which opens `/admin/library/credits`.
- That page is a sub-list like the `FILE_LISTS` pages, with a back link to Library; `bareOnPhone` applies to it.
  Its filter tabs use `AdminTabList`:
  - No marker (the default);
  - From Plex;
  - Yours;
  - All.
  Each tab has its count, and the tab is in the URL (`?show=`).
- 25 rows a page from the server, with Previous and Next and "Showing 26–50 of 212", as the grouping report pages.

**A row** (rows `44px 1fr auto`, `RowText`: title, "· year", and the edition name when the film has more than one
edition):
- **Line two** says where the time came from, with the time in IBM Plex Mono, formatted by `formatResume`:
  - "From Plex · 1:52:10";
  - "Yours · 1:52:10";
  - "No marker · the end screen shows at 97%".
- **Line three**, only for a row without a marker, says why:
  - `STALE`: "Plex's marker is for an older copy of this file";
  - `REFUSED`: "Plex's marker at 0:41:07 is too early or too late to trust";
  - `OUTSIDE`: "Plex's marker is past the end of this file";
  - `NONE` with `detected`:
    - "The detector found credits at 1:52:10", or "The detector wasn't sure", or "The detector found none";
    - "Not checked by the detector yet";
    - "The detector's check is out of date".
  The exact strings live in `state/creditsAdminCopy.js`.
- **The action** is one secondary pill, **Look and set…**, which opens the row's panel. Row actions are never amber.

**The row's panel** (inline under the row, one open at a time; opening another closes the first):
- **Two stills** at the panel's time and 30 s earlier (floored at 0), from `editionsApi.stillUrl`, 16:9 boxes reserved,
  `loading="lazy"`, a static skeleton until each loads.
- **The panel's time** is the row's marker, else the detector's time, else 97% of the runtime.
  - A 404 shows "No still for this file".
  - A 503 shows "Still not ready" with a quiet **Try again**, which never retries by itself.
  - No still is requested until a panel opens, so a page costs nothing on the server's ffmpeg limit of two at once.
- **The detector's reasons**, when the row has them, as a plain list.
- **A time field** ("End credits start at", `h:mm:ss` or `m:ss`, prefilled with the panel's time):
  - **Show stills** reloads both stills at the typed time.
  - **Set this time** is the panel's one primary action, which is why it may be amber. It sends the `PUT`.
  - A typed time that won't parse, or is outside the film, shows `p.admin__field-error` with `aria-invalid` and sends
    nothing.
- **Clear my time**, quiet, only on a "Yours" row. Its hint: "Goes back to Plex's marker, or none".
  It sends the `DELETE`.

**After a write**
- A toast confirms the outcome: "End credits set" or "Your time cleared".
- The page and counts are read again, because the row may leave the current tab.
- Focus goes to the next row's button, or to the section title when no row is left, through `useFallbackFocus`.
- A 409 shows the server's text in the panel through `lockText`, and the panel stays open with the typed time.
- A 400 shows the server's text the same way.

**States**
- Loading the page: static skeleton rows.
- A failed read: the inline error banner with **Check again**, as Library's other sections show one.
- An empty tab: "No films here".
- An announcement on a live region after a write.

**Code shape**
- `api/resources.js`: `adminApi.credits(filter, page)`, `setCredits(id, markerMs)`, `clearCredits(id)`. No JSX.
- `state/creditsAdmin.js` (pure): `parseTime` (the first h:mm:ss parser here, whole seconds), the panel's time, the
  line-two and line-three text, and the paging text.
- `state/useCreditsAdmin.js`: the hook.
- `screens/admin/EndCreditsCard.jsx`, `screens/admin/AdminEndCredits.jsx`, `screens/admin/EndCreditsPanel.jsx`.
- The route goes beside the `FILE_LISTS` routes in `App.jsx`.

**Tests (Vitest, 95/90 per file)**
- `parseTime`:
  - `1:52:10`, `52:10`, `0:59`;
  - rejects `1:60:00`, `1:2:3:4`, empty input, negative times and letters;
  - the bounds 1,000 ms and total − 1,000.
- Every copy branch.
- The panel's time choice.
- Paging text at the first, a middle and the last page.
- Components:
  - opening a panel requests exactly two stills, and none before;
  - one panel open at a time;
  - 404 and 503 stills, with Try again;
  - Set, Clear and their 400 and 409 paths;
  - the focus target after a write empties the tab;
  - the tab in the URL;
  - Clear shown only on a "Yours" row.

**Split if over 1,500 lines:** 3b-2a, the card, the list and the panel with its stills (read-only); 3b-2b, the
time field, Set and Clear.

## Not in this plan

- **Use detected** (back to `AUTO`) arrives with 3c.
- Re-evaluating in-progress states when a marker changes, as 3a accepted.
- A dedicated "Admin: End credits" screen in the handover. Admin "can be plain" (handover §3), and the Admin redesign's
  button, row and card rules above are the reference.
