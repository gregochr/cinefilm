# Credits 3c: `AUTO` markers from version 2 — plan

Written 2026-11-22, after 3b merged (#282–#288), and revised the same day after two adversarial reviews. It
specifies 3c of `credits-phase3-plan.md`, whose "3c" section lists what this spec had to settle. Where this file and
`credits-detection-plan.md` differ on `AUTO`, this file wins.

## Decisions (owner)

- **2026-11-22: "Yes"** to 3c, version 2's detections as `AUTO` markers, ahead of L6a (still held). `AUTO` is
  therefore tied to this plan's gate, not to `credits-classifier-plan.md`'s version 3 gate.
- **2026-11-22: films added after the gate get `AUTO` automatically**, without a stills check of their own. This was
  first asked with a wrong claim (that version 2 was never more than 15 s early), then asked again with the right
  figure from the recorded breach, and the answer stood. That figure stays off this public file, as the breach's
  counts do ("Not looked at before L5" in the classifier plan). The 20 s margin is added to every `AUTO` marker, and
  the owner fixes any film with **Set**.
- **2026-11-22: "good enough for v1 but needs to be better."** After the gate, the development film G1 finds early
  is looked at, and an approach to resolve it is brought to the owner ("Follow-up").

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
3. **`AUTO`**, only when the switch is on, the deny list was read for this pass, and the edition is not denied
   ("The test split"), from the last part's `credits_detection` row when it:
   - has verdict `FOUND`, a non-null `offset_ms`, and `analysis_version` exactly **2**
     (`CreditsApply.AUTO_ANALYSIS_VERSION`);
   - has a fingerprint equal to the last part's current fingerprint;
   - has a crawl or card reason: its first reason starts "Text on a dark screen moves up steadily" (crawl) or "Cards
     of text on a dark screen change" (card), the two `CreditsFinder` openings of a `FOUND`. Any other reason, no
     `AUTO`. Cards count only while `cinefilm.credits.auto.cards` is true.

   The time: `start = EditionTimeline.toGlobal(lastIndex, offset_ms)` (a refusal means no `AUTO`), and
   `total = EditionTimeline.of(parts).totalMs()` (unknown means no `AUTO`). **The start itself must pass
   `EditionGuard.plausible(start, total)`**, the check the product's own guard applies to a detection, so a start the
   guard would call unsure never marks. Then `marker = min(start + 20 000, total − 60 000)`, which is never below
   `start` because the start passed the guard.
4. otherwise both cleared (the 97% fallback).

**When the deny list can't be read** (switch on; the file missing, unreadable or malformed): no new `AUTO` is set.
An edition already `AUTO` keeps its marker only if step 3, with the deny file's own checks left out (the hash check
still applies), gives exactly the same marker; otherwise it is cleared. So a bad file never clears good markers to
put them back later, and never keeps one that went stale (a changed file, a re-analysis, a new arrangement).
**When the switch is off**, `AUTO` editions fall through to step 4 and are cleared.

**Raising `ANALYSIS_VERSION` clears `AUTO`:** there is one row per file, so a version 3 backfill overwrites the
version 2 rows and each `AUTO` marker falls to 97% as it goes. The version 3 plan must keep version 2's rows or
gate first; that is recorded here so it is not found out in production.

**Admin.** Clear (the `DELETE`) re-applies the edition as if its source were null, so it lands on `AUTO` when step
3 holds. Clear still shows only on a "Yours" row. **Undoing an `AUTO` marker is Set** (any later time); switching
`AUTO` off undoes them all. There is no "no marker" action and no **Use detected** (Clear is it).

## The test split

The test split is not looked at by 3c, and its count of looks stays at two (L5 and the version 3 gate). L1's
manifest lists only films with a Plex row, but the split rule gives every film a side, so the product works the
side out itself.

- **An edition is denied** when any of these holds:
  1. its film is listed in the deny file;
  2. the hash of its film's unit key gives test;
  3. its film's unit key is listed;
  4. its last part's `media_file_id` is listed.

  Rules 3 and 4 hold when a listed film is merged into another (`LibraryIngest.merge` moves its editions) or is
  deleted while a sibling of its unit arrives later.
- **The unit key's inputs are L1's**, read the way L1's export query reads them:
  - the film's collection's TMDB id (`film_collection.tmdb_id`, through `film.collection_id`);
  - `film.collection_id`;
  - `film.tmdb_id`;
  - `film.title`;
  - `film.release_year`.

  They go through `CreditsSplit.unitKey` and the hash. 3c-1a makes them public and pins, with a test, that the
  product's unit key equals L1's for the same fixture rows. A pre-seeded film whose hash says test is denied too,
  which only costs `AUTO`.
- **The deny file** is written by the planner from the manifest and L1's labels alone, before any version 2 result is
  read, and is kept off the repository (it is library data). It lists:
  - every film the newest manifest puts on test, with its unit key and its labelled `media_file_id`;
  - every film, unit key and file of a unit that the manifest has on both sides or excludes as `SPLIT_CONFLICT`;
  - any film G2 judges unclear.

  At G0 the planner's script also checks, offline, that the hash side agrees with the manifest for every manifest
  film that carry-over did not move.
- So no test-side film, old or added later, is marked `AUTO`, shown as Detected, or named in the candidates list.
- **A new manifest** (a later L1 run) means a new deny file, written the same way, before the next look at
  anything.

## The switch and the deny file

- Properties, documented in `application.properties`:
  - `cinefilm.credits.auto.enabled`: default **false**, and false in the test profile;
  - `cinefilm.credits.auto.cards`: default true;
  - `cinefilm.credits.auto.denylist`: a path, no default.
- `.env` keys: `CINEFILM_CREDITS_AUTO_ENABLED`, `CINEFILM_CREDITS_AUTO_CARDS`, `CINEFILM_CREDITS_AUTO_DENYLIST`.
- **Where it lives:** 3c-1b adds a read-only bind mount to `deploy/docker-compose.yml`:
  `${CINEFILM_DATA_DIR}/credits-auto` to `/credits-auto`. The file is `/credits-auto/deny.txt`. Nothing else
  from `~/cinefilm-labels/` goes into the app container.
- **The format:**
  - the first line is the header `# cinefilm-deny <manifest date> <count>`;
  - then one entry per line: `film <id>`, `unit <unit key>` or `file <media_file_id>`;
  - blank lines and further `#` lines are ignored.
  - A missing or wrong header, a count that differs from the entries, or any other line makes the file malformed.
- **When it is read:** at the start of each pass. The pass logs the header's date and count, never an entry.
  **After every replacement** of the file, the owner recreates the container (`operations.md`), which runs a pass
  at start-up.

## Triggers, logs

- `CreditsDetected`, a new event, is published by `CreditsDetectionService` **after it releases the lock**. It is
  only published when the saved row is a version 2 `FOUND` or replaces one. It asks the applier for a pass, as the
  3a-2b triggers do: one more pass at most. A re-analysis of unchanged results starts no passes.
- The pass's log line gains the `AUTO` count set. Once per pass it says why `AUTO` is off when it is (the switch is
  off, or the deny list is unreadable). Never a title or an id.

## The gate (before the owner switches `AUTO` on)

Run once, after 3c-1c is released and with the switch still off. **Pre-registered here, before any result.** Only
pass or fail goes into this file, never a count of G1, and never a title.

- **G0, the deny file.**
  - The planner writes it (above), and a list of **allowed** `media_file_id`s: the development films' labelled
    files that no deny rule holds for, from the manifest and labels alone.
  - The owner copies the deny file to the server and sets `CINEFILM_CREDITS_AUTO_DENYLIST`; the switch stays off.
- **G1, against Plex, development films only.**
  - The owner runs the read-only `psql` script in `operations.md` (3c-1c). It loads the allowed list into a
    temporary table and selects, joined on it, the version 2 `FOUND` rows whose file has a Plex row with the same
    fingerprint and `offset_ms < start_ms − 15 000`: `media_file_id` and the first reason, nothing else. So no test
    row and no count over test files is ever produced. The script prints no row count of anything else.
  - **Pass: at most 1 row in total**, crawl and card together. The one allowed is the film the 2026-11-22 decision
    accepted.
  - **Fail ends 3c without `AUTO`.** Any retry needs a revised plan and a fresh gate.
- **G2, every candidate by stills.**
  - The candidates list (3c-1c) names every edition step 3 would mark, computed as if the switch were on and as if
    the source were null, so editions the owner set are included (flagged `owner`).
  - The 3c-2 tool writes one sheet per candidate. Its nine tiles are, in edition time: the start − 10 s, the start,
    the start + 10 s, the marker, and the marker + 15, + 30, + 60, + 120 and + 240 s. Tiles 1–3 are context; tiles
    4–9 are judged.
  - The planner judges each sheet:
    - **early:** any of tiles 4–9 shows the film: picture, or story text such as an epilogue card;
    - **unclear:** anything that is neither clearly credits nor black, or text the planner can't read at sheet size
      (a full-size still may be taken of any tile before deciding);
    - otherwise **clear**.
  - An unclear film goes on the deny list, and nothing else changes.
  - Any early card turns cards off. **Any early crawl ends 3c without crawl `AUTO`.**
- **An end is final:** retrying a kind that a gate ended needs a revised plan and a fresh gate. Denying an early
  film and switching on anyway is not allowed.
- **G3, switching on.**
  - The planner gives the owner the final deny file.
  - The owner copies it, sets `CINEFILM_CREDITS_AUTO_ENABLED=true` (and `CINEFILM_CREDITS_AUTO_CARDS=false` if G2
    said so), and recreates the container.
  - The pass that follows logs the `AUTO` count, which must equal G2's clear count less any candidate the owner set
    (those stay `OWNER`).
  - **A different count:** the owner switches off and recreates. The candidates are fetched again, and G2 covers
    every new one before switching on again.

## Follow-up (owner, 2026-11-22)

After G1, the development film it finds early is looked at: its stills around version 2's start and Plex's, and its
reasons. The cause is found, and an approach is put to the owner before anything is built. Any change to the
detector is a new analysis version, so it comes with the version 3 rule above (keep version 2's rows or gate first).

## PRs

Each at most 1,500 changed lines, tests included.

- **3c-1a, pure and inert** (nothing calls it yet).
  - Step 3 in `CreditsApply`, with the unreadable-list case.
  - The deny file parser, pure.
  - `CreditsSplit`'s unit key and hash side made public.
  - Tests:
    - `CreditsApplyTest`:
      - each condition of step 3, both reason openings and another reason;
      - cards off, version 1 and 3 rows, a stale fingerprint;
      - the start at the guard's lower bound and just under it, the cap at `total − 60 s`, `toGlobal` refusing;
      - a two-part edition;
      - `OWNER` and `PLEX` still winning;
      - each of the four deny rules;
      - the switch off clearing `AUTO`;
      - an unreadable list keeping a still-valid `AUTO` and clearing a stale one.
    - The parser's test: the header, the count, the three entry kinds, comments, and malformed lines.
    - `CreditsSplitTest`: the product's unit key equals L1's for the same fixture rows.
- **3c-1b, wiring.**
  - The properties, the compose mount and the file reader.
  - The pass's batched read gains the `credits_detection` rows and the unit key's inputs, with a fixed number of
    queries per batch.
  - `CreditsDetected`, published after the lock, and its listener.
  - The Clear plumbing: `CreditsAdminService.clear` gets the detection row, the switch, the cards flag and the deny
    list, through the same code as the pass.
  - Log fields.
  - Tests:
    - `CreditsApplierTest`: the event triggers a pass, the log counts, and the header's date.
    - `CreditsDetectionServiceTest`: published after the lock, only for a version 2 `FOUND` or a replaced one.
    - `CreditsAdminServiceTest`: Clear landing on `AUTO`, and Clear with the list unreadable.
    - The reader's test.
    - `CreditsApplierIntegrationTest`: an `AUTO` set and kept, with the query count per batch fixed.
- **3c-1c, the candidates and the runbook.**
  - `GET /api/admin/credits/auto-candidates` returns
    `[{editionId, filmId, mediaFileId, path, offsetMs, kind, startMs, markerMs, owner}]`:
    - `path` is relative to `/media`;
    - `offsetMs` is file time, for the tool;
    - `startMs` and `markerMs` are edition time.

    It is computed by the same code. It answers 409 "No deny list" when the file can't be read. It goes in
    `foundation-plan.md` and `RouteSecurityDriftTest`, with the service and controller tests (401 and missing CSRF).
  - `operations.md`:
    - the G1 script;
    - the deny file's place, keys, and the recreate after each replacement;
    - fetching the candidates;
    - the tool's command;
    - switching on and off.
- **3c-2, the sheet tool** (`credits.learn`, a `main` run with `docker run`, `/media` mounted read-only).
  - It reads the candidates JSON and decodes each file with `CreditsFrameReader` (ffmpeg), turning G2's edition
    times into file time.
  - It writes a 9-tile `ContactSheet` per candidate (G2's tiles) in a fresh `auto-check-<UTC time>/` directory.
    A tile past the file's end is black, and the run's list says so.
  - With `--full <editionId> <tile>` it writes one full-size still.
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
