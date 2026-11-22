# Credits 3c: `AUTO` markers from version 2 — plan

Written 2026-11-22, after 3b merged (#282–#288), and revised the same day after thirteen adversarial reviews. It
specifies 3c of `credits-phase3-plan.md`, whose "3c" section lists what this spec had to settle. Where this file and
`credits-detection-plan.md` differ on `AUTO`, this file wins.

## Decisions (owner)

- **2026-11-22: "Yes"** to 3c, version 2's detections as `AUTO` markers, ahead of L6a (still held). `AUTO` is
  therefore tied to this plan's gate, not to `credits-classifier-plan.md`'s version 3 gate.
- **2026-11-22: films added after the gate get `AUTO` automatically**, without a stills check of their own. This was
  first asked with a wrong claim (that version 2 was never more than 15 s early), then asked again with the right
  figure from the recorded breach, and the answer stood. That figure stays off this public file, as the breach's
  counts do ("Not looked at before L5" in the classifier plan). The 20 s margin is added to every `AUTO` marker, and
  the owner fixes any film with **Set**. **Read as covering every `AUTO` set after the switch-on** that G1 never
  saw: new films, a replaced file re-analysed, a film whose Plex row went stale or unusable, a film that leaves the
  deny list under a new manifest. All of them are the same unchecked case the owner accepted.
- **2026-11-22: the "zero early against Plex" check of `credits-phase3-plan.md`'s outline is dropped.** Its only
  role was to tell whether films nobody checks can take `AUTO`, and the owner answered that with the breach's figure
  in hand. Every film the switch-on marks is still checked by stills (G1). Version 2's test-split figure in that bin
  is already public (L5: 0 of 238 more than 15 s early), so development counts against Plex, here or in the
  follow-up, reveal nothing new about the test split.
- **2026-11-22: "good enough for v1 but needs to be better."** After the gate, the development rows version 2 has
  early against Plex are looked at, and an approach to resolve them is brought to the owner ("Follow-up").

## What changes for the owner

Of the 256 editions with no usable Plex marker at the first 3a pass, those with a version 2 crawl or card result
that passes the guard get their end overlay, and count as watched, 20 s after where version 2 saw the credits start,
instead of at 97%. About 30% of films fall on the classifier's test side and keep the 97% fallback ("The test
split"), so roughly 120–160 editions are expected; G1 gives the real count. Admin → Library → End credits shows them
as **Detected**. In-progress states are not re-evaluated when a marker lands (as in 3a).

## The rule (`CreditsApply`, step 3)

Per edition, the first that holds wins (steps 1, 2 and 4 are unchanged from 3a):

1. source `OWNER`: unchanged.
2. a usable Plex row (`PlexState.USABLE`): Plex's time, source `PLEX`.
3. **`AUTO`**, only when the switch is on, there is a **deny list in force** (below), and the edition is not denied
   ("The test split"), and the film is matched (`film.tmdb_id` not null: an unmatched copy or cut has a `title:`
   unit key that the deny rules can't tie to a listed film), from the last part's `credits_detection` row when it:
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

**The deny list in force** is the one read at the start of this pass; if that read fails (the file missing,
unreadable or malformed), the last copy read successfully since start-up, in full, and the pass logs that it fell
back; if there is none (the container started with a bad file), there is no list in force, so no `AUTO` is set and
`AUTO` editions are cleared until a readable file is back (temporary; the log says why). Clear and the candidates
never read the file: they use the list in force of the latest pass. **When the switch is off**, `AUTO` editions fall through to step 4 and are cleared.

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
  4. the `media_file_id` of any of its parts is listed;
  5. the edition itself is listed;
  6. a film listed as `film` (only test-side films are) has, today, the same unit key as its film (computed in the pass, which reads every
     film's unit key inputs). This follows a test film that joins a collection: L1 keeps it, and every new film of
     its new unit, on test.

  Rules 3–5 hold when a listed film is merged into another (`LibraryIngest.merge` moves its editions to the survivor
  and deletes it) or is deleted while a sibling of its unit arrives later.
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
  - every film the newest manifest puts on test, with its unit key, **every one of its editions and every file of
    them** (the ids come from a read-only query the owner runs at G0, given the film ids and answering edition and
    `media_file_id`s only);
  - for a unit that the manifest has on both sides or excludes as `SPLIT_CONFLICT`: its unit key and every file of
    its films, and `film` entries for every film whose manifest side is test, a `SPLIT_CONFLICT` film's reference
    side included (later L1 runs keep that side), so rule 6 follows test films only;
  - any edition G1 judges unclear, as an `edition` entry and a `file` entry for each of its parts (file ids survive
    a re-arrangement or a merge), never `film`, so rule 6 doesn't take its collection.

  **Films G1 looks at never join the test split later.** The planner keeps a private list (off the repository) of
  every film G1 judged and every film the follow-up looked at. The next L1 run takes it as an exclusions list: a
  listed film that would land on test (carry-over rule 2 puts a unit's newcomers on its members' side) is excluded
  with the reason `LOOKED_AT` instead, so no unit is split, and the run reports how many. The list is given to **every** L1 run from now on,
  and the version 3 gate scores only test films that are not excluded. 3c-4 builds this, before the next L1 run.
  `credits-classifier-plan.md`'s "The split" says so too.

  At G0 the planner's script also checks, offline, that the hash side agrees with the manifest for every manifest
  film that carry-over did not move.
- So no test-side film, old or added later, is marked `AUTO`, shown as Detected, or named in the candidates list.
- **A new manifest** (a later L1 run) means a new deny file, written the same way, before the next look at
  anything.

## The switch and the deny file

- Properties, documented in `application.properties`:
  - `cinefilm.credits.auto.enabled`: default **false**, and false in the test profile;
  - `cinefilm.credits.auto.cards`: default true;
  - `cinefilm.credits.auto.denylist`: a path, empty by default; empty is treated as unreadable, so the app boots
    before G0.
- `.env` keys: `CINEFILM_CREDITS_AUTO_ENABLED`, `CINEFILM_CREDITS_AUTO_CARDS`, `CINEFILM_CREDITS_AUTO_DENYLIST`.
- **Where it lives:** 3c-1b adds a read-only bind mount to `deploy/docker-compose.yml`:
  `${CINEFILM_DATA_DIR:-/home/gregochr/cinefilm-data}/credits-auto` to `/credits-auto`, the same default as every
  other mount. The file is `/credits-auto/deny.txt`; `operations.md` creates the directory readable by the
  container's user (uid 1001). Nothing else from `~/cinefilm-labels/` goes into the app container.
- **The format:**
  - the first line is the header `# cinefilm-deny <manifest date> <count>`;
  - then one entry per line: `film <id>`, `unit <unit key>`, `file <media_file_id>` or `edition <id>`;
  - blank lines and further `#` lines are ignored.
  - A missing or wrong header, a count that differs from the entries, or any other line makes the file malformed.
- **When it is read:** at the start of every pass, **whatever the switch says**, and kept as the list in force, so
  the candidates work at G0 and G1 with the switch off. The pass logs the header's date and count, never an entry.
  **After every replacement** of the file, the owner recreates the container (`operations.md`), which runs a pass
  at start-up.

## Triggers, logs

- `CreditsDetected`, a new event, is published by `CreditsDetectionService` **after it releases the lock**. It is
  published for every stored row that is a version 2 `FOUND` or replaces one (a re-analysis under a new fingerprint
  with the same result included), and never for a save that stored nothing (`DROPPED`, `BUSY`) or another verdict
  over another verdict. It asks the applier for a pass, as the 3a-2b triggers do: one more pass at most.
- **`FilmMatchFixed`** (Fix match, which can change a film's TMDB id, title, year, collection or owning film) also
  asks for a pass, so a re-matched or joined film's deny rules apply at once. 3c-1c also checks for any other path
  that changes those fields without a scan, Plex import, Edit editions save or Fix match, and adds it the same way.
- The pass's log line gains the number of editions that are `AUTO` after the pass, changed or not (as the `PLEX`
  count is). Once per pass it says why `AUTO` is off when it is (the switch is
  off, or the deny list is unreadable). Never a title or an id.

## The gate (before the owner switches `AUTO` on)

**Pre-registered here, before any sheet is seen.** Only pass or fail goes into this file, never a title. Each step
waits for the release it needs:

- **G0, the deny file** (after 3c-1d is released). The planner writes it ("The test split"). The owner copies it to
  the server and sets `CINEFILM_CREDITS_AUTO_DENYLIST`, and recreates the container; the switch stays off. The
  start-up pass's log line must show the header's date and count.
- **G1, every candidate by stills** (after 3c-2 is released).
  - The candidates list (3c-1d) names every edition step 3 would mark, computed as if the switch were on and as if
    the source were null, so editions the owner set are included (flagged `owner`). It covers the same editions as
    the pass, **hidden editions included**.
  - The 3c-2 tool writes one sheet per candidate. Its nine tiles are, in edition time: the start − 10 s, the start,
    the start + 10 s, the marker, and the marker + 15, + 30, + 60, + 120 and + 240 s. Tiles 1–3 are context.
  - **Story picture** means picture with no credit text on it. Credits over picture are never story picture.
  - The planner judges each sheet, in this order:
    1. **early:**
       - tile 4 or 5 shows story picture or story text (an epilogue card) and no credits; or
       - story picture or story text appears in tiles 4–9 before the first tile that is credits only; or
       - story picture appears in tiles 6–9 after credits were seen while the marker is more than 15 minutes before
         the edition's end (text in the story, not a mid-credits scene);
    2. **unclear:** story picture after credits were seen with the marker 15 minutes or less from the end (a
       mid-credits scene), credits over picture, outtakes, anything that is neither clearly credits nor black, text
       the planner can't read at sheet size (a full-size still may be taken first), or a candidate the tool could
       not read;
    3. otherwise **clear**.
  - An unclear edition goes on the deny list (its `edition` and `file` entries), and nothing else changes.
  - Any early card turns cards off. **Any early crawl ends 3c: `AUTO` stays off altogether.**
- **An end is final:** retrying a kind that a gate ended needs a revised plan and a fresh gate. Denying an early
  film and switching on anyway is not allowed.
- **G2, switching on** (after 3c-3 is released, so Admin shows Detected).
  - The planner gives the owner the final deny file.
  - The owner copies it, sets `CINEFILM_CREDITS_AUTO_ENABLED=true` (and `CINEFILM_CREDITS_AUTO_CARDS=false` if G1
    said so), and recreates the container.
  - After the start-up pass's log line, the owner fetches the candidates again (they are computed from live data,
    so later passes don't change the check). Every
    pair `(editionId, markerMs)` among them **not** flagged `owner` must be one G1 judged clear (G1's clear pairs
    include those judged for owner-flagged candidates; cards only while cards are on). A candidate that dropped out
    (a Plex marker arrived, say) is fine. There is no count to match: the candidates and the pass are the same code,
    pinned by 3c-1d's integration test.
  - **A pair G1 did not judge clear:** the owner switches off and recreates, G1 judges every such pair, and G2 is
    run again.

## Follow-up (owner, 2026-11-22)

After G2, the owner runs a read-only query, joined on a list the planner writes from the manifest and labels alone
(the development films' labelled files that no deny rule holds for), that lists the development rows version 2 has
more than 15 s before Plex. No test row is produced. Each is looked at: its stills around version 2's start and Plex's, and its reasons. The cause is found, and an approach is put to the owner before anything is built. Any change to the
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
      - rule 6 following a `SPLIT_CONFLICT` film listed by its reference side;
      - each of the six deny rules, rule 5 with an edition moved by a merge and rule 6 with a new film sharing a
        listed film's current unit key;
      - an unmatched film (`tmdb_id` null) never `AUTO`;
      - an unclear edition's file moved to a new edition stays denied;
      - the switch off clearing `AUTO`;
      - a list in force that is an older copy, applied in full (a film moved into a listed unit since is cleared);
        no list in force clearing `AUTO`.
    - The parser's test: the header, the count, the four entry kinds, comments, and malformed lines.
    - `CreditsSplitTest`: the product's unit key equals L1's for the same fixture rows.
- **3c-1b, wiring.**
  - The properties, the compose mount and the file reader.
  - The pass's batched read gains the `credits_detection` rows and the unit key's inputs (including every listed
    film's current unit key, for rule 6), with a fixed number of queries per batch.
  - The Clear plumbing: `CreditsAdminService.clear` (and 3c-1d's candidates) take the list in force that the applier
    kept from its latest pass (held in memory, shared with the pass, never re-read from the file), and read live the
    detection row, the switch, the cards flag, the film's unit key inputs and `tmdb_id`, and every listed film's
    current unit key, through the same code as the pass.
  - Log fields.
  - Tests:
    - `CreditsApplierTest`: the log counts, including a second pass that changes nothing and still logs the full
      `AUTO` count; the header's date and count with no entry or id in the log; each reason `AUTO` is off (the
      switch, no list in force, both together); a pass with the switch off still reading the file, logging its
      header and keeping it in force.
    - `CreditsAdminServiceTest`: Clear landing on `AUTO`; Clear with an older copy in force landing on `AUTO` from it; Clear with no list in force
      landing on step 4; after a pass, the file's contents replaced with no pass run, Clear still answering from
      the pass's copy without calling the reader; Clear landing on step 4,
      not `AUTO`, for an edition denied by rule 2, by rule 6, and for an unmatched film.
    - The reader's test, an empty path counting as unreadable.
    - The list in force, in the reader's test and `CreditsApplierTest`: a good read then a bad one (the second pass
      applies the first copy in full and logs the fall-back); a bad read at start-up (`AUTO` cleared, the log says
      why); bad then good (the new copy is used); no log carries an entry.
    - `CreditsApplierIntegrationTest`: an `AUTO` set and kept, a hidden edition marked, the query count per batch
      fixed.
- **3c-1c, the trigger.** `CreditsDetected`, published by `CreditsDetectionService` after the lock, and its
  listener, and `FilmMatchFixed` asking for a pass. Tests: `CreditsDetectionServiceTest` (published after the lock;
  for a version 2 `FOUND`; for an `UNSURE` or `NOT_FOUND` replacing a version 2 `FOUND`; the same result under a
  new fingerprint publishes; nothing for `DROPPED`, `BUSY`, or another verdict replacing another verdict) and `CreditsApplierTest` (each event asks for a pass).
- **3c-1d, the candidates and the runbook.**
  - `GET /api/admin/credits/auto-candidates` returns
    `[{editionId, filmId, mediaFileId, path, offsetMs, kind, startMs, markerMs, owner}]`:
    - `path` is relative to `/media`;
    - `offsetMs` is file time, for the tool;
    - `startMs` and `markerMs` are edition time.

    It is computed by the same code, over the same editions as the pass (hidden ones included). It answers 409 "No
    deny list" only when there is no list in force. It goes in `foundation-plan.md` and `RouteSecurityDriftTest`.
  - Tests: the service and controller tests (401, missing CSRF, the 409 with no list in force, a 200 on an older copy, the
    file replaced with no pass run still answered from the pass's copy, and a 200 after a switch-off start-up pass), and an
    integration test where one fixture's candidates not flagged `owner` equal the editions a pass then sets to
    `AUTO`, with hidden and owner editions, and cards on and off.
  - `operations.md`:
    - the G0 edition query and the follow-up query;
    - the deny file's place, keys, and the recreate after each replacement;
    - fetching the candidates;
    - the tool's command;
    - switching on and off.
- **3c-2, the sheet tool** (`credits.learn`, a `main` run with `docker run`, `/media` mounted read-only).
  - It reads the candidates JSON and decodes each file with `CreditsFrameReader` (ffmpeg), turning G1's edition
    times into file time.
  - It writes a 9-tile `ContactSheet` per candidate (G1's tiles) in a fresh `auto-check-<UTC time>/` directory.
    A tile past the file's end is black, and the run's list says so.
  - With `--full <editionId> <tile>` it writes one full-size still.
  - A file it can't read is listed by edition id and skipped, and the run still exits 0.
  - Tests: the tile times, a two-part edition's file time, a tile past the file's end (black, and in the list),
    `--full`, the directory refusal, a missing file, and the smoke test of the documented command.
- **3c-3, Admin.**
  - `CreditsFilter.AUTO`, `counts.auto`, and the Detected tab (between From Plex and Yours).
  - `AUTO` rows show their time (3b showed none).
  - The Clear hint becomes "Goes back to Plex's marker, the detected time, or none".
  - Version 2's verdict and reasons stay unshown. `foundation-plan.md`.
  - Tests: backend, the filter and counts; frontend, the tab, the times and the exact hint.
- **3c-4, `LOOKED_AT` in L1** (any time before the next L1 run; nothing in 3c waits for it).
  - `LabelsMain` gains `--looked-at <file of film ids>`. `CreditsSplit.assign` takes the set and checks it on each
    film's final side, after the carry-over rules or the hash chose it (never inside one branch). A listed film that
    would land on test is excluded with the new `ExcludeReason.LOOKED_AT`, and the manifest records the side it
    would have had (so the deny file's `film` entry and rule 6 still follow it). The report counts them; a film whose file is already excluded for an
    earlier reason keeps that reason and is counted apart.
  - Tests in `CreditsSplitTest` and the L1 job's test: a looked-at film landing on test through carry-over rule 2 is
    excluded and counted; one landing on test by the hash alone (a unit with no earlier members) likewise; one landing
    on dev is unaffected; one already excluded keeps its reason; on a later run (carry-over rule 1) it stays excluded.
- **Then the gate** (G0–G2), then the owner switches `AUTO` on. No release is tagged by a session.

## Not in this plan

- Version 3 (L6a) and `credits-classifier-plan.md`'s gate stay held; that gate still decides any later `AUTO` from
  version 3.
- CineLight's fade from the marker (the Phase 6 webhook).
