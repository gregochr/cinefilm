# Credits 3c: `AUTO` markers from version 2 — plan

Written 2026-11-22, after 3b merged (#282–#288). It specifies 3c of `credits-phase3-plan.md`, whose "3c" section
lists what this spec had to settle. Where this file and `credits-detection-plan.md` differ on `AUTO`, this file wins.

## Decisions (owner)

- **2026-11-22: "Yes"** to 3c, version 2's detections as `AUTO` markers, ahead of L6a (still held). `AUTO` is
  therefore tied to this plan's gate, not to `credits-classifier-plan.md`'s version 3 gate.
- **2026-11-22: films added after the gate get `AUTO` automatically**, without a stills check of their own. The
  evidence for it: on every film looked at so far, no version 2 `FOUND` result was more than 15 s early (L5's 238
  test films; the development count comes from G1 below), and the 20 s margin is added to every `AUTO` marker. The
  owner can still Set or Clear any film in Admin.

## What changes for the owner

The films with no usable Plex marker (256 editions at the first 3a pass) get their end overlay, and count as
watched, 20 s after where version 2 saw the credits start, instead of at 97%. Admin → Library → End credits shows
these as **Detected**. In-progress states are not re-evaluated when a marker lands (as in 3a).

## The rule (`CreditsApply`, step 3)

Per edition, the first that holds wins (steps 1, 2 and 4 are unchanged from 3a):

1. source `OWNER`: unchanged.
2. a usable Plex row (`PlexState.USABLE`): Plex's time, source `PLEX`.
3. **`AUTO`, only when the switch is on and the deny list was read for this pass** ("The switch and the deny list"),
   the edition's film is not on the deny list, and the last part's `credits_detection` row:
   - has verdict `FOUND`, a non-null `offset_ms`, and `analysis_version` exactly **2**
     (`CreditsApply.AUTO_ANALYSIS_VERSION`; a later version needs its own gate before it marks anything);
   - has a fingerprint equal to the last part's current fingerprint;
   - has a crawl or card reason: its first reason starts "Text on a dark screen moves up steadily" (crawl) or "Cards
     of text on a dark screen change" (card), the two `CreditsFinder` openings of a `FOUND`. Any other reason, no
     `AUTO`. Cards count only while `cinefilm.credits.auto.cards` is true.

   The time: `start = EditionTimeline.toGlobal(lastIndex, offset_ms)` (a refusal means no `AUTO`), then
   `marker = min(start + 20 000, total − 60 000)`, where `total` is the edition's `timelineDurationMs` (none means
   no `AUTO`). If `marker < start` (the start is itself in the last 60 s) or `EditionGuard.plausible(marker, total)`
   fails, no `AUTO`. So an `AUTO` marker is never earlier than the detected start.
4. otherwise both cleared (the 97% fallback).

**When the deny list can't be read** (switch on, file missing, unreadable or malformed), step 3 is skipped and every
edition whose source is already `AUTO` is left unchanged for that pass, so a bad file never clears the markers and
puts them back later. When the switch is off, `AUTO` editions fall through to step 4 and are cleared.

**Clear** (Admin `DELETE`, 3b-1b) re-applies the edition as if its source were null, so it now lands on `AUTO` when
step 3 holds. There is no separate **Use detected**: Clear is it.

## The switch and the deny list

- `cinefilm.credits.auto.enabled` (default **false**; false in the test profile), `cinefilm.credits.auto.cards`
  (default true) and `cinefilm.credits.auto.deny-file` (a path; no default), documented in `application.properties`.
  The owner sets them in the server's `.env`; the file sits in the data directory the container already mounts
  (`operations.md` says where).
- **The deny file** is plain text, one `film_id` per line; blank lines and lines starting `#` are ignored. Any other
  line makes the whole file malformed. It is read at the start of each pass, and a pass logs its line count, never
  its ids. It is written by the planner (`~/cinefilm-labels/`, never the repository: it is library data) and holds:
  - **every film on the test side of the newest split manifest** (`split-<date>.json`), written from the manifest
    alone, before any version 2 result is read;
  - any film G2 judges unclear.

## The test split

The test split is not looked at by 3c, and its count of looks stays at two (L5 and the version 3 gate):

- Every test-side film is on the deny list, so the product never marks one `AUTO`, Admin never shows one as
  Detected, and the candidates list (G2) never names one.
- G1 drops test-side rows before anything is counted.
- L5's figure stands for them: 0 of 238 test films more than 15 s early.
- **Films added since the manifest** are on no side unless a later L1 run labels them; a new film with a Plex marker
  gets `PLEX` anyway. A new manifest means a new deny file, written the same way, before the next look at anything.

## Triggers, logs

- A new event, `CreditsDetected`, published by `CreditsDetectionService` after it saves a row (after the commit),
  asks the applier for a pass, like the 3a-2b triggers (one more pass at most, never inside the publisher's lock).
- The pass's log line gains the `AUTO` count set, and says once per pass why `AUTO` is off when it is (switch off,
  or the deny list unreadable). Never a title or an id.

## The gate (before the owner switches `AUTO` on)

Run once, after 3c-1 and 3c-2 are released and with the switch still off. The results go into this file as counts,
crawl and card apart, never titles.

- **G0, the deny file.** The planner writes the test-side deny file from the manifest; the owner copies it to the
  server and sets `cinefilm.credits.auto.deny-file` (the switch stays off).
- **G1, against Plex, development films only.** The owner runs a read-only `psql` export (the query goes in
  `operations.md` with 3c-1): every last part with a version 2 `FOUND` row and a Plex row on the same file and
  fingerprint, with `film_id`, `offset_ms`, Plex's `start_ms` and the first reason. The planner's offline join reads
  the manifest first and keeps development rows only. **Pass: zero rows with `offset_ms < start_ms − 15 000`.**
  - An early card turns cards off (`cinefilm.credits.auto.cards=false`).
  - An early crawl blocks `AUTO`: it is reported to the owner, and nothing is switched on without the owner's word.
- **G2, every candidate by stills.** The candidates list (3c-1) names every edition step 3 would mark now, as if the
  switch were on. The 3c-2 tool writes one sheet per candidate. The planner judges each one on the raw start, not
  the marker:
  - **early:** the frame at the raw start is still the film (picture that is not credits and not the black leading
    into them). Early cards turn cards off. An early crawl blocks `AUTO`, as in G1.
  - **unclear:** that film goes on the deny list, and nothing else changes.
- **G3, switching on.** The planner gives the owner the final deny file. The owner copies it, sets
  `CINEFILM_CREDITS_AUTO_ENABLED=true` (and cards off if G1 or G2 said so), and recreates the container. The pass
  that follows logs the `AUTO` count.

## Admin

- **Detected** tab (`filter=auto`, between From Plex and Yours), with its count, and `AUTO` rows show their time
  (3b showed none). Needs a look stays the editions with no marker.
- Clear's hint becomes "Goes back to Plex's marker, the detected time, or none".
- Version 2's verdict and reasons stay unshown.

## PRs

Each at most 1,500 changed lines, tests included.

- **3c-1, apply (off by default).**
  - Step 3 in `CreditsApply`, the switch and deny-list properties, the deny file reader (pure parser plus the read),
    `CreditsDetected` and its listener.
  - The pass's batched read gains the `credits_detection` rows (a fixed number of queries per batch). Log fields
    for `AUTO`.
  - `GET /api/admin/credits/auto-candidates`: `[{editionId, filmId, mediaFileId, kind, startMs, markerMs}]`,
    computed by the same `CreditsApply` code. It answers 409 "No deny list" when the file can't be read, so it never
    lists a test-side film. It goes in `foundation-plan.md` and `RouteSecurityDriftTest`.
  - The G1 export query and the deny-file steps go in `operations.md`.
  - Tests:
    - `CreditsApplyTest`: each condition of step 3, both reason openings and another reason, cards off, version 1
      and 3 rows, a stale fingerprint, the cap at `total − 60 s`, `start` in the last 60 s, the guard's lower bound,
      `toGlobal` refusing, a two-part edition, `OWNER` and `PLEX` still winning, a denied film, the switch off
      clearing `AUTO`, an unreadable list keeping it.
    - The parser's test.
    - `CreditsApplierTest`: the event triggers a pass, and the log counts.
    - `CreditsAdminServiceTest` and the controller test (401 and missing CSRF) for the candidates.
    - `CreditsApplierIntegrationTest`: an `AUTO` set and kept, with the query count per batch fixed.
- **3c-2, the sheet tool** (`credits.learn`, a `main` like `DatasetMeasureMain`, run with `docker run` and the
  media mounted read-only).
  - Reads the candidates JSON and writes, per candidate, a 9-tile sheet (`ContactSheet`) from 20 s before to 20 s
    after the raw start in 5 s steps (tile 5 is the start), in a fresh `auto-check-<UTC time>/` directory.
  - A file it can't read is listed by id and skipped, and the run still exits 0.
  - Tests: the tile times, the directory refusal, a missing file, the smoke test of the documented command.
- **3c-3, Admin.**
  - `CreditsFilter.AUTO`, `counts.auto` and the Detected tab; `AUTO` times shown; the Clear hint.
  - `foundation-plan.md`.
  - Tests:
    - backend: the filter and counts;
    - frontend: the tab, the times, the exact hint, the `adminApi` writes unchanged (the replay guard list).
- **Then the gate** (G0–G3), then the owner switches `AUTO` on. No release is tagged by a session.

## Not in this plan

- Version 3 (L6a) and `credits-classifier-plan.md`'s gate stay held; that gate still decides any later `AUTO` from
  version 3.
- CineLight's fade from the marker (the Phase 6 webhook).
