# Credits 3c: `AUTO` markers from version 2 — plan

Written 2026-11-22, after 3b merged (#282–#288), and revised the same day after an adversarial review. It specifies
3c of `credits-phase3-plan.md`, whose "3c" section lists what this spec had to settle. Where this file and
`credits-detection-plan.md` differ on `AUTO`, this file wins.

## Decisions (owner)

- **2026-11-22: "Yes"** to 3c, version 2's detections as `AUTO` markers, ahead of L6a (still held). `AUTO` is
  therefore tied to this plan's gate, not to `credits-classifier-plan.md`'s version 3 gate.
- **2026-11-22: films added after the gate get `AUTO` automatically**, without a stills check of their own. This was
  first asked with a wrong figure ("no result more than 15 s early"), then asked again with the right one, and the
  answer stood. The right figure: when the version 2 backfill finished (2026-11-15), **1** of the 847 files with both
  a Plex marker and a result was more than 15 s early. L5 found none on the test split, so it is a development film.
  That figure is already known from the recorded breach ("Not looked at before L5" in the classifier plan), and G1
  below only checks it has not grown. The 20 s margin is added to every `AUTO` marker. The owner fixes any film with
  **Set**.

## What changes for the owner

Of the 256 editions with no usable Plex marker at the first 3a pass, those with a version 2 crawl or card result
that passes the guard get their end overlay, and count as watched, 20 s after where version 2 saw the credits start,
instead of at 97%. About 30% of films fall on the classifier's test side and keep the 97% fallback ("The test
split"), so roughly 120–160 editions are expected; G2 gives the real count. Admin → Library → End credits shows them
as **Detected**. In-progress states are not re-evaluated when a marker lands (as in 3a).

## The rule (`CreditsApply`, step 3)

Per edition, the first that holds wins (steps 1, 2 and 4 are unchanged from 3a):

1. source `OWNER`: unchanged.
2. a usable Plex row (`PlexState.USABLE`): Plex's time, source `PLEX`.
3. **`AUTO`**, only when the switch is on, the deny list was read for this pass, and the film is not denied ("The
   test split"), from the last part's `credits_detection` row when it:
   - has verdict `FOUND`, a non-null `offset_ms`, and `analysis_version` exactly **2**
     (`CreditsApply.AUTO_ANALYSIS_VERSION`);
   - has a fingerprint equal to the last part's current fingerprint;
   - has a crawl or card reason: its first reason starts "Text on a dark screen moves up steadily" (crawl) or "Cards
     of text on a dark screen change" (card), the two `CreditsFinder` openings of a `FOUND`. Any other reason, no
     `AUTO`. Cards count only while `cinefilm.credits.auto.cards` is true.

   The time: `start = EditionTimeline.toGlobal(lastIndex, offset_ms)` (a refusal means no `AUTO`), then
   `marker = min(start + 20 000, total − 60 000)`, where `total` is the edition's `timelineDurationMs` (none means
   no `AUTO`). If `marker < start` (the start is itself in the last 60 s) or `EditionGuard.plausible(marker, total)`
   fails, no `AUTO`. So an `AUTO` marker is never earlier than the detected start.
4. otherwise both cleared (the 97% fallback).

**When the deny list can't be read** (switch on; the file missing, unreadable or malformed): no new `AUTO` is set.
An edition already `AUTO` keeps its marker only if step 3, with the deny list's own check left out (the hash check
still applies), gives exactly the same marker; otherwise it is cleared. So a bad file never clears good markers to
put them back later, and never keeps one that went stale (a changed file, a re-analysis, a new arrangement).
**When the switch is off**, `AUTO` editions fall through to step 4 and are cleared.

**Raising `ANALYSIS_VERSION` clears `AUTO`:** there is one row per file, so a version 3 backfill overwrites the
version 2 rows and each `AUTO` marker falls to 97% as it goes. The version 3 plan must keep version 2's rows or
gate first; that is recorded here so it is not found out in production.

**Admin.** Clear (the `DELETE`) re-applies the edition as if its source were null, so it lands on `AUTO` when step
3 holds. Clear still shows only on a "Yours" row. **Undoing an `AUTO` marker is Set** (any later time). There is no
"no marker" action and no **Use detected** (Clear is it).

## The test split

The test split is not looked at by 3c, and its count of looks stays at two (L5 and the version 3 gate). L1's
manifest lists only films with a Plex row, but the split rule gives every film a side, so the product works the
side out itself:

- **A film is denied** when any of these holds:
  - it is listed in the deny file;
  - the hash of its unit key gives test (`CreditsSplit`'s unit key and hash, the same rule L1 uses; 3c-1a makes
    them public);
  - another film with the same unit key is listed in the deny file.
- **The deny file** lists every film the newest manifest puts on test, every film of a unit that the manifest has on
  both sides or excludes as `SPLIT_CONFLICT`, and any film G2 judges unclear. It is written by the planner from the
  manifest alone, before any version 2 result is read, and kept off the repository (it is library data).
- So no test-side film, old or added later, is marked `AUTO`, shown as Detected, or named in the candidates list.
  A pre-seeded film whose hash says test is denied too, which only costs `AUTO`.
- G1 reads only rows more than 15 s early and drops test-side rows before counting.
- **A new manifest** (a later L1 run) means a new deny file, written the same way, before the next look at
  anything.

## The switch and the deny file

- Properties, documented in `application.properties`:
  - `cinefilm.credits.auto.enabled`: default **false**, and false in the test profile;
  - `cinefilm.credits.auto.cards`: default true;
  - `cinefilm.credits.auto.denylist`: a path, no default.
- `.env` keys: `CINEFILM_CREDITS_AUTO_ENABLED`, `CINEFILM_CREDITS_AUTO_CARDS` and `CINEFILM_CREDITS_AUTO_DENYLIST`.
- **Where it lives:** 3c-1a adds a read-only bind mount to `deploy/docker-compose.yml`:
  `${CINEFILM_DATA_DIR}/credits-auto` to `/credits-auto`. The file is `/credits-auto/deny.txt`. Nothing else
  from `~/cinefilm-labels/` goes into the app container.
- **The format:**
  - the first line is the header `# cinefilm-deny <manifest date> <count>`;
  - then one `film_id` per line;
  - blank lines and further `#` lines are ignored.
  - A missing or wrong header, a count that differs from the ids, or any other line makes the file malformed.
  - It is read at the start of each pass. The pass logs its count, never its ids.

## Triggers, logs

- `CreditsDetected`, a new event, is published by `CreditsDetectionService` **after it releases the lock**. It is
  only published when the saved row is a version 2 `FOUND` or replaces one. It asks the applier for a pass, as the
  3a-2b triggers do: one more pass at most. A re-analysis of unchanged results starts no passes.
- The pass's log line gains the `AUTO` count set. Once per pass it says why `AUTO` is off when it is (the switch is
  off, or the deny list is unreadable). Never a title or an id.

## The gate (before the owner switches `AUTO` on)

Run once, after 3c-1a and 3c-1b are released and with the switch still off. **Pre-registered here, before any
result:** the results go into this file as counts, crawl and card apart, never titles.

- **G0, the deny file.** The planner writes it from the manifest (above). The owner copies it to the server and
  sets `CINEFILM_CREDITS_AUTO_DENYLIST`; the switch stays off.
- **G1, against Plex, development films only.**
  - The owner runs a read-only `psql` export from `operations.md` (3c-1b). It selects only version 2 `FOUND` rows
    whose file has a Plex row with the same fingerprint and `offset_ms < start_ms − 15 000`, with `film_id` and the
    first reason. Nothing else.
  - The planner's script reads the manifest and the deny rules first and prints only the development counts, crawl
    and card apart.
  - **Pass: at most 1**, the film already known.
  - A count above 1 for cards turns cards off. A count above 1 for crawls ends 3c without crawl `AUTO`.
- **G2, every candidate by stills.**
  - The candidates list (3c-1b) names every edition step 3 would mark, computed as if the switch were on and as if
    the source were null, so editions the owner set are included (flagged `owner`).
  - The 3c-2 tool writes one sheet per candidate.
  - The planner judges tiles 5 to 9 of each sheet, from the raw start to the marker:
    - **early:** any of those tiles shows the film: picture, or story text such as an epilogue card;
    - **unclear:** anything that is neither clearly credits nor the black leading into them;
    - otherwise **clear**.
  - An unclear film goes on the deny list and nothing else changes.
  - Any early card turns cards off. **Any early crawl ends 3c without crawl `AUTO`.**
- **An end is final:** retrying a kind that a gate ended needs a revised plan and a fresh gate. Denying the early
  film and switching on anyway is not allowed.
- **G3, switching on.** The planner gives the owner the final deny file. The owner copies it, sets
  `CINEFILM_CREDITS_AUTO_ENABLED=true` (and `CINEFILM_CREDITS_AUTO_CARDS=false` if a gate said so), and recreates
  the container. The pass that follows logs the `AUTO` count, which should equal G2's clear count, less any
  candidate the owner set (those stay `OWNER`).

## PRs

Each at most 1,500 changed lines, tests included.

- **3c-1a, apply (off by default).**
  - Step 3 in `CreditsApply`, with the stale-safe unreadable case.
  - The properties, the compose mount, and the deny file parser (pure) and reader.
  - The hash check (`CreditsSplit`'s unit key and side made public); `Film`'s tmdb, collection and title/year
    columns are read in the pass's batch.
  - The pass's batched read gains the `credits_detection` rows, with a fixed number of queries per batch.
  - `CreditsDetected` and its listener.
  - The Clear plumbing: `CreditsAdminService.clear` gets the detection row, the switch, the cards flag and the deny
    list, through the same code as the pass.
  - Log fields.
  - Tests:
    - `CreditsApplyTest`:
      - each condition of step 3, both reason openings and another reason;
      - cards off, version 1 and 3 rows, a stale fingerprint;
      - the cap at `total − 60 s`, `start` in the last 60 s, the guard's lower bound, `toGlobal` refusing;
      - a two-part edition;
      - `OWNER` and `PLEX` still winning;
      - each of the three deny rules;
      - the switch off clearing `AUTO`;
      - an unreadable list keeping a still-valid `AUTO` and clearing a stale one.
    - The parser's test: header, count, comments and malformed lines.
    - `CreditsApplierTest`: the event triggers a pass, and the log counts.
    - `CreditsDetectionServiceTest`: published after the lock, only for a version 2 `FOUND` or a replaced one.
    - `CreditsAdminServiceTest`: Clear landing on `AUTO`, and Clear with the list unreadable.
    - `CreditsApplierIntegrationTest`: an `AUTO` set and kept, with the query count per batch fixed.
- **3c-1b, the candidates and the runbook.**
  - `GET /api/admin/credits/auto-candidates` returns
    `[{editionId, filmId, mediaFileId, path, offsetMs, kind, startMs, markerMs, owner}]`:
    - `path` is relative to `/media`;
    - `offsetMs` is file time, for the tool;
    - `startMs` and `markerMs` are edition time.

    It is computed by the same code. It answers 409 "No deny list" when the file can't be read. It goes in
    `foundation-plan.md` and `RouteSecurityDriftTest`, with the service and controller tests (401 and missing CSRF).
  - `operations.md`: the G1 query, the deny file's place and keys, fetching the candidates, the tool command, and
    switching on.
- **3c-2, the sheet tool** (`credits.learn`, a `main` run with `docker run`, `/media` mounted read-only).
  - It reads the candidates JSON and decodes each file with `CreditsFrameReader` (ffmpeg), in file time.
  - It writes a 9-tile `ContactSheet` per candidate, from 20 s before to 20 s after the raw start in 5 s steps
    (tile 5 is the start). A tile past the file's end is black and says so in the run's list.
  - Output goes in a fresh `auto-check-<UTC time>/` directory.
  - A file it can't read is listed by edition id and skipped, and the run still exits 0.
  - Tests: the tile times, a two-part edition's file time, the directory refusal, a missing file, and the smoke test
    of the documented command.
- **3c-3, Admin.**
  - `CreditsFilter.AUTO`, `counts.auto`, and the Detected tab (between From Plex and Yours).
  - `AUTO` rows show their time (3b showed none).
  - The Clear hint becomes "Goes back to Plex's marker, the detected time, or none".
  - Version 2's verdict and reasons stay unshown. `foundation-plan.md`.
  - Tests: backend, the filter and counts; frontend, the tab, the times and the exact hint.
- **Then the gate** (G0–G3), then the owner switches `AUTO` on. No release is tagged by a session.

## Not in this plan

- Version 3 (L6a) and `credits-classifier-plan.md`'s gate stay held; that gate still decides any later `AUTO` from
  version 3.
- CineLight's fade from the marker (the Phase 6 webhook).
