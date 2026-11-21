# Credits phase 3b: the Admin "End credits" list — plan

Written 2026-11-21, after 3a merged (#274, #276, #278) and 0.10.0 started applying `PLEX` markers. Revised the same
day after an adversarial review checked it against the code.

This plan specifies the 3b section of `credits-phase3-plan.md`. That plan's precedence, lock rules and accepted
in-progress behaviour stand unless this file says otherwise.

## What the owner gets

Admin → Library gains an **End credits** card. It links to a list of every edition and its end-credits marker: where
the marker came from, the time, or why there is none. Opening a row shows two stills, so a glance tells "credits"
from "still the film".

Two actions:
- **Set** an owner time. Its source is `OWNER`, and nothing automatic overrides it.
- **Clear** an owner time, which goes back to automatic: Plex's marker when one is usable, otherwise none.

The name is "End credits", not "Credits", because Library's "Cast & crew" card already means TMDB's credits.

## No version 2 results in 3b

`credits-detection-plan.md`'s Admin outline lists version 2's verdict and reasons. 3b leaves them out, together with
any still at version 2's time.

Phase 3's plan forbids showing any version 2 result for a film on the test side, including films added since the
manifest. The product can't tell a film's side yet, and 3c's spec has to settle how. No per-edition rule substitutes
for that: one edition of a film can have a Plex row while another has none, and a film with no row today may get one
later.

So version 2's verdict, reasons and **Use detected** all arrive with 3c.

## How a row's Plex state is worked out

`CreditsApply` gains a pure `plexStatus(parts, lastFingerprint, plexRow)`, computed whatever the edition's source.
`apply`'s step 2 is rewritten to use it, so the list and the pass can never disagree.

States, checked in the order `CreditsApply.plexMarker` checks them:

1. `NONE`: no row.
2. `NO_FINGERPRINT`: the last part has no fingerprint.
3. `STALE`: the row's fingerprint differs from the last part's.
4. `NO_TIMELINE`: `EditionTimeline.of` refuses the parts (no original part, or a part with no duration).
5. `OUTSIDE`: `toGlobal` refuses the row's offset.
6. `REFUSED`: `EditionGuard.plausible` fails.
7. `USABLE`: none of the above.

Only `REFUSED` and `USABLE` carry Plex's time, in edition time.

## The API

**`GET /api/admin/credits?filter=&page=&size=`**

`filter` takes one of four values:
- `none` (the default): editions whose source is null, whether or not a marker is set (a marker with no source yet
  shows as "not settled yet");
- `plex`: source `PLEX`;
- `owner`: source `OWNER`;
- `all`.

The other parameters:
- `page` is 0-based.
- `size` defaults to 25, with a maximum of 100.
- Any other value is a 400, as `PlaybackHealthController` answers.

Ordering is by the film's `sortTitle` (case-insensitive), then the film's year, then the edition's name, then its id.

**Hidden editions.** These are left out of every filter and every count, by a database predicate that matches
`Edition.hiddenFromBrowse()`. The predicate is `NOT EXISTS` an original part that is any of these:
- not present on disk;
- not `PLAYABLE`;
- part of a multi-part edition with a null or zero duration.

The builder confirms the predicate against `hiddenFromBrowse()` and the integration test pins the two together.

**The answer** goes in `CreditsAdminDtos`:

```
CreditsList { counts: { none, plex, owner, all }, page, size, total, items: [CreditsRow] }
```

- `total` is the chosen filter's count.
- The four counts come from one grouped query, and `none + plex + owner = all`.

**`CreditsRow`** has these fields:
- `editionId`, `filmId`, `title`, `year`, `editionName`, `editionCount` (how many visible editions the film has);
- `runtimeMs`: the edition's timeline total, or null when `EditionTimeline.of` refuses the parts;
- `markerMs` and `source`;
- `plex { state, markerMs }`.

Each page costs a fixed number of queries, never one per edition: the page of ids, those editions with their files,
their Plex rows, and the counts.

**`PUT /api/admin/editions/{id}/credits` `{ markerMs }`**
- `markerMs` must be whole seconds and must pass `EditionGuard.plausible` for the edition's timeline: at least 60% in,
  and at least 60 s before the end.
- That bound is the floor against a typo. "1:52" read as 1 min 52 s would otherwise make the film count as watched
  on its first progress report.
- Anything else is a 400 `{error}`:
  - "That time is outside where end credits can start";
  - "This film's length isn't known" (when there is no timeline).
- On success it sets the marker with source `OWNER` (over a `PLEX` marker too) and answers the new `CreditsRow`.
- 404 for an unknown or hidden edition.

**`DELETE /api/admin/editions/{id}/credits`**
- On an `OWNER` edition, it runs `CreditsApply.apply` as though the source were null and writes marker and source
  together. This happens whatever `cinefilm.credits.apply.enabled` says.
- On any other edition it writes nothing and answers the row as it is. A stale `PLEX` marker is left for the pass.
- It answers the new `CreditsRow`.

**Both writes**
- Each takes the library lock once with `tryAcquire(LibraryLock.CREDITS)`, never waiting, and answers 409 with
  `busyText()` when the lock is held.
- `CREDITS` is the holder the detector and the applier use. Sharing it is deliberate: a write and a pass can never
  overlap.
- **Order:** take the lock, run a `TransactionTemplate` that reads, computes, writes and commits, then release the
  lock in `finally` and build the answer. This is the pattern `ChristmasFilms.choose` uses.
- The service method is never `@Transactional`: the lock must outlive the commit, or a pass waiting on the lock could
  write a stale source over the owner's change.

**Code shape**
- `credits/CreditsAdminController` stays thin, over `credits/CreditsAdminService`.
- One edition's apply is shared with `CreditsApplier.applyBatch`, not copied.
- `PlexCreditsMarker`'s Javadoc stops saying nothing applies it.
- `docs/engineering/foundation-plan.md`'s API contract gains the three endpoints in the same PR (AGENTS.md rule 4).

## The screen

**Where**
- Library gets an **End credits** card beside the Plex card, with three count tiles ("No marker", "From Plex",
  "Yours") and a secondary button, **Review end credits…**, that opens `/admin/library/credits`.
- That page is a sub-list like the `FILE_LISTS` pages: a back link to Library, and `bareOnPhone` applies.
- Filter tabs use `AdminTabList`: No marker (the default), From Plex, Yours and All, each showing its count. The
  chosen tab is in the URL as `?show=`.
- The server sends 25 rows a page. The page has Previous and Next and a line like "Showing 26–50 of 212", paged as
  the grouping report is.
- If a write leaves the current page empty while `total` is above 0, the page steps back one.

**A row**
- Layout is `44px 1fr auto`, with `RowText` showing the title and "· year", plus the edition's name when
  `editionCount` is above 1.
- **Line two** is the source and time, in IBM Plex Mono through `formatResume`:
  - "From Plex · 1:52:10";
  - "Yours · 1:52:10";
  - "Not settled yet · 1:52:10";
  - "No marker · the end screen shows at 97%".
- **Line three**, only when there's no marker, gives the reason:
  - `NONE`: "Plex has no marker for this film";
  - `NO_FINGERPRINT` or `STALE`: "Plex's marker is for an older copy of this file";
  - `OUTSIDE`: "Plex's marker is past the end of this file";
  - `REFUSED`: "Plex's marker at 0:41:07 is too early or too late to trust";
  - `NO_TIMELINE`: "This film's length isn't known".
- The strings live in `state/creditsAdminCopy.js`.
- **The action** is one secondary pill, **Look and set…**, which opens the row's panel. It is never amber.

**The row's panel**
- It opens inline under its row. Only one panel is open at a time.
- **The panel's time** is the marker, else Plex's `REFUSED` time, else 97% of `runtimeMs`.
- **Two stills**, at the panel's time and 30 s earlier (floored at 0):
  - from `editionsApi.stillUrl`, in reserved 16:9 boxes, with a static skeleton until each loads;
  - no still is requested until a panel opens, so a page puts nothing on the server's two-at-a-time ffmpeg limit;
  - a failed still (an `<img>` can't tell a 404 from a 503) shows "No still for this time" and a quiet
    **Try again**, which never retries by itself.
- **The time field** reads "End credits start at", takes `h:mm:ss` or `m:ss`, and is prefilled with the panel's time.
  - **Show stills** reloads both stills at the typed time.
  - **Set this time**, a secondary pill (the Admin button rule allows no other primary), sends the `PUT`.
  - A time that won't parse, or falls outside the guard's range, shows `p.admin__field-error` with `aria-invalid`
    and `aria-describedby`, and sends nothing. The range check is computed from `runtimeMs`, matching the server's.
- **Clear my time** is a quiet button with the hint "Goes back to Plex's marker, or none", shown only on a "Yours"
  row. It sends the `DELETE`.
- With no `runtimeMs`, the panel shows line three's text and **Clear my time** where it applies. It has no stills and
  no time field.

**After a write**
- A toast says "End credits set" or "Your time cleared", and the same text goes to a live region.
- The page and its counts are read again.
- Focus moves through `useFallbackFocus` to the row's own button if the row is still on the page, otherwise the next
  row's button, otherwise the section title.
- A 400 or 409 shows the server's text in the panel through `lockText`. The panel stays open and keeps the typed time.

**Other states**
- Loading: static skeleton rows.
- A failed read: the inline error banner with **Check again**, which reads again.
- An empty tab: "No films here".

**Code shape**
- `api/resources.js` gains `adminApi.credits(filter, page)`, `setCredits(id, markerMs)` and `clearCredits(id)`.
- `state/creditsAdmin.js` (pure, no JSX) holds:
  - `parseTime`, whole seconds; it is the first h:mm:ss parser in the codebase;
  - the guard range;
  - the panel's time;
  - the row text;
  - the paging text and its step-back.
- `state/useCreditsAdmin.js` is the hook.
- `screens/admin/EndCreditsCard.jsx`, `AdminEndCredits.jsx` and `EndCreditsPanel.jsx` are the components.
- The route sits beside the `FILE_LISTS` routes in `App.jsx`.

## PRs (each under 1,500 lines, tests included)

**3b-1a — `plexStatus` and the GET.**
- `CreditsApplyTest`:
  - every state in order, with a row and a null fingerprint, and a stale row with a null duration (which is `STALE`);
  - the guard's bounds, one below, at and one above;
  - `apply`'s existing cases unchanged.
- `CreditsAdminServiceTest`:
  - each filter's rows and the grouped counts adding up, with an unsettled marker counted under `none`;
  - hidden editions left out;
  - `runtimeMs` null without a timeline;
  - `editionCount`.
- `CreditsAdminControllerTest` (MockMvc): each bad parameter gives a 400, and the JSON field names the frontend
  reads. No field outside `CreditsRow`'s list above, so no version 2 field.
- `CreditsAdminIntegrationTest` (Testcontainers):
  - seeded editions in every filter;
  - the hidden predicate agreeing with `hiddenFromBrowse()` for each hidden case;
  - order and paging across a page boundary;
  - a fixed statement count per page.

**3b-1b — PUT and DELETE.**
- `CreditsAdminServiceTest`:
  - PUT one second below, at and one second above both guard bounds, with a timeline total that isn't a whole
    second (for example 7,200,500 ms);
  - a non-whole second;
  - no timeline;
  - unknown and hidden editions;
  - `OWNER` over `PLEX`;
  - DELETE on `OWNER`: back to `PLEX` from a usable row, and cleared without one;
  - DELETE on a stale `PLEX` edition, which is left alone;
  - both writes answering 409 without writing when the lock is held, and releasing the lock after a failure.
  - These assert saved marker and source, never repository call counts.
- `CreditsAdminControllerTest`: the 400, 404 and 409 bodies.
- `CreditsAdminIntegrationTest`: a PUT then DELETE round trip, and the lock released only after the commit (a second
  thread's `tryAcquire` fails until the write has committed).

**3b-2a — the card and the read-only list.**
- `parseTime` isn't in this PR.
- Pure tests:
  - the row text for every state and source;
  - the panel's time choice;
  - the paging text at the first, a middle and the last page;
  - the step-back.
- Component tests:
  - the card's tiles and link;
  - tabs with their roles and `?show=`;
  - Previous and Next;
  - the skeleton, the failed read and Check again re-reading;
  - the empty tab;
  - opening a panel requests exactly two stills, and no still is requested before;
  - one panel open at a time;
  - a failed still and Try again;
  - no stills without `runtimeMs`.

**3b-2b — the time field, Set and Clear.**
- `parseTime`:
  - accepts `1:52:10`, `52:10` and `0:59`;
  - refuses `1:60:00`, `1:2:3:4`, empty input, negative values and letters.
- The guard range one second either side of both bounds, with a non-whole-second runtime.
- Component tests:
  - Set and Clear with the toast and live-region text;
  - 400 and 409 keeping the panel and the typed time;
  - `aria-invalid` and the error description;
  - focus landing on the row's button, the next row's, or the section title;
  - Clear only on a "Yours" row;
  - the page stepping back after the last row leaves.

## Not in this plan

- Version 2's verdict, reasons and **Use detected**, all with 3c.
- Re-evaluating in-progress states when a marker changes, as 3a accepted.
- A handover screen for this list. Admin "can be plain" (handover §3), and the Admin redesign's button, row and card
  rules are the reference.
