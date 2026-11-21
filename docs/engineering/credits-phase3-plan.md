# Credits phase 3: applying markers — plan

Written 2026-11-21, after L5 (`credits-classifier-plan.md`, "L5: what the models showed"): the owner kept version 2
and held L6a. Revised the same day after an adversarial review checked against the code. It revises phase 3 of
`credits-detection-plan.md`, whose storage, precedence, edition guard, split editions and watched rule stand unless
this file says otherwise.

## Why

Nothing sets `edition.credits_marker_ms` today. Every film's end overlay appears at 97% of its runtime and it counts
as watched at 92%. Two sources sit unused in the database:

- **`plex_credits_marker`** (phase 2c): Plex's own marker, one row per media file, for most of the library, already
  moved later where Plex's copies differ (`max(0, …)` in 2c). Plex is the benchmark the credits work is measured
  against, so a `PLEX` marker takes no margin (the settled precedence, "Storage" step 2).
- **`credits_detection`** at `analysis_version` 2: the rule-based detector's verdict for every file. On L5's 238 test
  films it was within ±10 s of Plex for 71% of dark openings, and none of its `FOUND` results was more than 15 s
  early (seven were 5–15 s early).

This plan applies `PLEX` markers now (3a), adds the Admin view (3b), and leaves `AUTO` from version 2 (3c) to the
owner's decision, because the 2026-11-21 decision says phase 3's `AUTO` waits with L6a.

## 3a-1 — the source column, carried (inert)

- **Migration** `V35__credits_marker_source.sql`, expand-only: `edition.credits_marker_source VARCHAR(10)` nullable,
  CHECK in `AUTO`, `PLEX`, `OWNER`. `Edition` gets the field.
- **The source travels with the marker** wherever the marker moves: `StateCarry` (`Old`, `Piece`, `Result`),
  `EditionJoin` (`join`, where it carries `marker`, and `unfold`, where it relocates it) and `EditionArranger.settle`
  (where it carries `carried.markerMs()` and where it clears the marker: the source is cleared with it). Those paths
  serve the scan's grouping, the Plex film import (`PlexFilmWriter`) and Edit editions alike. A marker and its source
  are always both null or both set.
- **`DevSeed`** seeds its markers as `OWNER`: it seeds no Plex rows and its files have no fingerprint, so any other
  source would be cleared by 3a-2's first pass.
- **Nothing reads the source yet**; behaviour is unchanged.
- **Tests:** the existing `StateCarry`, `EditionJoin` and `EditionArranger` tests gain the source beside every marker
  assertion, the clear path included; `DevSeedTest` checks `OWNER`; `FlywayMigrationIntegrationTest` checks the
  column, its CHECK and existing rows null.

## 3a-2 — applying `PLEX` markers

- **`CreditsApply`, pure:** given an edition's ordered original parts (ids, durations), its last part's current
  fingerprint, its marker and source, and the last part's `plex_credits_marker` row, returns the marker and source:
  1. source `OWNER`: unchanged;
  2. a Plex row whose fingerprint matches the file's current one, whose edition time
     (`EditionTimeline.toGlobal(lastIndex, start_ms)`) passes `EditionGuard.plausible`: that time, source `PLEX`;
     `toGlobal` refusing the offset (outside the part) means no marker from this step;
  3. reserved for 3c's `AUTO`; never applies in 3a;
  4. otherwise both cleared.
  No `timelineDurationMs`, or a last part with no duration or fingerprint: step 4.
- **`CreditsApplier`, the service,** shaped like `CreditsRefresher.startDue`/`worker`: its own single-thread
  executor and a pending flag, so triggers that arrive during a pass ask for exactly one more pass. Each pass waits
  for the `LibraryLock` holder `CREDITS` with bounded retries, as `PlexCreditsImportService.acquireLock` does
  (`LOCK_ATTEMPTS` × `LOCK_WAIT`); a pass that still can't take it gives up and leaves the flag set for the next
  trigger or nudge. Under the lock, in batches of short transactions, it reads the editions, their parts and the Plex
  rows with a fixed number of queries per batch (never one per edition), computes, and writes only the editions whose
  marker or source changes. Computing under the lock is what keeps an Edit editions save or an Admin `OWNER` set from
  landing between the read and the write.
- **Triggers** (each only sets the flag and wakes the worker, so it never runs inside the publisher's lock or
  transaction): `ApplicationReadyEvent`; `ScanFinished`; `PlexImportFinished`; a new `PlexCreditsImportFinished`,
  published by `PlexCreditsImportService` when an import finishes; Edit editions' save, after commit; and the hourly
  `CreditsScheduler` nudge, which is the real retry.
- **Config:** `cinefilm.credits.apply.enabled` (default true; false in the test profile), documented in
  `application.properties`.
- **Logs:** one line per pass with counts (`PLEX` set, changed, cleared, `OWNER` kept, unchanged), and the number of
  in-progress states whose saved position is at or past their edition's new marker. Never a title.
- **`PlexCreditsImportService`'s Javadoc** stops saying nothing reads the rows.
- **What changes for the owner:**
  - films with a Plex marker show the end overlay, and count as watched, at Plex's credits start (the watched rule is
    "watched at the marker", decided 2026-11-13);
  - **accepted:** states already in progress are not re-evaluated, so a film saved at or past its new marker (long
    credit rolls, where the marker is below 92%) stays in Continue watching. Resuming it shows the end overlay almost
    at once, and the first progress report marks it watched. The pass logs how many there are.
- **Tests:**
  - `CreditsApplyTest`: each step; a stale fingerprint; the guard at both bounds; `toGlobal` refusing the offset; a
    two-part edition (the marker lands at part 2's edition time); no timeline; `OWNER` kept with a Plex row present.
  - `CreditsApplierTest` (Mockito; every service has its own unit test class, and the local coverage gate excludes
    `integration/`): the lock wait, refusal and the flag left set; a trigger during a pass gives exactly one more pass;
    only changed editions written; `OWNER` skipped; the log counts; each trigger; the property off.
  - `CreditsApplierIntegrationTest` (Testcontainers, CI): seeded editions and rows; a pass sets, clears and keeps;
    a second pass writes nothing; the query count per batch is fixed.

## 3b — Admin: see and correct

Specified after 3a-2 merges, against the handover's admin visual language, as `credits-detection-plan.md`'s "Admin
(phase 3)": the list (film, edition, source, time, version 2's verdict and reasons, two stills), **Set** (`OWNER`),
**Clear** (back to automatic: the source is cleared and a pass is asked for), and `GET /api/admin/credits`, `PUT` and
`DELETE /api/admin/editions/{id}/credits`. Both writes take the lock as the applier does. **Use detected** arrives
only with 3c.

## 3c — `AUTO` from version 2 (needs the owner's yes)

Not specified until the owner says yes: the 2026-11-21 decision has `AUTO` wait with L6a, and that PR edits both
`credits-detection-plan.md` passages that tie `AUTO` to the classifier plan's gate. Settled now for when it is:

- **Its gate is the classifier plan's revised gate applied to version 2:** zero version 2 `FOUND` rows more than 15 s
  before a matching Plex row, and **every** version 2 `FOUND` row on a file with no matching Plex row checked by
  stills (about 220 at most; those are the only films `AUTO` would ever mark), not a sample. Crawl and card results
  are reported apart; an early card result limits `AUTO` to crawls, an early crawl result blocks it. Counts only, no
  titles, in this file.
- **Before writing the spec,** the planner reads the "more than 15 s early" bin of
  `~/cinefilm-labels/breach-2026-11-15.txt` (all 847 files): if it is above zero, the Plex half already fails.
- **The test split.** Version 2 is fixed, its test results were scored at L5, and nothing for version 3 is chosen from
  this gate, so it is not one of the classifier plan's two looks. It is recorded in that plan's looks log, naming the
  crawl/card breakdown of test rows as seen, so no later version 3 choice draws on it.
- **Applying:** a version 2 `FOUND` row whose fingerprint matches, of a kind the gate cleared, at its edition time
  plus **20 s** (capped at the guard's 60 s before the end), source `AUTO`. `PLEX` still wins. The detector's row
  writes become a trigger, and **Use detected** joins Admin.

## Not in this plan

- L6a (version 3 in the product) and the classifier plan's gate stay held.
- CineLight's fade from the marker (the Phase 6 webhook) is its own plan; 3a-2 is what gives it a marker to send.
