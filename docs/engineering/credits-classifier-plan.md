# Credits detection: a learned classifier — plan

Written 2026-11-13 and revised four times the same day, after four adversarial reviews that were checked against the
code. It extends `credits-detection-plan.md`. Storage, split editions, the window, the edition guard, the watched rule
and phase 3's applying mechanism (precedence `OWNER > PLEX > AUTO > 97%`) are unchanged. Two things do change:
- **which `AUTO` results phase 3 may use** is decided by this plan's gate, by opening type and margin band, instead of
  2b's crawl/card kinds;
- **L6a stores both values** as columns on `credits_detection`. It replaces the rule-based classifier
and start search ("Per-frame features", "Finding the start", "The motion check") with a model learned from Plex's
markers.

## Why

The owner, 2026-11-13: "Our tool just needs to be better. Plex looks like the benchmark." The first three films the
2b detector analysed in production, compared with Plex:

| Film | Detector | Plex | Our frame | Plex's frame |
|---|---|---|---|---|
| *Wicked* | 2:33:32 | 2:30:49 | credit cards (+163 s) | the "To Be Continued" title |
| *Gladiator II* | 2:18:41 | 2:18:47 | the last shot, a dark hand in grass (−6 s) | first credits on black |
| *Civil War* | 1:48:00 | 1:41:12 | logos on black (+408 s) | "Written and directed by" on a light grey card |

Plex was right all three times. Each miss traces back to a hand-written rule. Plex is being retired, after which the
detector is the only source, so **a future film whose credits are dark text on white must work** (owner,
2026-11-13). The owner chose the learned route the same day.

## How credits open in this library (measured 2026-11-13)

For each of the 849 labelled films, the centre of the frame (80% × 60%, grey) at Plex's start + 5, 20 and 45 s, using
ffmpeg `signalstats` on the server, read-only:

| Opening | Films | Examples |
|---|---|---|
| **Dark screen** (mean luma below 35 at +5 s) | 568 (67%) | what the rule detector was built for |
| **Flat light plate** (mean ≥ 120, 10th–90th percentile spread ≤ 40, in one of the three) | 19 (2%) | *Civil War*, *Black Swan*, *A Man Called Otto*, *Despicable Me 4*, *Spider-Man: No Way Home*, *Matilda*, *Wallace and Gromit* |
| **Lit and textured**: credits over picture, stylised end-title sequences | 262 (31%) | *Cars*, *Bee Movie*, *Deadpool 2*, *Barbie*, *Free Guy*, *Black Panther* |

The bigger gap is credits over picture, not white backgrounds. "Labels" says how those are treated.

**Recomputed on development films (2026-11-17)** with L3a's definition ("L3a specification"), on the stored
full-range frames inside the crop: of the 609 development films, 368 (60%) open dark, 17 (3%) on a plate and 224 (37%) lit. These
figures replace the table above, which used limited-range luma, a centre crop and all films.

## What is captured, and what must happen before Plex is retired

- **`plex_credits_marker`** (V24): 849 rows, one per media file, covering **849 editions of 842 films**. Each holds the
  earliest credits start, shifted onto CineFilm's copy, plus the copy's fingerprint.
- **The full Plex archive**, taken 2026-11-13, read-only and GET-only (`/library/metadata/{key}?includeMarkers=1` for
  every movie item):
  - 1,087 items, 866 with credits markers;
  - every marker's start, end and `final` flag, and each item's Media and Part file paths and durations;
  - 25 MB, with no token in it.
  - **Layout:** `sections.json`; `section-<id>.json` (the `/all` list, in list order); `item-<ratingKey>.json` (one
    item's metadata with `Marker` and `Media`/`Part`).
  - **Where it is kept:**
    - `~/cinefilm-labels/plex-raw-2026-11-13/` on the server, outside the cache;
    - the same path on the owner's Mac, off-host;
    - older copies under `~/cinefilm-data/cache/credits-dataset/` and `~/credits-dataset-backup/`.

  It is the only label source once Plex is gone, and **it is never deleted**. For example, *Captain America: Civil
  War* (`item-120.json`) has three credits markers: 2:11:12–2:12:50, 2:14:18–2:20:26, and a final one at 2:21:26.
  - Two items (`item-215`, *Forrest Gump*; `item-749`, *Third Person*) failed on the first pass and were fetched
    again the same day. All three copies now hold 1,087 items, 866 with credits markers, and no `.tmp` files.
  - **Data facts checked in review 5** (every item and export row parsed):
    - every marker has `type` equal to `"credits"` and an `endTimeOffset`; 141 items have more than one;
    - every marker carries the same `id` (79833), so marker ids are never used as keys;
    - 32 joinable films have no `final` marker and 27 have two, so `final` is ignored;
    - all 849 export rows join after the re-fetch and reproduce the import's shift exactly, every shift is under 1 s, and no earliest
      marker falls before the window.
- **Before the owner retires Plex, in this order.** Only these steps need Plex, and none can be done afterwards.
  Nothing here waits for L1 to be built.
  1. (optional, owner's call because it is a non-GET action in Plex) Plex runs credits detection on its unmarked
     items. **Steps 2–4 wait until Plex's background analysis has finished.**
  2. In one sitting, the planner re-takes the archive the same way, as `plex-raw-<date>/`.
  3. Still in that sitting, the planner re-runs the 2c "Import credits from Plex" action, so films added since
     2026-11-13 get `plex_credits_marker` rows. L1 joins from those rows, and phase 3's `PLEX` precedence needs them.
     The planner then checks the import report's counts against the archive's count of items with credits markers.
  4. Still in that sitting, the planner takes the database export (below), so the archive, the rows and the export
     agree.
  5. All three are copied to the owner's Mac.

  The labels file and the split manifest are rebuilt from those frozen inputs whenever needed, and backed up the same
  way once they exist.
- **Done on 2026-11-13:** the export below, taken read-only (`export-2026-11-13.csv`, 849 rows), with its SQL in
  `export.sql` beside it, in `~/cinefilm-labels/` on the server and the Mac. All 849 computed fingerprints equal the
  stored `plex_fingerprint`. 397 of the 849 files (842 films) belong to a collection.
- **The version 2 baseline.** The 2b detector's rows (`ANALYSIS_VERSION` 2) are the baseline. When the v2 backfill
  finishes, and again before any version 3 release is deployed (the refresher starts overwriting on startup), the
  planner runs a read-only `\copy (select * from credits_detection) to …` into `~/cinefilm-labels/`.
- **Disk:** the server's root disk has 61 GB free (`df -h /home/gregochr/cinefilm-data`, 2026-11-13), and the host has
  15 GB RAM and 8 cores. The free-space figure in `operations.md` is out of date.

## Goals and benchmark

- **Plex is the benchmark.** The error is the detector's offset minus Plex's earliest credits start on the same file.
  `UNSURE` counts as `NOT_FOUND` everywhere.
- **The gate:** no `FOUND` result more than 15 s early, with the 20 s applied margin still landing after Plex. Early
  is the failure that matters, so this has no tolerance.
- **Goals, reported by opening type** (dark, light plate, lit), never as one blended figure:
  - at least 90% within ±10 s for dark and light-plate openings;
  - at least 97% within ±60 s, or not found, for dark and light-plate openings;
  - lit openings are reported as they come out, and are late by design (see "Labels").
- **The baseline**, version 2, is scored on the same films, at L5, from its stored `offset_ms` for both its early
  and goal errors. Version 3's early figure is worst case (`t − 5 s`), so the early comparison favours version 2 by
  `f − (t − 5 s)`: 5 s when the dense step falls back to `t`, up to about 30 s when it finds an `f`. Version 3's
  real dense earliness is measured only at the gate. It is not scored before then
  ("Not looked at before L5").
- **Nothing is installed** on the Mac, the server or in CI. Anything new is a Maven dependency or a file. Training and
  the product share one implementation of features, preprocessing and inference.

## Labels (`CreditsLabels`, L1)

- **Inputs** (arguments to L1's `main`; no database access and no secrets):
  - **the archive directory** (layout above). L1 parses the raw JSON itself, including `endTimeOffset` and `final`,
    because `PlexModels.PlexMarker` has no end;
  - **a read-only export the planner makes.** It is a CSV with a header and these columns, in this order:
    `media_file_id, path, fingerprint, duration_ms, edition_id, is_last_original_part, film_id, film_tmdb_id,
    film_title, film_year, collection_id, collection_tmdb_id, plex_start_ms, plex_fingerprint, presence`.
    - It is made by this exact command (`~/cinefilm-labels/export.sql`), run on the host as
      `docker exec -i cinefilm-db psql -U cinefilm -d cinefilm -v ON_ERROR_STOP=1 -q < export.sql > export-<date>.csv`.
      `TO STDOUT` lands the file on the host, not in the database container.
    - `fingerprint` reproduces `MediaFile.fingerprint()`. `duration_ms` is `MediaFile.timelineDurationMs()`, the one
      the product's window uses. `is_last_original_part` is `Edition.originalParts()`'s last file.

      ```sql
      \copy (WITH parts AS (SELECT m.id, m.edition_id, row_number() OVER (PARTITION BY m.edition_id
        ORDER BY m.part_number DESC, m.id DESC) AS rn_desc FROM media_file m WHERE m.kind = 'ORIGINAL')
      SELECT m.id AS media_file_id, m.path,
        m.size_bytes || ':' || floor(extract(epoch FROM m.modified_at) * 1000)::bigint AS fingerprint,
        coalesce(nullif(m.video_duration_ms, 0), m.duration_ms) AS duration_ms, m.edition_id,
        (p.rn_desc = 1) AS is_last_original_part, f.id AS film_id, f.tmdb_id AS film_tmdb_id,
        f.title AS film_title, f.release_year AS film_year, f.collection_id, c.tmdb_id AS collection_tmdb_id,
        k.start_ms AS plex_start_ms, k.fingerprint AS plex_fingerprint, m.presence
      FROM plex_credits_marker k JOIN media_file m ON m.id = k.media_file_id JOIN edition e ON e.id = m.edition_id
      JOIN film f ON f.id = e.film_id LEFT JOIN film_collection c ON c.id = f.collection_id
      LEFT JOIN parts p ON p.id = m.id ORDER BY m.id) TO STDOUT WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
      ```

      (one line in the file; wrapped here.) A row with `is_last_original_part` false (`f`) or `presence` not
      `PRESENT` is left out with a reason;
  - **the previous split manifest**, optional (see "The split");
  - **a pre-seed list** of `film_id`s forced into development (see "The split");
  - **the Plex path prefix** (`cinefilm.plex.path-prefix`), normalised through `PlexRules.prefix()`.
    `PlexRules.relativePath` returns empty without it. It applies NFC to the Plex side only, so L1 applies NFC to the export path itself;
  - **an exclusions file** (`media_file_id`, reason), for Plex errors found later;
  - **the run date.**
- **Matching.** It re-uses the decided matching, with no re-pairing.
  - Each exported row is joined to the archive item whose Part file is the same relative path
    (`PlexRules.relativePath`).
  - A path that matches more than one archive item (for example, duplicates across sections) is left out with a
    reason.
- **The shift is recomputed, never inferred.**
  - From the archive item's durations, exactly as `PlexCreditsRules.java:82-97` does: `max(0, matched
    Media.duration − min(item.duration, every Media.duration))`. The item's own duration takes part in the minimum.
  - `PlexCreditsRules` and `decide` are package-private in `plex`, so L1 copies the formula as written. A fixture
    covers an item whose own duration is the shortest.
  - It must equal `plex_start_ms − (the item's earliest credits start)` to within 1 ms, and lie in [0, 4 s].
  - Otherwise the file is left out with a reason, because the markers changed between the import and the archive.
  - The shift is applied to every marker's start and end.
- **Also left out with a reason:**
  - a file whose current fingerprint differs from `plex_fingerprint`;
  - a file that is not its edition's last original part;
  - a file with no archive item.
- **Only markers with `type` equal to `"credits"` count.** Intro, commercial and any other types are ignored
  everywhere, as in `PlexCreditsRules.earliestCredits`. Overlapping or touching credits markers are merged first. A
  credits marker with no `endTimeOffset` excludes the file, with a reason.
- **Frame labels** at offset `t` in the file:
  - inside a merged credits marker: credits;
  - before the earliest one: film;
  - between two of them (a mid-credits scene): film;
  - after the last one's end: left out, whatever its `final` flag.
- **Left out:**
  - frames within ±5 s of any merged credits boundary (the fade, rounding, and up to about 4 s of copy shift);
  - every frame of a film whose earliest marker is before the analysis window.
- **Credits over picture** are left out, decided now. **Plain frames** are frames whose `OpeningType.frameKind`
  (below) is `DARK` or `PLATE`, so text on a light card counts as plain and is kept. Inside a credits marker, the
  frames left out are:
  - every frame before the first 10 s run of plain frames;
  - any run of 15 s or more of `LIT` frames.

  The rule also applies to the following:
  - the film's start remains a target. These films are reported as "lit opening": the model is expected to be late
    on them, by the length of the sequence;
  - the reason: frames of lit picture labelled credits would teach the model that picture can be credits, which is
    what makes a climax score as credits;
  - this flag needs frames, so it is computed in L3 from the stored frames, not in L1;
  - a fixture covers a plate film (*Civil War*-like), whose plate frames must stay labelled credits;
  - **one variant is tested in cross-validation**: including the lit frames that also show text. It is a training run
    judged by L3c-eval's nested selection ("Calibration and nested selection").
- **Plex errors.** The development films with the largest errors are looked at as stills. A clear Plex error is left
  out through the exclusions file (its reason lands in the labels' `excluded_note` when `OWNER_EXCLUDED` is the
  recorded reason, and always in the report), and is never relabelled by guesswork.
- **The output** goes to `~/cinefilm-labels/labels-<date>.json`. That is library data, so it is never committed (the
  repository is public). Its schema is one object per file:
  - `media_file_id`, `fingerprint`, `unit_key`, `side` (`dev` or `test`), `fold` (0–4, development only);
  - `target_start_ms`, `shift_ms`;
  - `credits` and `film` (lists of `[from_ms, to_ms)` intervals);
  - `left_out` (a list of `{from_ms, to_ms, reason}`);
  - `excluded` (a reason code, or null, for a file left out entirely), and `excluded_note` (the owner's free-text
    reason for `OWNER_EXCLUDED`, else null).

  The opening type and the end-title flag are added by L3, and never by hand.

### Opening type (`OpeningType`, pure, L3a)

This is one definition, used the same way by labels, reports, the product's reasons text and the gate. It is
computed on `FramePrep.grey` frames (full range) inside `ActiveCrop`, never on the product's `format=gray` frames
(limited range, where `P50 < 4` never fires). L6a tests that the stored `opening_type` comes from the `FramePrep`
path:

- **`frameKind(frame)`** ("L3a specification"). `DARK` was re-derived on development frames, replacing P0's mean
  with a median. `PLATE` was mapped from P0 and confirmed on development frames:
  - `DARK` when the median luma is below 4;
  - `PLATE` when the mean is at least 121 and the 10th–90th percentile spread is at most 47;
  - `LIT` otherwise.
- **`OpeningType.of(start)`** uses the coarse frames nearest to `start` + 5, 20 and 45 s:
  - `DARK` if the +5 s frame is `DARK`;
  - otherwise `PLATE` if any of the three is `PLATE`;
  - otherwise `LIT`.
- The P0 table was measured with ffmpeg on the centre crop. L3a recomputed it with this definition, on development
  films only (above, under the P0 table).
- **Margin bands** band `S(t, u*)` at the coarse `t` from steps 2–4 of "Finding the start", before the dense step,
  in units of the calibrated `σ`: `[σ, 2σ)` is
  `low`, and `≥ 2σ` is `high`.

### The split

- **The unit key**, the first that applies:
  - `ctmdb:<collection_tmdb_id>`, then `cid:<collection_id>`, when the film belongs to a collection;
  - `tmdb:<film_tmdb_id>`;
  - `title:<t>|<year>`, where `t` is the title in Unicode NFC, lowercased with `Locale.ROOT`, keeping only
    characters matching `[\p{L}\p{N}]`, and the year is empty when null.
- **The hash:** the first 8 bytes of SHA-256 over the unit key's UTF-8 bytes, read as an unsigned number. Mod 100 below
  70 is **development**, otherwise **test**.
- **The fold** of a development unit is the same hash mod 5.
- **The manifest** (`split-<date>.json`, written by L1) records each `film_id` with its unit key, side and fold. When
  a previous manifest is given:
  - a film listed there keeps its side and fold;
  - a new film whose unit already has a member in the manifest takes that member's side and fold;
  - a film listed with a different unit key now (it joined a collection) keeps its old side, and so does every new
    film of its new unit. A conflict between members, where two old films now share a unit but sit on different
    sides, excludes the newcomers with a reason, never moves an old film.
- **The pre-seed list** forces films already examined while designing the detectors into development, together
  with their whole units (a film already in a previous manifest keeps its recorded side, carry-over rule 1):
  - *Wicked*, *Gladiator II* and *Civil War* (this plan);
  - every film named in `credits-detection-plan.md`'s phase 1 and 2 measurements.

  Their `film_id`s are listed in `~/cinefilm-labels/preseed.txt`, written by the planner before L1's first run.
- **One file per edition**: the last part, which is the one the product analyses.
- **Development** uses 5-fold cross-validation, with folds made from the same units. Every model and threshold
  choice is made there.
- **The product is a 5-fold ensemble.** Each model is trained on development minus one fold, and the ensemble's `p`
  is the mean of the five models' `p`.
- **The thresholds are tuned on ensemble-type predictions.** For outer fold `k`, the prediction is the mean of the
  four models trained without fold `k`, each trained without `k` and one other fold. The thresholds are tuned on
  these out-of-fold ensemble predictions, so averaging affects tuning exactly as it affects the product.
- **The 10-film feature measurement (L3a) and every stills review use development films only.**
- **The test split is looked at twice in total:**
  1. after the comparison (L5);
  2. at the version 3 gate.
- **The test rule is pre-registered.** An early result on the test split turns `AUTO` off for that opening type and
  margin band. Early means more than 15 s before the target, the gate's threshold: scored on `earlyError`
  (`t − 5 s`) offline at L5, and on the product's rows at the gate. It is never retuned and scored again. Test films are never relabelled, so a Plex mistake on a test
  film can switch `AUTO` off for its band. That cost is accepted, and is put to the owner with the L5 report.
- **Thresholds keep a safety margin:** the earliest out-of-fold error must be at least −5 s. Offline it is scored
  at `t − 5 s` ("How a film is scored"), so it amounts to `t ≥ target` on every development film the evaluation scores `FOUND`.
  The calibration ranks this margin first (L3c's ranking, levels 1–2), so it always makes a choice. A choice that
  misses the margin on any development film is not shipped without the owner's decision: it is reported with the
  L5 report.
- **Held-out early films** (added 2026-11-19, for the owner to decide at L5). The margin rule above covers a
  choice's own counts, not the nested evaluation's held-out rows (each film scored with its fold's choice). Held-out
  films early by more than 5 s or 15 s are reported to the owner with the L5 comparison, and nothing ships at L6a
  with a held-out film early by more than 15 s without the owner's decision.

## Frames: the dataset job (L2)

### What is read and stored

For each labelled file, under `/app/cache/credits-dataset/frames/<media_file_id>/`:

- the active area (`ActiveArea.of`, the five 480 px frames at 30–60% of the part);
- the window;
- the coarse frames: single-frame seeks every 5 s across the window;
- the dense frames: 2 fps from the target start − 30 s to + 30 s;
- `complete.json`, holding the fingerprint, the ffmpeg version, the git SHA and the settings.

**One path, shared Java code.**

- ffmpeg writes **one `rgb24` PPM at 480 px** per seek to the job's temp directory, so the share is read once.
- Everything after that is shared Java code in `credits.learn.FramePrep`, used the same way by the dataset job and
  the product:
  - **grey** = the BT.601 integer luma of each pixel, `(77R + 150G + 29B) >> 8`, then a 2×2 box average to 240 px.
    It is stored as lossless PNG.
  - **colour** = the active area cropped at 480 px coordinates, then squashed to 224 × 224 by `FramePrep`'s own
    bilinear resampler (not `java.awt` scaling), then encoded as JPEG by ImageIO at quality 0.95. It is stored as that
    JPEG.
  - The product encodes and decodes the same JPEG in memory before the encoder. So training and the product see
    identical bytes, with one round trip each.
  - The 2b rule classes are not used by the model. Its grey comes from `FramePrep`, not from ffmpeg's
    `format=gray`.

**Size:**

- planned at about 850 files × ≤ 540 frames × (about 15 KB grey + 25 KB colour) ≈ 18 GB;
- **measured on 10 films first**, with the per-frame budget adjusted before the full run;
- **the job stops when free disk falls below 15 GB.** That leaves room for the 5 GB HLS cache cap and the
  pre-release dumps. L6b deletes the frames.

### How it runs (inside the server, because only the server can see playback)

- **Switching it on.** `CreditsDatasetJob` sits behind `cinefilm.credits.dataset.enabled` (default off), with admin
  start, stop and status endpoints, documented in `foundation-plan.md`.
  - It auto-resumes after a restart only when its `running` marker is on disk.
  - The planner starts it after the v2 backfill has finished. The status endpoint shows the refresher's count of due
    files that are not held back, and that count must be 0.
- **Targets** (which files, and each file's target start for the dense range) come from `plex_credits_marker` in
  the database, with the same fingerprint. The labels file is not needed on the server.
- **No `LibraryLock`.** The job writes no library rows, and the lock is never held while ffmpeg runs
  (`credits-detection-plan.md`). Instead:
  - before each file, and after its last ffmpeg call, it checks that the file is still `PRESENT` with the same
    fingerprint;
  - resume skips a file only when `complete.json` has the current fingerprint.
- **One file per executor task on `creditsExecutor`**, re-submitted after each one.
  - It yields while the refresher has due files that are **not held back** by its failure back-off, so newly added
    films are analysed first. `CreditsRefresher` exposes that count. One unreadable file can therefore never stall
    the job.
  - Its playback checkpoint is `checkPlayback`, extracted from `CreditsRefresher` into a shared `PlaybackGuard` and
    called before every ffmpeg call.
  - When playback blocks it, the job goes `WAITING`, and `CreditsDatasetScheduler`'s hourly nudge resumes a `WAITING`
    run.
- **Niceness:** set for each job (default 19). It is passed into the reader, which today takes niceness only from
  `cinefilm.transcode.nice`.

### Measuring (L2b-2)

`credits.learn.DatasetMeasureMain` is a plain class run as a throwaway container from the released image (the
`docker run` block under "Where the code lives, and tool runs", with `-Dloader.main=...DatasetMeasureMain`). Its
arguments are `--data /data --labels /labels/labels-<date>.json --out /out [--ids 5,7,12] [--stills] [--project 850]`.

- **It measures development films only, and never test films: it has no option to.** `--labels` is L1's
  `labels-<date>.json`. A film directory is opened only when its label has `side` `dev` and no exclusion. Every other
  directory (test side, excluded, or not in the labels) is never opened: no `complete.json` read, no file decoded, no
  row. `summary.json` holds only counts of them by reason (`skipped`: `test`, `excluded`, `unlabelled`): **no skipped
  id of any kind is written anywhere**, because an unlabelled directory may belong to a test film and an exclusion
  may sit in a test unit. `--ids` narrows within the development films, and an id of another kind is counted the
  same way. The L5 look (`--look=L5`) belongs to the evaluation tool, and the gate's look is a database query. The plan's looks log, under "Phases",
  records them.
- **It reads only `--data` and `--labels`** (`--data` mounted `:ro`) and writes one directory of its own,
  `<out>/measure-<yyyyMMdd'T'HHmmss'Z'>/` (made with `createDirectory`; a run never overwrites or deletes another, and
  never leaves stale stills). `--out` must not be inside `--data`. No network, no database.
- **Four questions** before the full run and L3a:
  1. Are the frames complete and decodable? `films.csv` has one row per film, `status` `ok`, `incomplete` (no
     usable format-1 `complete.json`), `mismatched` (it names another file) or `damaged`, and counts of `missing`,
     `corrupt` (unreadable, not decodable, or the wrong size), `extra` files and `duplicates` (an offset listed twice,
     measured once; any of missing, corrupt or duplicates makes the film `damaged`). One bad frame file never stops the run; an
     unreadable `coarse/` or `dense/` directory does (exit 1).
  2. How big is a film's frame set? `pngBytes`, `jpgBytes`, `totalBytes`; `summary.json` projects the dataset as the
     `ok` films' mean size times `--project` (default 850).
  3. How long did a film take to read? `readSeconds` is an **estimate**: `complete.json`'s `writtenAt` minus the
     earliest modification time of the film's frame files, rounded down. It includes any wait for playback, and is
     blank when the times are missing or the result would be negative.
  4. What do the values look like around Plex's marker? `frames.csv` has one row per frame: its `fromTargetMs`,
     the grey mean, percentiles (nearest rank), the shares below 35 and above 200 (measurement placeholders, as L3a
     re-derives the real thresholds), and the colour channel means and chroma.
- **Stills** (`--stills`): per film, in `stills/<mediaFileId>/`, the dense frames nearest to target -30, -20, -10, -5,
  0, +5, +10, +20 and +30 s (`d-30s.jpg`/`.png` ... `d+30s`), copied unchanged, `stills.csv` (their offsets) and
  `sheet.png`, the nine grey frames in a 3 by 3 grid. A film with no dense frames has no stills and a `note`.
- **Exit codes:** 0 when it ran, even with damaged films (read the report); 1 on a bad flag, an unreadable `--data`
  or an `--out` inside it; 2 when `frames/` is missing or holds no film directory.

## Two model types

Both produce `p(credits)` for each frame, and both feed the same segmenter. They are compared on development
through **offline evaluation**: the shipping model and segmenter code, run on the stored frames.

- **How a film is scored** (`CreditsEvaluateMain`, L3c-eval). The dense search is simulated on the stored dense
  frames that have time features (`[target − 25 s, target + 25 s)`) and that also lie in the product's own range
  with time features, `[t − 10 s, t + 30 s)`.
  - **The search is covered** when `t − 5 s ≥ target − 25 s` and it stops at an `f` with
    `f + 5 s < min(target + 25 s, t + 30 s)`.
  - **The stored grid is not the product's.** The stored dense frames sit on a 0.5 s grid set by Plex's target, the
    product's on one set by `t`, and `t` is on the 5 s coarse grid while the target is in ms: the two are the same
    decoded frames almost never. A brief low frame seen by only one grid can move
    the result either way. So the simulated `f` is an estimate, never a safe bound.
  - **Early metrics** (the −5 s margin, the early counts) always use **`t − 5 s`**, the earliest the dense step could
    ever return: worst case, never favourable, with no exception.
  - **The ±10 s and ±60 s goals** use the simulated `f` when the search is covered (an estimate), and `t` otherwise.
    `θ_d` is tuned through these goals.
  - **So offline, the −5 s margin is a rule on the coarse step:** `t − 5 s − target ≥ −5 s`, that is `t ≥ target`,
    on every `FOUND` film (`UNSURE` counts as `NOT_FOUND`). It is stricter than the product needs and pushes calibration later, which "a late overlay is
    better than an early one" accepts. The dense step's own earliness is measured only at the gate ("The gate,
    revised"), on the product's version 3 rows.
- **Neither model has a position feature.** Nothing tells a model how far into the film a frame is. That is a rule.

### A. Gradient-boosted trees on measured features

- **Features (`CreditsFeatures`).** Measured on the 240 px grey frame inside the active area, treating light and dark
  alike:
  - background plainness;
  - ink, both polarities;
  - thin sharp runs, text rows, central and bottom-band shares, line spacing;
  - edge energy, and a 4×4 layout grid;
  - two time features against the frames ±5 s away: mean absolute difference and changed-ink share. At the window's
    ends, a coarse frame uses the one neighbour for both. A dense frame has no time features without both
    neighbours ("L3a specification"). There is no crawl-shift feature, because at a 5 s spacing it aliases.
- **The planner owns the exact feature list and formulas.** It is written into L3a's brief after measuring 10
  films; Sonnet implements it and does not design it.
- **Trees (`GradientBoostedTrees`).** Logistic loss, shallow trees, quantile bins, unweighted classes.
  - Depth, count and learning rate are arguments of L3b's training `main`. Before L3c exists they are chosen by
    out-of-fold log loss on coarse rows; once L3c-eval exists, its nested selection ("Calibration and nested
    selection") chooses among training runs instead.
  - **Deterministic:** single-threaded; inputs sorted by `(mediaFileId, kind, offsetMs)`; ties broken by the lowest feature index,
    then the lowest threshold; quantiles from sorted values. A test trains twice and compares the JSON byte for
    byte.
- **Where it is computed:** features and training run in a **throwaway container** on the server, from the released
  image, reading the dataset read-only (see "Tool runs"). Only the model JSON comes back.

### B. Pretrained image embedding plus logistic regression

- **The encoder** is chosen by the planner in L4a. That happens after the L4b-2 release (L4b-2 ships the benchmark
  `main`) and before any product change. Candidates, in order:
  1. CLIP ViT-B/32's image tower, quantised, from the `Xenova/clip-vit-base-patch32` ONNX export (MIT, about
     90 MB, outputs `image_embeds`);
  2. a smaller MobileNet-class ONNX model from the ONNX Model Zoo (Apache 2.0), if CLIP is too slow or too large.

  **Chosen at L4a (2026-11-19, v0.9.1): MobileNet v2-12, fp32** (ONNX Model Zoo, `mobilenetv2-12.onnx`, SHA-256
  `c0c3f76d93fa3fd6580652a45618618a220fced18babf65774ed169de0432ad5`). Its output is the 1,000 ImageNet logits
  (tensor names `input` and `output`), normalised with mean [0.485, 0.456, 0.406] and standard deviation
  [0.229, 0.224, 0.225]. Measured with `CreditsEncoderBenchMain` in the tool-run container at `-Xmx512m`:

  | Candidate | Per frame | 520 frames | Peak RSS | Result |
  |---|---|---|---|---|
  | CLIP ViT-B/32 image tower, quantised | 294 ms | 153 s | 650 MB | rejected: over the 2-minute limit |
  | MobileNet v2-12, fp32 | 32 ms | 16.7 s | 459 MB | **chosen** |
  | MobileNet v2-12, int8 | 39 ms | 20.1 s | 339 MB | the fallback, if memory is tight at L6a |

  The int8 model is slower than fp32 on this CPU, so it is kept only for its smaller RSS.
- **L4a measures each candidate in a throwaway container on the server:**
  - the per-frame CPU time with one intra-op and one inter-op thread, and the arena off;
  - peak RSS.

  L4b's brief first checks that the pinned ONNX Runtime jar contains `osx-x64` natives, since both Macs are Intel.
  - **If it does:** the ONNX adapter is tested on the Mac as normal.
  - **If it doesn't:** the adapter is a one-method class behind an `ImageEncoder` interface. Everything else is
    tested on the Mac against a pure-Java stub. The adapter's own test runs in CI only, and the PR asks the owner to
    approve that class's exception to the local coverage floor.
- **The cost limit:** a candidate is rejected if a film's 520 frames take more than 2 minutes, or if RSS would push
  the server past `mem_limit: 2g` alongside a transcode.
- **Embeddings are computed in a throwaway container from the stored JPEGs**, and logistic regression is trained
  there. **The encoder file reaches the production image and the server only if B wins** (L6). The ONNX Runtime
  library itself is in every image built after L4b-1 merges, because tool runs use the released image; the server
  never loads it before L6a (owner, 2026-11-19: no separate tools image).
- **In the product:**
  - the encoder file sits outside the jar, fetched in the Dockerfile's runtime stage with
    `ADD --checksum=sha256:… <url>`;
  - unit tests use a tiny committed ONNX fixture;
  - the session is opened per analysis and closed afterwards, with the arena off;
  - the playback checkpoint runs between batches of 16 frames.

### Choosing

- The comparison uses **nested** cross-validation. The hyperparameters and thresholds for each outer fold are chosen
  on its inner folds only, so outer-fold results can be early.
- The ranking, in order:
  1. fewer outer-fold results earlier than −15 s;
  2. then fewer earlier than −5 s;
  3. then the larger **share within ±10 s on dark and light-plate openings**.
- **Stated limits:**
  - each out-of-fold ensemble's models are trained on 3 of 5 folds, while the product's are trained on 4, so the
    tuned thresholds are slightly conservative for the product;
  - the nested loop trains models without three folds as well, which is cheap for both model types.
- **One evaluation per model type.** L5 runs `CreditsEvaluateMain` once over model A's runs and once over model B's,
  and compares the two held-out summaries (each film scored with its fold's choice) by this ranking. One mixed
  selection would let the type come from the final choice, which is not nested, and mix types across outer folds.
  **Both of these are development-only runs (no `--look`).** Once the comparison is recorded, L5's single look is one
  more `CreditsEvaluateMain` run with `--look=L5 --baseline`, over the **winning type's runs alone**. The losing type
  is never scored on test films.
- **A tie goes to A**, which needs no encoder file in the image and no download.
- The loser's code is deleted in L6b.

## Finding the start (`CreditsSegmenter`, L3c)

**Scores.** Each coarse frame gets `ℓ_i = logit(p_i) − logit(π) − λ`:

- `π` is the training share of credits frames, so the prior cancels out;
- `λ ≥ 0` is a per-frame penalty that leans every decision late, chosen in cross-validation.

Against "all film", a stretch from `t` to `u` is worth `S(t, u) = Σ_{t≤i<u} ℓ_i`. Frames outside the stretch count
as film.

1. **Best stretch.** Find `(t*, u*)` with the largest `S`, at least 30 s long (the maximum-sum stretch, O(n)). Call
   its score `S*`.
2. **Lean late.** With `u*` fixed, the start `t` is the **latest** frame with `S(t, u*) ≥ S* − δ` and `u* − t ≥
   30 s`, where `δ ≥ 0` is chosen in cross-validation.
3. **Drop leading islands.** If, after `t`, the stretch holds a run of frames with `ℓ < 0` lasting `G` seconds or
   more, the part before the first such run is dropped unless it lasts 60 s or more. Then step 2 is applied again
   from the new start, and this repeats. `G` is chosen in cross-validation.
   - This handles one card, then picture, then credits.
   - It is late on Marvel-style "titles, then a scene, then the crawl", which the owner accepts as late.
4. **Confidence.** Both must hold, or the result is `UNSURE`:
   - `S(t, u*) ≥ σ`;
   - every coarse frame in the first 15 s from `t` (three frames) has `p ≥ θ`.
5. **Dense.** Decode 2 fps from `t − 15 s` to `t + 35 s` (100 frames).
   - The start is the first dense frame `f ≥ t − 5 s` such that **every** dense frame in `[f, f + 5 s]` has `p ≥ θ_d`.
   - A dense frame without time features has no `p`. This holds for both models, so that A and B search the same
     frames, although B's `p` needs no time features. An `f` is considered only when every dense frame in
     `[f, f + 5 s]` has a `p`.
   - If no `f` qualifies, the start is the coarse `t`, never earlier.
   - This `CreditsFrameReader` change (from 300 s to 50 s, with `DENSE_TIMEOUT` scaled down) belongs to L6a.
6. **Vetoes:**
   - the edition guard;
   - the dialogue check, where cue times are already known; it moves out of `CreditsFinder` in L6a;
   - only the last original part is analysed.
7. **Reasons text:** `Credits model v1 (A|B): credits from 2:18:47, <opening> (margin 41.2, min p 0.97 over 15 s)`.
   The margin shown is the raw `S(t, u*)` that the margin band uses.

**Calibration.** `λ`, `δ`, `G`, `σ`, `θ` and `θ_d` are chosen together by L3c's nested selection ("Calibration and
nested selection"), ranked by: fewer `FOUND` films early by more than 15 s, then by more than 5 s (scored at
`t − 5 s`, so it constrains the coarse step only), then fewer negative violations, then the larger ±10 s share
(where `θ_d` acts, through the simulated `f`).

- **Negatives come from real films.** Each labelled film's window is cut off at the target start − 5 s. The film
  is a violation when the best film-only stretch score there (stretches ending before start − 5 s) is `≥ 0.8σ`,
  which also covers a `FOUND` on the cut window.
- Negatives follow the same folds.

**Epilogue cards** that Plex itself counts as credits can't be seen by the Plex gate. The development films whose
worst-case start, `t − 5 s` (as every early check), lands more than 10 s before the first credits-on-plain
frame are looked at as stills and reported.

## The model file

- **What it holds:**
  - the type and the feature version;
  - the trees, or the encoder's SHA-256 and the regression weights;
  - the fold ensemble and the thresholds;
  - the git SHA and the dataset and manifest hashes.
- **Where it lives:** `backend/src/main/resources/credits/credits-model-v1.json`, loaded once.
- **The feature-version check** is tested in L3b against a fixture model. **L6a adds the CI unit test** that loads
  the committed file and checks its feature version against the code.
- **At runtime**, a mismatch or a missing encoder disables credits detection with an error log. The server still
  starts and plays.

## Where the code lives, and tool runs

- **All code is in `src/main/java/gregochr/cinefilm/credits/learn/`**, fully gated: Checkstyle, SpotBugs, JaCoCo
  per-class floors, diff-cover and the test-class check.
  - Each `main` is a thin wrapper over tested pure classes.
  - SpotBugs suppressions for path arguments state their reason.
  - Exact-value fixtures hold the numeric code to the PIT floor. If equivalent mutants make 98 unreachable, the PR
    names the exclusion and asks the owner.
- **Tool runs** (features, embeddings, training, evaluation) are a throwaway container from the released image, on the
  server:

  ```
  docker run --rm --network none --cpus 1 --cpu-shares 2 --memory 3g --entrypoint nice \
    -v ~/cinefilm-data/cache/credits-dataset:/data:ro -v ~/cinefilm-labels:/labels:ro \
    -v ~/cinefilm-data/cache/credits-dataset-out:/out \
    ghcr.io/gregochr/cinefilm:<tag> -n 19 java -Xmx2g \
    -Dloader.main=gregochr.cinefilm.credits.learn.<Main> \
    -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher <args>
  ```

  - The image runs as uid 1001. The output directory `credits-dataset-out` is created by the server (uid 1001)
    beside the dataset, and the planner copies results from it into `~/cinefilm-labels`.
  - **Every tool run needs a released image with that phase's code**, and only the owner tags releases. The owner
    releases after L1, L2b, L3b-look (the features tool and training), L3c, L4b-2 (the benchmark, for L4a) and
    L4b-6b (owner, 2026-11-19).

  - It has its own memory limit, separate from the server's, and one CPU at the lowest priority.
  - It reads only local files, never the share.
  - Nothing is installed: it is the same image.
  - The Mac has 17 GB free, so it does not hold the dataset.

## The gate, revised

This plan's gate (it replaces phase 2b's gate, which is never run) runs after the version 3 backfill.
- **It is a read-only database query**, of `credits_detection` against `plex_credits_marker`, not a tool run.
  - The database holds no side. So the planner exports the joined rows read-only (with `edition.film_id`) and joins
    them offline to `split-<date>.json`.
  - Test and development rows are reported separately, grouped by the stored `opening_type` and `margin_band`.
  - The planner logs the look by hand in the looks log: the date, the git SHA and the count of test files.
- **Until the gate,** no error of the product's version 3 rows (`credits_detection`, the backfill) against Plex is
  computed over test files, mirroring the v2 rule in "Not looked at before L5". L5's single offline look is the only
  earlier use of the test split.

The gate:

- **Against Plex:** zero `FOUND` rows more than 15 s before `plex_credits_marker` on the same file and fingerprint.
  - The figures for the **test split** decide. This is the second and last time the test split is looked at.
  - **An early row on a development film also blocks.** The product ensemble has seen those films, so an early one
    points to a bug where the product and the offline pipeline differ. It is fixed before anything is applied.
- **Films without a Plex marker** are where `AUTO` will actually be used. **Every** `FOUND` row among them (about 220
  at most) is checked by stills.
- **Split by opening type and margin band.** `AUTO` is used only where every check passed, under the pre-registered
  rule above.

## Phases

Each phase is its own small PR, built by Sonnet and reviewed by the planner (`CLAUDE.md`).

- **P0 (done, 2026-11-13):** the Plex archive, the opening measurement, and this plan.
- **L1 — labels.**
  - `CreditsLabels` (pure) and `CreditsSplit` (pure: the unit key, the hash, the manifest), plus a thin `main` that
    writes the labels file.
  - Fixtures are trimmed, anonymised archive items: multiple markers, a mid-credits scene, a copy shift, a marker
    before the window, a fingerprint mismatch, a missing item.
- **L2a — `FramePrep` and the reader's `rgb24` output.** The luma, the box downscale, the crop, the bilinear squash
  and the JPEG round-trip, all pure and tested with exact-value fixtures. A reader method that writes one `rgb24` PPM
  per seek. Niceness is passed per job. The product's existing grey path is unchanged.
- **L2b — the dataset job.** `PlaybackGuard` (extracted), `CreditsDatasetJob`, the endpoints, the disk floor, resume,
  and the yield to `dueFiles()`. **The planner then runs it on 10 films, measures, and runs the rest** (about 2–3
  days).
- **L3a — `OpeningType`, the end-title flag and `CreditsFeatures`**, to the planner's exact list ("L3a
  specification"), in four PRs, each done at merge:
  - **L3a-1a:** `ActiveCrop` and `OpeningType`, pure. Done when these tests pass:
    - the `ActiveCrop` tests: an odd left, an odd width, the 8 px throw, each input check at its boundary (a negative
      left or top, a zero width or height, an area one px past the frame), and a grey frame of the wrong size;
    - the `frameKind` boundary tests: one below, at and one above each threshold;
    - the nearest-frame tie tests.
  - **L3a-1b:** `CreditsFeatures`, pure. Done when every feature's exact-value tests and both property tests pass.
    Synthetic frames: text on black, text on white, white on light grey, a card change, a burned-in subtitle, a bright
    textured scene, a dark picture.
  - **L3a-2a:** `FrameLabels`, plus `LeftOutReason`'s `LIT_OPENING` and `LIT_RUN`. Done when the listed fixtures
    pass and L1's `LabelsJob` output is unchanged.
  - **L3a-2b:** the `CreditsFeaturesMain` tool. Done when a smoke test runs it on a fixture dataset. The fixture holds
    one `ok`, one `damaged` (wrong grey height), one `stale` (fingerprint differs from the label's), one
    `incomplete`, one `mismatched` and one `noDirectory` development film, and one test, one excluded (test-side) and one unlabelled directory. The run's
    `--ids` names all nine ids, so `noDirectory` is counted, and
    the excluded test-side record counts as `excluded`. The test proves that only the `ok` film reaches pass 2 (rows
    and a record are written for it alone), that the damaged film is opened only by the check, that no frame of the `stale` film is decoded, and that the test,
    excluded, unlabelled and `noDirectory` ids are never opened and never written. It asserts every count in
    `skippedByStatus`.
  - **The tool first runs after the L3b-look release,** before L3c is planned. That run must reproduce the planner's Python counts in "L3a
    specification" exactly: the openings, the coarse `LIT_OPENING`, `LIT_RUN` and kept counts, and the intervals
    left out whole. This is the check that the Java and the prototype agree.
    - **A mismatch blocks L5.** The planner diffs the results film by film, and a fix PR follows before any training
      run is used.
- **L3b — `GradientBoostedTrees`**, the model JSON, the determinism test and the feature-version test. Two PRs
  ("L3b specification"): L3b-1, the trees and the JSON; L3b-2, the training `main`.
  - **Training rows:**
    - Rows are sorted by `(mediaFileId, kind, offsetMs)` everywhere: in the CSV, in training and in the determinism
      test.
    - Rows labelled with a `left_out` reason, or with empty time features, are dropped.
    - **The base model trains on coarse rows only.** L3b's training `main` only exposes the variants, off by
      default: `--with-dense` (dense rows added) and `--lit-text-variant` ("lit frames that show text"). They are
      judged after L3c-eval exists, as training runs among which its nested selection chooses ("Calibration and
      nested selection").
  - **What the training `main` trains:** for each fold `k`, one model on development minus `k` (the five shipping
    models), and for each pair of folds `k ≠ j`, one model on development minus `k` and `j` (the out-of-fold
    ensembles: fold `k`'s prediction is the mean of the four models without `k`). It writes `oof-logloss.json`:
    the coarse-row log loss of each fold's out-of-fold ensemble on that fold, and pooled over all development rows.
    A test proves that no model used for fold `k`'s predictions saw a row of fold `k`.
  - **The model file's hashes:** `summary.json` supplies the dataset and labels hashes. The training `main` takes the
    split manifest as an argument and records its SHA-256.
  - **Test rows are refused:** the training `main` has no `--look`, rejects it as an unknown flag, and exits
    non-zero, writing no model, on the first row whose `side` is not `dev` or whose `fold` is empty. So L5 trains
    only on a run without `--look`. Its tests cover both.
- **L3b-look — `--look=L5` on `CreditsFeaturesMain`.** Its own small PR, merged
  before the L3b-look release (the release after it). Done when tests show:
  - without the flag, test directories are skipped and counted, never opened, exactly as in L3a-2b;
  - with it, test films are included: their rows have `side` `test` and an empty `fold`, their records go into
    `frame-labels.json`, and `summary.json` counts them separately;
  - excluded and unlabelled directories are never opened, with or without the flag. Exclusion is still checked before
    side (L3a-2b), and a fixture holds an excluded test-side record that is never opened under `--look=L5`;
  - `look.json` holds the flag, the UTC time, the git SHA and the count of test films, and no ids.
- **The looks log** (the planner fills it in from each `look.json`):
  - **L5, 2026-11-21:** `eval-20261121T095028Z`, `CreditsEvaluateMain --look=L5 --baseline` over model A's three
    runs, `toolGitCommit` `280593c`, 238 test films, on features run `features-20261120T224417Z` (also `--look=L5`,
    238 test films). Results under "L5: what the models showed".
  - Two looks count: `CreditsEvaluateMain`'s run with `--look=L5`, and the gate's database query ("The gate,
    revised").
- **L3c — `CreditsSegmenter`** (L3c-1, "L3c specification"). Tests:
  - a clean step;
  - a short `p ≈ 0.5` stretch early in the window before the real credits;
  - a mid-credits gap;
  - a post-credits scene;
  - an isolated false positive;
  - one card, then picture, then credits;
  - epilogue cards running into the credits;
  - no credits;
  - the dense fallback;
  - a dense range cut short at the file's end, so that no `f` has a `p` throughout `[f, f + 5 s]`.
- **L3c-eval — `CreditsEvaluateMain`,** the offline evaluation (the shipping model and segmenter code run on stored
  features, as "Two model types" describes). Two PRs, L3c-eval-1 and L3c-eval-2, merged before the L3c release.
  - **Specified in full in "L3c specification"** (2026-11-19): its arguments, the calibration and nested
    selection (`λ`, `δ`, `G`, `σ`, `θ`, `θ_d`, and the training run) owned by L3c-eval, and each fold's calibrated
    values in `calibration.json`. Also settled: a development film is scored only by models
    that did not train on it (its fold's out-of-fold ensemble) with that fold's calibration, never by the shipping
    ensemble; and the items below.
  - It refuses test films unless `--look=L5` is passed, and writes `look.json`, both as in L3b-look.
  - Tests cover the branches under "Two model types": a covered search (early metrics at `t − 5 s`, goals at
    the simulated `f`), including one on aligned grids, scored the same way; and a search not covered (early at
    `t − 5 s`, goals at `t`). One more test has `t` well before the target, where the stored frames alone would give a late `f` that the product
    could not reach.
  - **Its output,** `evaluation.csv`, has one row per film:
    - `mediaFileId`, `side`, `fold`;
    - `verdict` (`FOUND`, `UNSURE` or `NOT_FOUND`), `t` (empty when there is none), and the simulated `f` (empty
      when not covered);
    - `covered`, and `aligned` (for reporting only: `(t − offset₀) mod 500 ms = 0`, where `offset₀` is the film's first
      dense offset in `features.csv`; see "Scoring one film"); `earlyError` (`t − 5 s` against the target, always) and `goalError`
      (`f` against the target when covered, else `t`). `f`, `covered`, `aligned`, `earlyError` and `goalError` are
      empty unless the verdict is `FOUND`; an empty `goalError` counts as not found for the ±60 s goal and as outside
      ±10 s for the ±10 s goal. Each share's denominator is every film of that opening type (Plex's);
    - the opening type at `t` (from `OpeningType.of(coarse, t)`), the margin band, and Plex's opening type for the
      goals;
    - version 2's verdict and error against the same target, from `--baseline <the v2 snapshot csv>`, whose columns
      are `media_file_id, fingerprint, verdict, offset_ms, reasons, checked_at, analysis_version`. A row joins on
      `media_file_id` and `fingerprint`; with no matching row, both are empty. `--baseline` is refused (exit non-zero,
      nothing written) unless `--look=L5` is also passed, with a test. So the one `--look=L5` run scores the baseline
      too; v2 is never scored by hand, nor on development films as a set before L5.

    Its tests include a film whose opening type at `t` differs from the one at Plex's start.
  - Its run with `--look` is the one that counts as a look.
- **L4a — the encoder check (planner, no PR, after the L4b-2 release):** CPU and RSS for each candidate, measured with
  L4b's benchmark in a tool run.
- **L4b — model B's tool code:** ONNX Runtime, the embeddings, logistic regression and the benchmark `main` (CPU
  time and peak RSS), with a tiny ONNX fixture. No product wiring.
  - Its `main`s that read stored frames refuse test films unless `--look=L5` is passed, and write `look.json`,
    as in L3b-look.
  - **Specified in full in "L4b specification"** (2026-11-19), in nine PRs, L3c-grid and L4b-1 to L4b-6b. The
    owner releases after L4b-2 (L4a runs on it) and after L4b-6b.
- **L5 — the comparison (planner).** Tool runs, nested cross-validation, the choice, then the first look at the test
  split. One development-only evaluation per model type, compared on their held-out summaries ("Choosing"); then the
  single look, one `--look=L5 --baseline` evaluation of the winner alone (the `CreditsEmbedMain --look=L5` run only
  if B won). The results go into this file as "L5: what the models showed".
- **L6a — the model in the product.** **Held** (owner, 2026-11-21): v2 stays, see "L5: what the models showed".
  - The winner and the segmenter go in `CreditsDetectionService`.
  - The dense reader becomes 50 s, and the cue check moves.
  - `ANALYSIS_VERSION = 3`, with the model JSON committed.
  - If B won, the Dockerfile adds the encoder `ADD`.
  - The v2 snapshot is taken **before L6a merges**, so an unrelated release of `main` can't deploy version 3 first.
    The pre-release `pg_dump` is the backstop.
- **L6b — removal:** **Held** with L6a (owner, 2026-11-21). It removes the rule classes (once nothing uses the 2b flag), the losing model, and the dataset job.
- **Then** the revised gate (the second look at the test split), then phase 3 of `credits-detection-plan.md`. Both
  wait with L6a (owner, 2026-11-21): the gate reads version 3 rows, which only L6a writes.

## L1 specification (settled in review round 5)

L1's brief copies this section as written.

### Reading the inputs

- **Archive files.** Only files matching `item-\d+\.json` are parsed. Every `ratingKey` listed in the
  `section-*.json` files that has no item file, and any other `item-*` file, is reported with a count and a reason,
  and is never parsed.
- **The export CSV.**
  - RFC 4180, UTF-8 with no BOM, with a header. Fields with commas are quoted.
  - Booleans are `t` and `f`. An empty field means null; it occurs in `film_tmdb_id`, `film_year`, `collection_id`
    and `collection_tmdb_id`.
  - L1 checks the header against the column list above and fails if it differs.
- **The exclusions file.** CSV with the header `media_file_id,reason`. It may be empty or missing.
- **The pre-seed file.** One `film_id` per line.
- **The run date.** ISO `yyyy-MM-dd`.
- **The prefix.** In production it is `/Volumes/Media Library/Movies` (`cinefilm.plex.path-prefix`), passed as an
  argument through `PlexRules.prefix()`.
- **NFC.** `PlexRules.relativePath` normalises only the Plex side. L1 applies NFC to the export's `path` itself
  before comparing, as `PlexCreditsRules.java:133` does.

### The window

- The window is `[duration_ms − windowMs(duration_ms), duration_ms]`, where `windowMs(d) = min(35 min, d × 40 / 100)`.
- `CreditsFrameReader.windowMs` and `coarseOffsets` become public static, so L1, L2 and L3 share the product's exact
  arithmetic. A pure move that doesn't change behaviour.
- Labels exist only inside the window:
  - the `film` intervals start at the window's start;
  - nothing before the window is labelled.

### Labels

- **Merging.** Credits markers, after the shift, are merged first: overlapping, nested or touching intervals become
  one. 28 of the joined files have overlapping or nested markers. **The ±5 s exclusion is around the merged
  boundaries**, never the raw ones.
- **`target_start_ms`** is the start of the earliest merged credits interval, after the shift. On today's data it
  equals `plex_start_ms`.
- **How the interval lists are built.**
  - The three lists `credits`, `film` and `left_out` are **disjoint** and together cover exactly
    `[windowStart, duration_ms)`. Every interval is clamped to that range, and empty ones are dropped.
  - On today's data, the last merged credits end is past `duration_ms` in 473 files, so clamping matters.
  - Where reasons overlap, `BOUNDARY` takes precedence over `AFTER_LAST_CREDITS`.
  - A merged credits interval shorter than 10 s (7 today, for example *Iron Man 2* at 5.3 s) is swallowed by the
    exclusion and contributes no credits frames. That is expected, not an error.
- **`leading_island` flag.** It is set when the first merged credits interval is **shorter than 60 s** and the **raw
  gap** to the next merged interval is **longer than 60 s**. There are 22 such films today, for example *Cruel
  Intentions* (20 s, then a 122 s gap). Development islands are reviewed as stills under "Plex errors", and L5 and the
  gate report flagged films separately.
- **The per-file record** in `labels-<date>.json` adds `leading_island` (a boolean) to the schema above.
- **An excluded file's record** keeps `media_file_id`, `fingerprint`, `unit_key`, `side`, `fold` and `excluded`.
  - Its interval lists are empty.
  - Its `target_start_ms`, `shift_ms` and `leading_island` are null.
- **Exclusion order.** When several reasons apply, the **first in the closed-set order below** is recorded.
  `is_last_original_part` empty (a non-original file) counts as `NOT_LAST_PART`.

### Reason codes (a closed set)

- **`excluded`** (the whole file):
  - `NOT_LAST_PART`, `NOT_PRESENT`, `FINGERPRINT_CHANGED`;
  - `NO_ARCHIVE_ITEM`, `AMBIGUOUS_ARCHIVE_ITEM`;
  - `NO_CREDITS_MARKER`, `MISSING_MARKER_END`;
  - `SHIFT_MISMATCH`, `SHIFT_OUT_OF_RANGE`;
  - `MARKER_BEFORE_WINDOW`;
  - `OWNER_EXCLUDED` (from the exclusions file);
  - `SPLIT_CONFLICT`.
- **`left_out`** (an interval):
  - `BOUNDARY` (±5 s);
  - `AFTER_LAST_CREDITS`.

  Later phases add `LIT_OPENING` and `LIT_RUN` in L3.

### The split

- The hash is SHA-256 over the unit key's UTF-8 bytes. The first 8 bytes are read **big-endian** (`ByteBuffer.getLong`)
  as an unsigned 64-bit value:
  - side: `Long.remainderUnsigned(v, 100) < 70` is `dev`;
  - fold: `Long.remainderUnsigned(v, 5)`.
- Test vectors, pinned:
  - `tmdb:333371` gives 40, `dev`, fold 0;
  - `ctmdb:263` gives 60, `dev`, fold 0.
- **Expected on today's data:**
  - 657 units; 610 development files and 239 test files (see the carry-over rules);
  - 45 pre-seeded films. Besides the films named in the detection plan and this plan's three, they include *Barbie*,
    *Bee Movie*, *Black Panther*, *Cars*, *Deadpool 2*, *Free Guy*, *Matilda* and *Spider-Man: No Way Home*;
  - every pre-seeded film's unit goes to development.
- **The manifest file** `split-<date>.json`, **per film**:
  - `{ "date", "films": [{ "film_id", "unit_key", "side", "fold" }], "looks": [] }`;
  - every exported film is listed, excluded ones included;
  - `looks` stays an empty array, copied unchanged from the previous manifest. Tool runs mount `/labels`
    read-only, so the record of each use of the test split is the plan's looks log, under "Phases"
    ("Settled for later phases").
- **Carry-over rules, applied in order:**
  1. A film with a previous entry keeps its side and fold. **It wins over the pre-seed**, and the run reports the
     conflict.
  2. A new film whose unit already has members **in the previous manifest** takes the side and fold of those
     members, using the member with the lowest `film_id`. If those members sit on more than one side, the new film
     is excluded as `SPLIT_CONFLICT`. Its record and manifest entry carry its own hash side and fold, for reference;
     later runs keep that side under rule 1, so a conflict is reported once, on the run that found it.
  3. A new film whose **unit contains a pre-seeded film** (the film itself included) goes to `dev`, with its hash
     fold. So a pre-seeded unit is never split: today *Despicable Me 2* (film 224) goes to `dev` with *Despicable
     Me 4* (film 225).
  4. Any other new film takes its hash side and fold.
- **Expected on today's first run (no previous manifest):** exactly **610 `dev` and 239 `test` files**.
- Owner exclusions and their reasons live in the exclusions file, the labels (`excluded_note`) and the report, not
  in the manifest.
- **Each run reports drift and conflicts, counted:**
  - films kept on their old side because they changed unit;
  - units split across sides;
  - `SPLIT_CONFLICT` exclusions.

### Running L1

- L1 needs no database, no ffmpeg and no share, so it runs **on the Mac** from the locally built jar. It doesn't wait
  for a release and touches no server directories:
  `java -Dloader.main=gregochr.cinefilm.credits.learn.LabelsMain -cp backend/target/cinefilm-*.jar
  org.springframework.boot.loader.launch.PropertiesLauncher --archive <dir> --export <csv> --preseed <file>
  --prefix "/Volumes/Media Library/Movies" --date <yyyy-MM-dd> --out <dir> [--previous-split <json>]
  [--exclusions <csv>]`.
  - A missing required flag, or an unreadable input, exits non-zero with a one-line message.
- The brief includes a smoke test of that exact command against the built jar, using the fixture inputs.
- **The output** goes to `--out` (`~/cinefilm-labels/`), and is copied to the server:
  - `labels-<date>.json`;
  - `split-<date>.json`;
  - `labels-<date>-report.txt`: plain text with the counts of files and units, `dev` and `test` files, each exclusion
    reason, `leading_island`, unlisted or extra archive files, carry-over conflicts, units split across sides, and
    films kept on their old side.
- **Expected on today's data:** all 849 rows join, so **no file is excluded**.

### Tests (fixtures are trimmed, anonymised archive items and export rows)

- one marker; several markers; overlapping and nested markers (merged boundaries);
- a mid-credits gap; no `final`; two `final`s;
- a missing end; a non-credits type;
- an item whose own duration is the shortest (the shift formula);
- a shift mismatch; a fingerprint change; a non-last part; an ambiguous path; a missing item file; a `.tmp` file;
- NFC on the export side;
- a marker before the window;
- `leading_island`;
- the hash test vectors; the manifest carry-over, including a film that moved to a collection and a conflict; the
  pre-seed;
- CSV quoting and nulls; a wrong header.

### Not looked at before L5

- **The v2 detector's results on test films.** The v2 backfill finished on 2026-11-15; its rows were snapshotted on
  2026-11-17 (`credits-detection-v2-2026-11-17.csv` in `~/cinefilm-labels/`).
  - **Until L5, no v2-against-Plex error is computed over development films as a set:** no count, share,
    histogram, ranking or list, in any bins. Finer bins would sum back to the breach's.
  - A per-film comparison is allowed only for a film chosen without reference to v2's errors.
  - The baseline is scored on the test split only within L5's single look.
  - **2b's "gate before phase 3" query** (`credits-detection-plan.md`) runs over every file with a marker, test files
    included. It is superseded by this plan's gate, and is never run.
- **A breach, recorded (2026-11-15).** When the v2 backfill finished, the planner compared v2 with Plex as one
  blended count over all 847 files with both a marker and a result, test files included.
  - No split, film or opening type was broken out, and nothing has been tuned on it. The new model is unaffected,
    because none of its choices come from the v2 results.
  - **The counts are kept off this public file**, in `~/cinefilm-labels/breach-2026-11-15.txt`. Development counts
    in the same bins, subtracted from them, would give the test split's.
  - The bins were: within −15 s…+60 s, more than 60 s late, more than 15 s early, and not found or unsure. Together
    they cover every outcome, hence the rule above.
  - L5's report scores the baseline on the test split as planned, and names this earlier blended look beside it.
    It is recorded here, not in the looks log ("Settled for later phases").

### Re-import

- **Rows can go stale.** The 2c import replaces rows but never deletes them (`PlexCreditsImportService.java:52`).
  After Plex re-analyses a film, a row can outlive its marker. Taking the archive and the export in one sitting
  mostly covers this, and L1's shift check catches a changed earliest marker.

## L3a specification (measured 2026-11-17)

### What was measured

All on development films only, read-only, on the server:

- **The full dataset run** finished on 2026-11-16: 847 of 849 files. The two missing files have no decodable picture
  in their last seconds (fixed for the product in #170; the dataset's colour reads still stop there).
- **`DatasetMeasureMain`** ran on every development film (`measure-20261117T153142Z` in `~/cinefilm-labels/`):
  - 609 films, all `ok`; 238 test directories were skipped and never opened;
  - 253,159 coarse frames.
- **The planner's standard-library Python** decoded the stored grey PNGs and applied this section's exact definitions:
  the `ActiveCrop` crop, nearest-rank percentiles, and the nearest-frame tie rule. It read the L1 labels and wrote
  nothing to the dataset.
  - By L1's labels, 42,082 of the coarse frames are credits and 208,915 are film.
  - Every table below is over **coarse frames only**: every credits frame, plus a random 20,000 of the film frames.

All luma is `FramePrep`'s full-range grey (black 0, white 255), inside the crop.

**Dark background.** The median is a much cleaner test than the mean, because text raises the mean but not the
median:

| Rule | Credits frames | Film frames |
|---|---|---|
| `P50 < 2` | 77.7% | 1.6% |
| **`P50 < 4` (chosen)** | **79.9%** | **3.2%** |
| `P50 < 8` | 82.1% | 7.4% |
| `mean < 22` (P0's 35, mapped to full range) | 77.9% | 14.9% |

- Going from 4 to 8 adds 2.2 points of credits frames for 4.2 points of film frames.
- On openings, `P50 < 8` turns 22 more films from lit to dark (390 against 368).
- **Why frame-level film share decides it.** Inside a credits interval, a film frame wrongly called plain keeps lit
  picture labelled as credits. That is the error the lit rules exist to prevent. A lower threshold only leaves more
  credits frames out, which costs training data, not correctness.
  - `frameKind` also sets `OpeningType`, which decides which goals a film is held to and which `AUTO` band it falls
    in. So a threshold that moves films from dark to lit also moves them out of the ±10 s goals. That is what the
    review below checks.
- **The cost, checked at the first tool run.** Before L5's first training run, the planner reviews development stills of the 22
  films that `P50 < 8` would turn dark, and of 20 of the 124 intervals left out whole ("With the chosen rules"). The
  outcome is recorded here.
- **A threshold change** is a one-constant PR. In the same change, the planner re-runs the prototype and replaces
  every count recorded from it: this section's tables, the openings under the P0 table, and the counts the Java
  check reproduces. A change made after the L3b-look release needs a new release before the Java check runs.

**Light plate.** Among bright frames (crop mean ≥ 121: 1,897 credits frames, 1,113 film frames in the sample):

| `P90 − P10` at most | Credits (share of bright) | Film (share of bright) | Plate openings |
|---|---|---|---|
| 35 | 320 (16.9%) | 39 (3.5%) | — |
| **47 (chosen)** | **568 (29.9%)** | **49 (4.4%)** | **17** |
| 60 | 809 (42.6%) | 76 (6.8%) | 21 |

- **At a spread of 47:**
  - a mean of at least 100 gives 23.0% of credits frames and 2.5% of film frames;
  - a mean of at least 140 gives 31.8% and 4.5%;
  - each share is over the frames that are bright at that mean.
- **Where 121 and 47 come from.** They are P0's 120 and 40, picked by eye before any split existed and mapped to full
  range by `(Y − 16) × 255 / 219` (121.1 and 46.6), not fitted to test films.
  - The development figures above support keeping them.
  - Moving to 60 changes 4 openings, and raises the film share about 1.5 times (4.4% to 6.8%).
  - **Read the 4.4% with care.** The film side is a sample of about 9.6% of the window's film frames. Scaled up,
    about 510 film frames are `PLATE`, against 568 credits frames.
- **The 10th–90th spread is kept, not the 5th–95th.** On a white card, text often covers more than 5% of the pixels,
  so the 5th percentile lands on the text.

**With the chosen rules:**

- **Openings:** of 609 films, 368 dark (60%), 17 plate (3%) and 224 lit (37%).
- **The lit rules ("`FrameLabels`" below):**
  - they leave out 5,880 coarse credits frames as `LIT_OPENING` and 1,646 as `LIT_RUN`, and keep 34,556 (82.1%);
  - 124 credits intervals have no two consecutive plain coarse frames, and are left out whole. This includes the
    intervals holding fewer than two coarse frames.
- **These counts are the reference for the Java check.** They come from `labels-2026-11-13.json` and the dataset as
  read on 2026-11-16 (the 609 development films in `measure-20261117T153142Z`).
  - Their `datasetSha256`, by the definition under `CreditsFeaturesMain`, is
    `a6068949c657f06fc2f83a90cac5574700a565b0ceca8a78426eea51c7fbed8a`.
  - The Java run must use the same labels file, and its `datasetSha256` must match this hash before the counts are
    compared.
  - If the dataset has changed since (a film added, or re-read after a fingerprint change), the planner restricts
    the Java run with `--ids` to the unchanged films. It first re-runs the Python prototype on the same id list, and
    compares the hash and the counts against that run.

**A prototype of the text-shape features**, on the crop. It used this spec's definitions of `B`, ink, edges and thin
runs. Its text-row test compared a floating-point share with 0.02 and 0.6, not the integer test below, so the
`bands` row is approximate. These are quartiles (25 / 50 / 75) over 600 random credits
frames and 600 film frames from the five minutes before the target:

| Feature (below) | Credits | Film |
|---|---|---|
| `plain` | 0.78 / 0.86 / 0.91 | 0.20 / 0.30 / 0.51 |
| `hEdge` | 0.020 / 0.040 / 0.069 | 0.004 / 0.010 / 0.022 |
| `thinPerK` | 9.3 / 18.8 / 29.5 | 3.5 / 7.3 / 13.4 |
| `thinShare` | 0.78 / 0.90 / 0.97 | 0.53 / 0.67 / 0.79 |
| `bands` | 2 / 8 / 14 | 0 / 1 / 2 |

The raw report, the labels and the scripts stay in `~/cinefilm-labels/`. They are library data and are never
committed.

### Conventions for all of L3a

- **Integer arithmetic is Java's.** Each formula below is given as the Java expression to use: `w / 4` rounds down,
  and `(a + 1) / 2` is `ceil(a / 2)` for `a >= 0`, which `ActiveCrop` checks first.
- **A `double` share or ratio** is `(double) numerator / denominator`, and is 0 when the denominator is 0.
- **Percentiles are nearest-rank**, as in `FrameStats`. Over the `n` crop values sorted ascending, `P(p)` is the
  value at the 1-based rank `(p * n + 99) / 100`.
- **The mean** is `(double) sum / n`, where `sum` is a `long`.
- **The 33 features form one `double[]`,** in the order below. Integer-valued features such as `p10` and `bands` are
  stored as doubles, so `12` is written `12.0`.

### `ActiveCrop` (pure, L3a-1a)

`ActiveCrop.of(int left, int top, int width, int height, int greyWidth, int greyHeight)`:
- the first four are the area in 480 px coordinates;
- the last two are the grey frame's size: `complete.json`'s `frame` width and height divided by 2.

`GreyFrame apply(GreyFrame grey)` returns the cropped frame. It throws when `grey`'s size is not `greyWidth` ×
`greyHeight`. Every function below takes the cropped `GreyFrame`.

**It first throws `IllegalArgumentException`** unless all of these hold:
- `1 <= greyWidth <= GreyFrame.MAX_DIMENSION` and `1 <= greyHeight <= GreyFrame.MAX_DIMENSION` (checked first, which
  keeps the later `int` arithmetic from overflowing);
- `left >= 0` and `top >= 0`;
- `width >= 1` and `height >= 1`;
- `left + width <= 2 * greyWidth` and `top + height <= 2 * greyHeight`.

Then it computes the crop.

- The active area is in 480 px coordinates (`complete.json`'s `activeArea`, and `ActiveArea.of` in the product). The
  240 px grey frame's pixel `X` covers 480 px columns `2X` and `2X + 1`.
- **The crop keeps whole 2×2 boxes only:**
  - `x0 = (left + 1) / 2` and `x1 = (left + width) / 2`; `y0` and `y1` the same way;
  - columns `[x0, x1)` and rows `[y0, y1)`.
- **It then throws** unless `x1 - x0 >= 8` and `y1 - y0 >= 8`. `ActiveArea.of` guarantees at least half the frame, so neither happens on real files.

### `OpeningType` (pure, L3a-1a)

- **`FrameKind frameKind(double mean, int p10, int p50, int p90)`,** and `frameKind(GreyFrame crop)`, which
  computes the four stats and delegates to it. The boundary tests go through the stats version, and the "lit frames
  that show text" variant reuses it, so the thresholds live in one place:
  - `DARK` when `P(50) < 4`;
  - else `PLATE` when `mean >= 121` and `P(90) - P(10) <= 47`;
  - else `LIT`.

  The constants are named, and their Javadoc cites this section.
- **`OpeningType.of(List<KindAt> coarse, long startMs)`.** `KindAt` is `(long offsetMs, FrameKind kind)`, sorted by
  offset.
  - For each of `startMs + 5000`, `+ 20000` and `+ 45000`, it picks the coarse entry with the smallest
    `|offset − time|`. On a tie, the earlier entry wins.
  - It returns `DARK` if the +5 s entry is `DARK`, else `PLATE` if any of the three is `PLATE`, else `LIT`.
  - It returns empty (`Optional`) when the list is empty or no entry is at or after `startMs`. That can happen when
    a coarse pass ended early at a frameless tail (#170).
- It takes kinds, not frames, so `FrameLabels` and the product stay pure. The caller computes the kinds.

### `CreditsFeatures` (pure, `FEATURE_VERSION = 1`, L3a-1b)

**Inputs.** One crop (`w × h`, `n = w * h`) and, for the two time features, its two neighbours' crops of the same
size.
- `B` is the crop's `P(50)`.
- A pixel is **ink** when `Math.abs(v - B) >= 64`, so light and dark text count alike.

**The 33 features, in this order** (the CSV column names are these names):

1. `mean`, `p10`, `p50`, `p90`: the luma statistics.
2. `plain`: the share of pixels with `Math.abs(v - B) <= 12`.
3. `ink`: the share of ink pixels.
4. `hEdge`: the horizontal edge pairs, over `(w - 1) * h`. A pair is pixels `(x, y)` and `(x + 1, y)` with
   `Math.abs(Δ) >= 48`.
5. `vEdge`: the same for vertical pairs, over `w * (h - 1)`.
6. **The thin-stroke features.** An **ink run** is a maximal horizontal run of ink pixels in one row. A run is
   **thin** when it is 1 to 6 px long.
   - `thinPerK`: `1000.0 * thinRuns / n`.
   - `thinShare`: thin runs over all ink runs.
7. **The text-row features.** Row `y` is a **text row** when its count `e` of horizontal edge pairs satisfies
   `50 * e >= w - 1` and `5 * e <= 3 * (w - 1)`. That is a share from 0.02 to 0.6, inclusive, in exact integers.
   - `textRows`: text rows over `h`.
   - `bands`: the number of maximal runs of consecutive text rows that are 3 to 20 rows tall, inclusive.
   - `lineSpacingCv`: each band's centre is `(first + last) / 2.0`. Over the `k` gaps between consecutive centres,
     it is computed in this order:
     1. `m = sum / k`;
     2. `var = Σ (g − m)² / k`, summed in gap order;
     3. `Math.sqrt(var) / m`.

     It is 0 with fewer than 3 bands.
8. `centralInk`: ink pixels in columns `[w / 4, (3 * w) / 4)`, over all ink pixels.
9. `bottomInk`: ink pixels in rows `[h - h / 5, h)`, over all ink pixels. Burned-in subtitles sit here.
10. **The grid,** `grid00` … `grid33` (row `i`, then column `j`). Each is the ink pixels in a cell over that cell's
    pixels, not over all ink. Cell `(i, j)` covers columns `[(j * w) / 4, ((j + 1) * w) / 4)` and rows
    `[(i * h) / 4, ((i + 1) * h) / 4)`.
11. **The time features:**
    - `mad`: `(double) (s1 + s2) / (2L * n)`. `s1` and `s2` are the `long` sums of `Math.abs(v - v')` against each
      neighbour.
    - `inkChange`: `(double) (c1 + c2) / (2L * n)`. `c1` and `c2` count the pixels whose ink status differs from
      each neighbour, each frame using its own `B`.

    **Neighbours:**
    - **A coarse frame** uses the entries one index either side in `complete.json`'s `coarse` list. At either end of
      the list, the one neighbour is used for both.
    - **A dense frame** uses the entries 10 indices either side in `complete.json`'s `dense` list.
      - Without both neighbours, it has no time features: they are empty in the CSV, and the row is not used for
        training.
      - Dense frames before the window are omitted from the CSV, but still serve as neighbours.
      - The list can be shorter than 120 entries, when the file ends inside the range or ffmpeg wrote fewer frames.
        The rule is by index, so it still holds ("Settled for later phases").

**Tests.**
- Exact values for every feature, on small hand-made crops from 8 × 8 up to 32 × 24 (heights to 24 for the band and
  `lineSpacingCv` boundaries; odd-by-odd sizes such as 9 × 9 and 31 × 23 for the negative property), plus the plan's
  synthetic frames.
- Boundary tests one below, at and one above each threshold: 64, 12, 48, the run lengths 6 and 7, the band heights 2,
  3, 20 and 21, and both text-row inequalities. The lower one (`50 * e >= w - 1`) is at equality only when `w - 1` is
  a multiple of 50, so its test uses a 51 × 8 crop (`e = 1` at the boundary, `e = 0` below), the one exception to the
  size range.
- **Two properties**, tested with examples on the hand-made crops (no property-testing dependency):
  - **A frame and its negative** (`255 − v`, applied to the neighbours too) give equal values for every feature
    except `mean`, `p10`, `p50` and `p90`. The test uses crops with an odd pixel count, where the nearest-rank median
    of the negative is exactly `255 − B`.
  - **A frame compared with itself** gives `mad = 0` and `inkChange = 0`.

### `FrameLabels` and `CreditsFeaturesMain` (L3a-2)

**`FrameLabels` (pure)** takes one L1 `LabelRecord` and the film's coarse `List<KindAt>`.

- **The rules work on each interval of the record's `credits` list**, which L1 has already trimmed by `BOUNDARY`.
  - Membership is half-open: a coarse frame at `t` is inside `[from, to)` when `from <= t < to`.
  - "Consecutive" means adjacent entries in `complete.json`'s `coarse` list.
- **`LIT_OPENING`:**
  - Find the first two consecutive coarse frames inside the interval that are both plain (`DARK` or `PLATE`).
  - If that pair starts at the interval's first coarse frame, nothing is left out, and the stretch from `from` to
    that frame stays credits.
  - Otherwise, the left-out interval is `[from, the offset of the pair's first frame)`. A lone plain frame before
    the pair doesn't end it.
  - If no such pair exists, including when the interval holds fewer than two coarse frames, the whole interval
    becomes `LIT_OPENING`.
- **`LIT_RUN`:** after that pair, each run of 3 or more consecutive `LIT` coarse frames inside `[from, to)` (frames
  past `to` never extend a run; a fixture has a run of 2 at the interval's end that continues as `LIT` past `to`, and
  is kept) becomes
  `[first's offset, min(last's offset + 5000, to))`. A run of 2 is kept.
- **The record afterwards:**
  - `LeftOutReason` gains `LIT_OPENING` and `LIT_RUN`. L1's `LabelsMain` output is unchanged by this.
  - The `credits` intervals shrink to what is left, and empty fragments are dropped, as in L1.
  - The three lists stay disjoint and still cover `[windowStart, duration)`.
  - `left_out` is sorted by `from_ms`.
- **`FrameLabels.labelAt(FrameLabelRecord, long offsetMs)`** is pure and gives any frame's label, coarse or dense:
  `credits`, `film`, or the `left_out` reason of the interval holding it, by half-open membership. The bounds are the
  union of the three lists (the first `from`, the last `to`); an offset outside them, or any offset on an excluded
  record (empty lists), is an `IllegalArgumentException`. `CreditsFeaturesMain` writes `features.csv`'s `label`
  with it.
- **The end-title flag:**
  - `opening_type = OpeningType.of(coarse, target_start_ms)`, and `lit_opening = (opening_type == LIT)`.
  - When `OpeningType.of` is empty, both are null. The film's frames are still labelled, and `summary.json` counts it
    under `noOpeningFrame`, with no id.
  - Inside the pure class, an excluded record gets both as null and keeps its empty lists. The tool never writes
    excluded records (below).
- **The output record is a new `FrameLabelRecord`,** not a change to `LabelRecord`, so L1's output is unchanged. It
  holds `LabelRecord`'s fields in their order, then `opening_type` (`"DARK"`, `"PLATE"`, `"LIT"` or null) and
  `lit_opening` (a boolean or null), with the same snake_case naming.
- **Fixtures** (the excluded record is tested inside the pure class only):
  - a *Civil War*-like plate film, whose plate frames stay credits;
  - a lit opening;
  - an interval with no plain pair;
  - a `LIT_RUN` that ends at the interval's end;
  - a run of exactly 2 `LIT` frames, which is kept;
  - `labelAt` for dense offsets on both sides of a boundary (`to − 500` and `to`);
  - an excluded record;
  - a credits interval holding one coarse frame;
  - a lone plain frame before the first plain pair;
  - a film with no coarse frame at or after its target.

**`CreditsFeaturesMain`** is a thin `main`. Its arguments are `--data /data --labels /labels/labels-<date>.json
--out /out [--ids …]`. It has no `--stills` and no `--project`.

- **Development films only, with no way to include test films.** Otherwise it follows `DatasetMeasureMain`'s rules,
  with one difference:
  - test, excluded and unlabelled directories are never opened;
  - a record's exclusion is checked before its side, so an excluded test-side record counts as `excluded`
    (`DatasetMeasureJob.classify` checks side first and counts it as `test`);
  - `summary.json` counts them, and no skipped id of any kind is written;
  - it writes a fresh `<out>/features-<stamp>/` per run.
- **Only `ok` films are read, in two passes per film.**
  1. **Check,** in this order: `DatasetComplete.read` (empty is `incomplete`); the directory's id against
     `complete.json`'s `mediaFileId` (a difference is `mismatched`); `complete.json`'s `fingerprint` against the
     label's (a difference is `stale`, with no frame decoded; L3b-look and L3c-eval apply the same rule). Then
     `FilmCheck.check`, which reads `complete.json` again (expected), with a sink that ignores each frame except to check its grey height against
     `complete.json`'s `frame.height / 2`. A wrong height makes the film `damaged` before pass 2 starts, so nothing
     is written for a film that turns out not `ok`. `FilmCheck` is unchanged, so `DatasetMeasureMain`'s
     output is too.
  2. **Read.** Only for an `ok` film. It first computes every coarse frame's features and kind and keeps those rows
     (about 420 × 33 doubles), runs `FrameLabels`, and writes the coarse rows. Then it streams the dense frames in
     list order through a bounded buffer (21 frames) for the ±10-index neighbours.

  The smoke test's damaged film is one whose grey frame has the right width and the wrong height, so `FilmCheck`
  returns `OK` and only this check makes it `damaged`.
  - A film that is `incomplete`, `mismatched` or `damaged` is skipped. A development label with no directory is
    considered only when `--ids` names it (without `--ids` only directories are enumerated). `DatasetMeasureJob`
    folds it into `incomplete`; this tool counts it apart, as `noDirectory`.
  - Skipped films are counted in `summary.json` under `skippedByStatus: {incomplete, mismatched, damaged,
    noDirectory, stale}`, with no ids, beside the `test`, `excluded` and `unlabelled` counts.
- L3b-look adds `--look=L5` ("Phases"); there is no `gate` value.
- **`features.csv`:** one row per stored grey frame (coarse and dense) inside the window.
  - Rows are in `(mediaFileId, kind, offsetMs)` order. `kind` is `coarse` or `dense`, and `coarse` sorts first.
  - Columns: `mediaFileId`, `fold`, `side`, `kind`, `offsetMs`, `label`, then the 33 features. Without `--look`,
    `side` is always `dev`.
  - `label` is `credits`, `film`, or a `left_out` reason written as its enum name: `BOUNDARY`,
    `AFTER_LAST_CREDITS`, `LIT_OPENING` or `LIT_RUN`.
  - Dense frames before the window, possible when the target is within 30 s of the window's start, are omitted, and
    counted in `summary.json`.
  - Every `double` is written with `Double.toString`, so the CSV round-trips exactly. L3b trains on these values, and
    the product computes the same doubles in memory.
- **`frame-labels.json`:** a top-level array of `FrameLabelRecord`, in `media_file_id` order.
  - It holds only the development records whose film was read.
  - Excluded records, and development films skipped as not `ok` or missing, are omitted and counted in
    `summary.json`. A `SPLIT_CONFLICT` exclusion can sit in a test unit, so no excluded id is written.
  - It never overwrites L1's labels file.
- **`summary.json`:**
  - counts by `(kind, label)`, by opening type, and of the intervals left out whole;
  - the skipped counts by reason and status, and the omitted dense frames;
  - `FEATURE_VERSION` and the git SHA;
  - the SHA-256 of the labels file;
  - `datasetSha256`: one SHA-256 over the `complete.json` bytes of the development films that reached pass 2
    (`ok`), concatenated in ascending numeric `mediaFileId` order. Test films never enter it, with or without
    `--look`; the reference check uses a run without `--look`.

**The "lit frames that show text" variant** ("Labels").
- It relabels as `credits` the rows labelled `LIT_OPENING` or `LIT_RUN` that meet both conditions:
  - their `frameKind`, recomputed from the row's `mean`, `p10`, `p50` and `p90`, is `LIT`;
  - their `bands >= 3`.
- It applies to coarse and dense rows alike.
- The relabel happens before `left_out` rows are dropped for training.
- It is an option of L3b's training `main` (`--lit-text-variant`), judged once L3c-eval exists, by its nested
  selection ("Calibration and nested selection").

## L3b specification (written 2026-11-18)

L3b is two PRs, to stay within 1,500 changed lines: **L3b-1**, the pure trees and the model JSON, and **L3b-2**, the
training `main`. All code is in `credits/learn/`, under L3a's conventions.

### `GradientBoostedTrees` (pure, L3b-1)

- **Input.** A `double[][]` of rows (each `CreditsFeatures.COUNT` long), an `int[]` of labels (1 = credits, 0 =
  film), in the order given; the caller sorts. A NaN or infinite value, a label other than 0 or 1, rows of the wrong
  length, no rows, or a single class is an `IllegalArgumentException`.
- **Parameters** (a record, `GbtParams`): `trees` (default 200), `depth` (default 3), `learningRate` (default 0.1),
  `bins` (default 32), `minLeaf` (default 20), `lambda` (default 1.0). Each is checked: `trees`, `depth`, `minLeaf`
  ≥ 1; `bins` 2–256; `learningRate` in (0, 1]; `lambda` finite and > 0 (at 0, a saturated leaf divides 0 by 0, so 0 is refused).
- **Bins.** For each feature, the values sorted ascending; the candidate thresholds are the values at index
  `(k * n) / bins` for `k = 1 … bins − 1`, de-duplicated, ascending. A row goes left when `x <= threshold`. A
  feature with one distinct value has no threshold. Bins are computed once per fit, from all the fit's rows.
- **Start.** `base = ln(p / (1 − p))` with `p` the share of label 1.
- **Each tree.** Scores start at `base`. For each row, `p = 1 / (1 + e^−score)`, `g = p − y`, `h = p(1 − p)`.
  - A node splits on the `(feature, threshold)` with the largest gain
    `GL²/(HL + λ) + GR²/(HR + λ) − G²/(H + λ)`, over every feature and threshold where both sides hold at least
    `minLeaf` rows. It splits only when that gain is greater than 1e-12. Ties: the lowest feature index, then the
    lowest threshold (scan in that order, replace only on a strictly larger gain).
  - Growth stops at `depth` (a root at depth 0 can split; a node at `depth` is a leaf).
  - A leaf's value is `−learningRate · G / (H + λ)`.
  - Sums are in `double`, so a fit is deterministic: a node's `G` and `H` and each bin's sums run in row order,
    `GL` and `HL` add the bins in ascending order, and `GR = G − GL`, `HR = H − HL`.
- **Predict.** `p = 1 / (1 + e^−(base + Σ leaf values))`, for one row.
- **JSON.** One object: `{"featureVersion", "base", "params", "trees"}`, where a tree node is either
  `{"feature", "threshold", "left", "right"}` or `{"leaf"}`. Doubles are written by Jackson (shortest round-trip
  form). Reading checks `featureVersion == CreditsFeatures.FEATURE_VERSION` and refuses a mismatch with an
  `IllegalStateException` naming both versions; a fixture model file in test resources proves it. Any other bad
  content (a missing field, a wrong type, a `feature` outside `0 … COUNT − 1`, a non-finite number) is an
  `IllegalArgumentException`.
- **Tests.** Exact values on small hand-worked sets: the bins, one split's gain and tie-break, a leaf value, the base,
  a two-tree prediction; `minLeaf` blocking a split; the JSON round trip (the read model predicts identically); the
  feature-version refusal; and determinism: two fits of the same rows give byte-identical JSON.

### `CreditsTrainMain` (L3b-2)

- **Arguments:** `--features <run>/features.csv --summary <run>/summary.json --manifest <split manifest> --out /out
  [--trees N] [--depth D] [--learning-rate R] [--bins B] [--min-leaf M] [--lambda L] [--with-dense]
  [--lit-text-variant]`. There is no `--look`: it is an unknown flag (exit 1).
- **Rows.** Read in file order, which `CreditsFeaturesMain` wrote as `(mediaFileId, kind, offsetMs)`; the tool
  checks that order and refuses a file out of order.
  - The first row whose `side` is not `dev` or whose `fold` is empty stops the run: exit 1, no model written.
  - `--lit-text-variant` relabels as `credits` the `LIT_OPENING` and `LIT_RUN` rows whose
    `OpeningType.frameKind(mean, p10, p50, p90)` is `LIT` and whose `bands >= 3`, before the next step.
  - Rows with a `left_out` label, or with empty time features, are dropped.
  - Dense rows are dropped unless `--with-dense`.
- **Models.** For each fold `k` (0–4), one model on every kept row not in fold `k`: the five shipping models. For each
  unordered pair `{k, j}`, one model on the rows in neither: ten pair models. Fold `k`'s out-of-fold prediction for a
  row is the mean of the four pair models that exclude `k`.
- **Outputs**, in a fresh `<out>/train-<stamp>/`:
  - `model.json`: `type` `"gbt"`, the feature version, the params and variant flags, the five shipping models, the
    git SHA, `datasetSha256` and `labelsSha256` copied from `summary.json`, and the manifest's SHA-256. No
    thresholds yet (L3c adds them). No ids.
  - `oof-models.json`: the ten pair models, keyed `"k,j"` with `k < j`, for L3c-eval.
  - `oof-logloss.json`: the log loss (natural log, `p` clamped to `[1e-15, 1 − 1e-15]`) of each fold's out-of-fold
    predictions on that fold's **coarse** kept rows, and pooled over all of them; the row counts.
- **Tests:** a fixture CSV exercising each drop rule and each variant; the exit on a test row and on an empty fold,
  with nothing written; `--look` refused; a test that records each model's training folds and proves no model used
  for fold `k`'s predictions saw a row of fold `k`; the log loss on a hand-checked case.

### L3b's training runs (2026-11-19, v0.8.5, features `features-20261118T221304Z`)

Default parameters, each about 3 minutes on one CPU. The out-of-fold log loss, pooled over development coarse rows
(a constant prediction scores about 0.41):

| Run | Rows | Pooled | Folds 0–4 |
|---|---|---|---|
| base | 243,471 | 0.0206 | 0.0202, 0.0223, 0.0216, 0.0177, 0.0211 |
| `--with-dense` | 243,471 | 0.0218 | 0.0225, 0.0242, 0.0224, 0.0184, 0.0218 |
| `--lit-text-variant` | 247,980 | 0.0401 | 0.0360, 0.0395, 0.0467, 0.0354, 0.0424 |

The variant's rows differ, so its figure is not comparable. Variants and parameters are chosen by L3c-eval's nested
selection, never by these figures.

### L3c's development evaluation (2026-11-19, v0.9.0)

The three runs above were made again with `nested-models.json` (L3c-1): base `train-20261119T144958Z`,
`--with-dense` `train-20261119T145507Z` and `--lit-text-variant` `train-20261119T145939Z`. One evaluation of all
three, `eval-20261119T151319Z` (`toolGitCommit` `9abcf6e`), took a few minutes on one CPU. Development films only,
with no look.

- **Outer choices:** fold 0 the base run; fold 1 `--lit-text-variant`; folds 2–4 `--with-dense`. `λ` is 3 in four
  folds and 2 in one; `δ` is 10 in all five. Both are the top of their grid.
- **The final choice:** `--with-dense`, `λ` 3, `δ` 10, `G` 4 frames (20 s), `σ` 60, `θ` 0.9, `θ_d` 0.5. Its counts
  over the 609 films: no `FOUND` film early by more than 15 s, none by more than 5 s, no violation; of the Plex-dark
  and plate films (ranking levels 4 and 5 count only those), 188 within ±10 s and 333 within ±60 s or not found.
- **Held out** (`summary.json`: each film scored with its fold's choice, by models that never saw it), 609 films.
  `FOUND` films by the opening at `t`, and the early ones:

  | Opening at `t` | `FOUND` | Early by more than 15 s | Early by more than 5 s |
  |---|---|---|---|
  | `DARK` | 464 (low 20, high 444) | 1 (high) | 2 (high) |
  | `PLATE` | 2 | 0 | 0 |
  | `LIT` | 17 | 0 | 0 |

  The goals, by Plex's opening type, each over every film of that type:

  | Plex opening | Films | Within ±10 s | Within ±60 s or not found |
  |---|---|---|---|
  | `DARK` | 368 | 208 (56.5%) | 321 (87.2%) |
  | `PLATE` | 17 | 0 (0%) | 8 (47.1%) |
  | `LIT` | 224 | 1 (0.4%) | 128 (57.1%) |

What it shows:

- **Dark openings fall well short of the goals.** 56.5% within ±10 s against the goal of 90%, and 87.2% within
  ±60 s or not found against 97%. Plate openings: none of 17 within ±10 s, and 47.1% against 97%.
- **One held-out film is early by more than 15 s.** It opens dark, in the high margin band: the gate's
  zero-tolerance failure, in the band `AUTO` depends on most. A second film, also dark and high, is early by more
  than 5 s. The final choice's own counts show none, because a choice's own counts are scored with the films it was
  chosen on.
- **No rule covered held-out early films until now.** The margin rule ("The split") covers a choice's own counts.
  Added for the owner to decide at L5 (see "The split"): held-out early films are reported with the L5 comparison,
  and nothing ships at L6a with a held-out film early by more than 15 s without the owner's decision.
- **`λ` and `δ` sit at the top of their grids, which is weak evidence on its own.** Ranking level 6 prefers the
  larger `λ` and `δ` on a tie, so a top value can be a tie broken upward rather than a better point. The grid is
  widened upward anyway (L3c-grid, in "L4b specification") and model A's runs are evaluated again on it before L5.
- **Lit openings are late by design, and model B is not expected to change that.** "Labels" leaves lit credits
  frames out of training, and model B trains on the same rows, so neither model learns where a lit opening starts.
  Only `--lit-text-variant` touches it (fold 1 chose it). Plate openings keep their credits frames in training
  ("Labels"), so they, and the dark shortfall, are where a different model can help.

### Model A on the widened grid (2026-11-19, v0.9.1)

The same three runs, evaluated again on L3c-grid's 42,000 points: `eval-20261119T213020Z` (`toolGitCommit`
`6b1b236`), same dataset and labels hashes. Development films only, with no look.

- **The final choice is unchanged:** `--with-dense`, `λ` 3, `δ` 10, `G` 4, `σ` 60, `θ` 0.9, `θ_d` 0.5, with the same
  counts (188 within ±10 s, 333 within ±60 s or not found, no early film, no violation).
- **The outer choices now sit inside the grid:** `λ` is 1, 3, 3, 2 and 3; `δ` is 15 once and 10 four times. None
  chose the new top values (`λ` 4 or 5, `δ` 20). So the earlier top-of-grid values were not a grid edge holding the
  model back.
- **Held out, almost the same:** dark 209 of 368 within ±10 s (56.8%, was 56.5%) and 325 within ±60 s or not found
  (88.3%, was 87.2%); plate 0 of 17 and 8 (47.1%); lit 1 of 224 and 127 (56.7%). The early films are the same as
  before: one dark film in the high band early by more than 15 s, and two by more than 5 s.
- **What follows:** model A's tuning is settled. The dark and plate shortfall is left to model B and the L5
  comparison.

### Model B, and the choice of type (2026-11-20, v0.9.2)

Model B's runs, all on v0.9.2 (`toolGitCommit` `280593c`) and on the same features run, dataset and labels hashes as
model A's. Development films only, with no look.

- **Embeddings:** `embed-20261120T081506Z`, MobileNet v2-12 fp32, in 2 h 20 min (the estimate was about 3 h).
- **Seven training runs**, 46 to 66 minutes each (the estimate was about 2 h):

  | Run | Rows | `l2` |
  |---|---|---|
  | `train-lr-20261120T130105Z` | base | 0.1 |
  | `train-lr-20261120T135533Z` | base | 1 |
  | `train-lr-20261120T144102Z` | base | 10 |
  | `train-lr-20261120T154515Z` | `--with-dense` | 0.1 |
  | `train-lr-20261120T164708Z` | `--with-dense` | 1 |
  | `train-lr-20261120T175255Z` | `--with-dense` | 10 |
  | `train-lr-20261120T185053Z` | `--lit-text-variant` | 1 |

- **One evaluation over all seven:** `eval-20261120T220653Z`, on the 42,000-point grid.
  - **Outer choices:** fold 0 base `l2` 10; fold 1 lit-text; folds 2 and 4 with-dense `l2` 1; fold 3 with-dense
    `l2` 10. `σ` is 160, the top of its grid, in four of the five; `δ` is 20, the top of its grid, in two.
  - **Final:** with-dense `l2` 10, `λ` 2, `δ` 15, `G` 2, `σ` 160, `θ` 0.8, `θ_d` 0.5. Its own counts: 127 within
    ±10 s, 322 within ±60 s or not found, no early film, no violation (model A's final: 188 and 333).
- **Held out, against model A** (each film scored with its fold's choice):

  | | Model A | Model B |
  |---|---|---|
  | Early by more than 15 s | 1 (dark, high band) | 3 (1 dark high, 2 lit high) |
  | Early by more than 5 s | 2 | 4 |
  | Dark within ±10 s | 209 of 368 (56.8%) | 179 of 368 (48.6%) |
  | Dark within ±60 s or not found | 325 (88.3%) | 332 (90.2%) |
  | Plate within ±10 s / ±60 s or not found | 0 / 8 of 17 | 0 / 9 of 17 |
  | Lit within ±10 s / ±60 s or not found | 1 / 127 of 224 | 8 / 139 of 224 |

- **The choice: model A**, by "Choosing"'s ranking at its first level (one held-out film early by more than 15 s
  against three), and at the third as well (dark and plate within ±10 s: 209 against 179). Accepted by the owner on
  2026-11-20.
- **B's top-of-grid choices were not followed up.** `σ` at 160 in four outer folds and the final, `δ` at 20 in two
  and `l2` 10 in two and the final suggest B's grid was narrow for it. Widening it would have cost a code PR, a
  release and about four hours of runs; B's first-level loss is two lit films early by more than 15 s, which a wider
  smoothing range is not expected to remove, and the ranking was pre-registered. The owner chose A without it.
- **What follows:** L5's single look, one `--look=L5 --baseline` evaluation over model A's three runs alone
  (`train-20261119T144958Z`, `train-20261119T145507Z`, `train-20261119T145939Z`), on a features run made with
  `--look=L5`. Model B is never scored on test films, and no `CreditsEmbedMain --look=L5` run is made. Model B's code
  is deleted in L6b.

### L5: what the models showed (2026-11-21, v0.9.2)

The single look: `eval-20261121T095028Z`, model A's three runs, scored with `--look=L5 --baseline
credits-detection-v2-2026-11-17.csv`. Its dataset and labels hashes equal model A's training runs' (the tool refuses
a difference).

- **The development half is unchanged:** its `summary.json` block equals `eval-20261119T213020Z`'s, field for field.
- **The test split, 238 films.** Model A by the evaluation's own `summary.json`; v2 counted from `evaluation.csv`'s
  `v2Verdict` and `v2Error` columns with the same thresholds (every test film has a v2 row):

  | Test split | Model A | v2 |
  |---|---|---|
  | `FOUND` early by more than 15 s | 0 | 0 |
  | `FOUND` early by more than 5 s | 0 | 7 (all dark) |
  | Dark within ±10 s | 73 of 152 (48.0%) | 108 of 152 (71.1%) |
  | Dark within ±60 s or not found | 136 (89.5%) | 133 (87.5%) |
  | Plate within ±10 s / ±60 s or not found | 0 / 2 of 2 | 0 / 2 of 2 |
  | Lit within ±10 s / ±60 s or not found | 0 / 36 of 84 | 1 / 34 of 84 |

  Model A's early counts are by the opening at `t` and margin band; v2's by Plex's opening type. Model A's early error
  is its worst case (`t − 5 s`) and v2's its stored offset, so the early rows favour v2 ("Goals and benchmark").
  Seven test films carry `leading_island`; with no early test film, none is early.

What it shows:

- **The pre-registered test rule switches nothing off:** no test film is early by more than 15 s, in any opening type
  or band.
- **Model A is much worse than v2 where it matters most.** Dark openings within ±10 s: 48.0% against v2's 71.1%, and
  both well short of the 90% goal. Model A is slightly better within ±60 s and is never early by more than 5 s, where
  v2 is seven times; neither is early by more than 15 s.
- **The breach is named beside it** ("Not looked at before L5"): the blended v2 count of 2026-11-15 covered test files
  too. Nothing was tuned on it, and this comparison does not depend on it.
- **The held-out development film early by more than 15 s** (dark, high band; "The split") is not decided, since
  nothing ships.

**The decision (owner, 2026-11-21): keep v2, and hold L6a.** Version 2 stays the product's detector and `ANALYSIS_VERSION`
stays 2. The test split has been looked at once; its second and last look is the version 3 gate, so any new design is
chosen on development films only and judged there. The gate and phase 3's `AUTO` wait with L6a, because the gate
reads version 3 rows (with their `opening_type` and `margin_band`) and phase 3's `AUTO` waits on the gate. Phase 3 itself
is not built yet, so today no marker is applied: every film's end overlay
appears at 97% of its runtime, and it counts as watched at 92%. Model B's code is not deleted while L6a is held (L6b is held with it).

## L3c specification (written 2026-11-19)

Three PRs, each at most 1,500 changed lines: **L3c-1**, the segmenter and the training `main`'s nested models;
**L3c-eval-1**, the per-film scoring and the calibration, both pure; **L3c-eval-2**, `CreditsEvaluateMain`. The release
after L3c-eval-2 is the "L3c" release. All code is in `credits/learn/`, under L3a's conventions.

### `CreditsSegmenter` (pure, L3c-1)

This turns "Finding the start" into code. Vetoes (step 6) and the reasons text (step 7) are L6a's.

- **`SegmenterParams`** (a record): `lambda`, `delta`, `gapFrames` (`G` as a count of coarse frames: a run of `r`
  frames lasts `5r` s), `sigma`, `theta`, `thetaDense`. Each is checked: `lambda`, `delta` finite and ≥ 0;
  `gapFrames` ≥ 1; `sigma` finite; `theta`, `thetaDense` in `[0, 1]`.
- **`Coarse coarse(long[] offsets, double[] p, double logitPi, SegmenterParams params)`.** `offsets` are the film's
  coarse offsets, ascending; `p` the same length.
  - **Frames are counted, not timed.** Every length is a count of adjacent coarse frames, 5 s each
    (`CreditsFrameReader.COARSE_STEP_MS`): 30 s is 6 frames, 15 s is 3, 60 s is 12. Stretches are index ranges
    `[a, b)`.
  - `ℓ_i = logit(clamp(p_i)) − logitPi − lambda`, with `clamp` to `[1e-15, 1 − 1e-15]` (as L3b's log loss) and
    `logit(x) = ln(x / (1 − x))`. `P` is the prefix sum (`P[0] = 0`, `P[i+1] = P[i] + ℓ_i`, in index order), and
    `S(a, b) = P[b] − P[a]`.
  - **Step 1.** `S*` is the largest `S(a, b)` with `b − a ≥ 6`. On equal sums the larger `b` wins, then the larger
    `a`. With fewer than 6 frames, the verdict is `NOT_FOUND`.
  - **Step 2.** With `u* = b` fixed, `t` is the largest `a` with `u* − a ≥ 6` and `S(a, u*) ≥ S* − delta`.
  - **Step 3.** Look for the first run of at least `gapFrames` consecutive frames with `ℓ < 0` inside `[t, u*)`. If
    there is none, or the frames before it (from `t`) number 12 or more, stop. Otherwise the run is taken whole
    (extended while `ℓ < 0`, up to `u*`), and the new start `s` is the index after its last frame. If `u* − s < 6`, the verdict is `NOT_FOUND`. Else `t` becomes the largest `a ≥ s` with
    `u* − a ≥ 6` and `S(a, u*) ≥ S(s, u*) − delta`, and step 3 repeats from the new `t`.
  - **Step 4.** `FOUND` when `S(t, u*) ≥ sigma` and the three frames `t`, `t + 1`, `t + 2` all have `p ≥ theta`;
    otherwise `UNSURE`.
  - It returns the verdict, `t`'s offset and index, `u*`'s index, `S*`, `S(t, u*)` (the margin) and the smallest
    `p` of the three frames. `NOT_FOUND` returns no `t`.
- **`OptionalLong dense(long t, long[] offsets, double[] p, double thetaDense)`.** `offsets` ascending, on one 500 ms
  grid; `p` is NaN where a frame has no time features.
  - `f` is the smallest offset `≥ t − 5000` such that the 11 offsets `f, f + 500, …, f + 5000` are all present,
    none has a NaN `p`, and all have `p ≥ thetaDense`. Empty when none qualifies, and the caller uses `t`.
- **`double bestStretch(double[] p, int end, double logitPi, double lambda)`:** `S*` over frames `[0, end)`
  only, or negative infinity when `end < 6`. The calibration's negatives use it.
- **Tests:** the ten under "Phases", L3c, each with exact `t`, `f` and verdict; and boundaries one below, at and one
  above: 5, 6 and 7 frames; a run of `gapFrames − 1` and `gapFrames`; a run longer than `gapFrames` that ends
  fewer than 6 frames before `u*` (`NOT_FOUND`); 11 and 12 frames before a run; `S(t, u*)`
  against `sigma`; each of the three `p` against `theta`; a dense window with 10 and 11 offsets; `f` at
  `t − 5500` and `t − 5000`; both tie rules of step 1.

### Scoring one film (`FilmScore`, pure, L3c-eval-1)

It is computed in two parts, so a grid runs fast: **`coarsePart`**, per film, prediction set, `λ`, `δ` and `G`
(the segmenter's steps 1–3 and, per `λ`, the negative's `bestStretch`); and **`finish`**, per `σ`, `θ` and `θ_d`,
which applies step 4, the dense step (cached per `t` and `θ_d`) and the fields below. A test proves the two parts
equal one `FilmScore.of` call with all six params on every fixture film. The calibration keeps running counts per
grid point and stores no per-film result.

It takes one film: the coarse offsets, each coarse frame's kind (`OpeningType.frameKind` of its row's `mean`,
`p10`, `p50`, `p90`), the coarse `p`, the dense offsets and `p` (every stored dense row, NaN without time
features), the target, Plex's opening type, `logitPi` and the params. It returns one `evaluation.csv` row's
values:

- the segmenter's verdict and `t`;
- **the simulated `f`:** `dense` on the dense rows with time features whose offsets lie in `[t − 10 s, t + 30 s)`;
- **`covered`:** `t − 5 s ≥ target − 25 s`, and `f` exists with `f + 5 s < min(target + 25 s, t + 30 s)`;
- **`aligned`:** `(t − d₀) mod 500 = 0`, with `d₀` the film's first dense offset in `features.csv` (all dense
  offsets share one 500 ms grid, so any one serves; `complete.json` is not read);
- **`earlyError`** `= t − 5000 − target`; **`goalError`** `= f − target` when covered, else `t − target`;
- **the opening at `t`:** `OpeningType.of(kinds, t)`;
- **the margin band:** `low` when `S(t, u*) < 2σ`, else `high`;
- **the negative:** `bestStretch` over the coarse frames with offset `< target − 5000`. It is a **violation** when
  it is `≥ 0.8σ`. A `FOUND` on the cut window implies a violation, since `S(t, u*) ≤ S*`.

`f`, `covered`, `aligned`, the errors and the band are empty unless `FOUND`.

**Tests:** the branches under "Two model types" (a covered search, one on aligned grids, one not covered, and `t`
well before the target, where the stored frames alone would give an `f` the product could not reach); an opening at
`t` that differs from Plex's; a negative at `0.8σ` exactly and just below it.

### Calibration and nested selection (`CreditsCalibration`, pure, L3c-eval-1)

**This phase owns both.** Hyperparameters and variants are separate training runs; the calibration picks a run and
a grid point together, so both are chosen by the nested rule.

- **The grid** (20,000 points; steps 1–3 depend only on `λ`, `δ`, `G`, so they run 80 times per film). L3c-grid
  widens `λ` and `δ` to 42,000 points ("L4b specification"):
  - `λ` ∈ {0, 0.5, 1, 2, 3}; `δ` ∈ {0, 2, 5, 10}; `G` ∈ {10, 15, 20, 30} s (2, 3, 4, 6 frames);
  - `θ` ∈ {0.5, 0.7, 0.8, 0.9, 0.95}; `σ` ∈ {5, 10, 15, 20, 30, 40, 60, 80, 120, 160};
    `θ_d` ∈ {0.5, 0.7, 0.8, 0.9, 0.95}.
- **The ranking** of a `(run, point)` over a set of films, each scored by `FilmScore`, compared in order:
  1. fewer `FOUND` films with `earlyError < −15000`;
  2. fewer with `earlyError < −5000` (the −5 s margin, that is `t < target`);
  3. fewer negative violations;
  4. more Plex-`DARK` and Plex-`PLATE` films with `|goalError| ≤ 10000`;
  5. more of them with `|goalError| ≤ 60000` or not `FOUND`;
  6. the more conservative point: larger `σ`, then larger `θ`, larger `λ`, larger `δ`, **smaller** `G`, larger
     `θ_d`;
  7. the run given first on the command line.

  The margin is a ranking, not a hard rule, so a choice always exists. Each choice records its counts, and a choice
  whose count 1 or 2 is not zero is reported to the owner with the L5 report; such a `final` choice is not shipped
  without the owner's decision.
- **Which predictions score which film** (`π` of an ensemble: `logitPi` is the mean of its models' `base`):
  - **Outer fold `k`:** the choice is made on the development films outside `k`. A film of fold `j` is scored by the
    mean of the three triple models `{k, j, m}` (each trained on the other two folds). Fold `k`'s films are then
    scored with that choice by fold `k`'s out-of-fold ensemble (the four pair models without `k`). These rows are
    `evaluation.csv`'s development rows.
  - **Final:** the choice is made on all development films, each scored by its fold's out-of-fold ensemble. It is
    what L6a commits, with that run's five shipping models. Under `--look=L5` it scores the test films with the
    shipping ensemble.
  - The ensembles differ in size (three models inner, four outer, five shipping), which leaves the thresholds
    slightly conservative, as "Choosing" accepts.
- **Tests:** a hand-built set where each ranking level decides; each tie rule; a test that records the films and
  models each choice used and proves no outer fold `k` choice saw a fold `k` film, and no model scoring a film was
  trained on that film's fold; `logitPi` as the mean of the bases.

### `CreditsEvaluateMain` (L3c-eval-2)

- **Training first (in L3c-1).** `CreditsTrainMain` also writes `nested-models.json`: ten triple models keyed `"k,j,m"` with
  `k < j < m`, each trained on the kept rows in none of the three folds. A test proves no triple model saw a row of
  its folds. Runs made before this (all of 2026-11-19's) lack the file and are refused, so they are run again.
- **Arguments:** `--features <features run> --train <train run> [--train <train run> …] --out /out [--look=L5]
  [--baseline <v2 snapshot csv>]`. It reads `features.csv`, `frame-labels.json` and `summary.json` from the features
  run, and `model.json`, `oof-models.json` and `nested-models.json` from each training run.
- **Refused** (exit 1, nothing written):
  - a test row without `--look=L5`, or `--look=L5` on a features run with no `look.json`;
  - `--baseline` without `--look=L5`;
  - a training run whose `datasetSha256` or `labelsSha256` differs from the features run's `summary.json`, or whose
    manifest hash differs from another run's; a feature-version mismatch (`fromJson`); the same run given twice;
  - rows out of `(mediaFileId, kind, offsetMs)` order, or a development row with an empty fold.
- **Every row of a film is scored**, whatever its label: the segmenter sees every coarse frame in the window.
  Single-threaded and deterministic: the same inputs give byte-identical files apart from the stamp and
  `generatedAt`.
- **Outputs**, in a fresh `<out>/eval-<stamp>/`:
  - `evaluation.csv`, the columns under "Phases", L3c-eval, with exactly this header:
    `mediaFileId,side,fold,verdict,t,f,covered,aligned,earlyError,goalError,openingAtT,marginBand,plexOpening,v2Verdict,v2Error,run,leadingIsland`.
    Times and errors are in ms. `openingAtT` and `plexOpening` are `DARK`, `PLATE`, `LIT` or empty. `v2Error` is
    `offset_ms − target`, empty unless v2's verdict is `FOUND`; both v2 columns are empty without `--baseline`.
    `run` is the chosen training run's directory name. `leadingIsland` is the film's `leading_island` from
    `frame-labels.json` (L1's flag), so L5 can report those films apart. Rows are in `mediaFileId` order. Test rows appear only under `--look=L5`.
  - `calibration.json`: **where each fold's calibrated values are stored.** `{"grid", "outer": {"0" … "4": {"run",
    "params", "counts"}}, "final": {"run", "params", "counts"}, "toolGitCommit", "datasetSha256", "labelsSha256"}`,
    where `counts` holds the ranking's levels 1–5 and the number of films. L6a copies `final` into the model file.
  - `summary.json`, for the development rows and, under `--look`, the test rows apart:
    - **early counts**, by the cross of the opening at `t` and the margin band (the groups the pre-registered test
      rule and `AUTO` use), over `FOUND` films: `FOUND`, `earlyError < −15 s`, `earlyError < −5 s`;
    - **goal shares**, by Plex's opening type, each over every film of that type: within ±10 s, and within ±60 s or
      not found. A film with no Plex opening type (`noOpeningFrame`) is its own `none` group, and is in no ranking
      level 4 or 5 count.
  - `look.json` under `--look=L5`, as in L3b-look.
- **Tests:** a smoke test on a fixture features run and a fixture training run (two small folds of hand-made films)
  checking every output file; each refusal with nothing written; the baseline join on `media_file_id` and
  `fingerprint`, including a row with no match; `--look=L5` scoring test films with the final choice.
- **The planner's runs after the L3c release:** retrain the base, both variants and a few parameter sets, then
  evaluate them together in one run. That run's choices replace L3b's log-loss figures.

## L4b specification (written 2026-11-19)

Model B's tool code: the ONNX encoder, the benchmark, the embeddings run, logistic regression, its training `main`,
and the evaluation of model B's runs. No product wiring (L6a), and no encoder file in the image or the repository.
All code is in `credits/learn/`, under L3a's conventions. Nine PRs, each at most 1,500 changed lines including
tests, merged in this order (the sizes are estimates, including tests):

1. **L3c-grid** (about 100 lines): the calibration grid widened upward, so model A's runs are evaluated again on
   it before L5.
2. **L4b-1** (about 800): the ONNX Runtime dependency, `EncoderSpec`, `EncoderInput`, `ImageEncoder`,
   `OnnxImageEncoder` and the tiny ONNX fixture.
3. **L4b-2** (about 1,000): `EmbeddingFile` and the benchmark, `CreditsEncoderBenchMain`.
4. **L4b-3** (about 1,300): the embeddings run, `CreditsEmbedMain`.
5. **L4b-4a** (about 700): the training scaffold, `TrainRows` and `FoldModels`, extracted from `CreditsTrainJob`
   with no change to its output.
6. **L4b-4b** (about 900): `LogisticRegression` and `LrParams`, pure.
7. **L4b-5** (about 900): model B's training run, `CreditsTrainLrMain`.
8. **L4b-6a** (about 700): the evaluation reads films one at a time, and reads model B's model files.
9. **L4b-6b** (about 900): the evaluation scores model B's runs (`--embeddings`).

**Releases** (owner, 2026-11-19): after L4b-2, so L4a can run while the rest is built (L3c-grid merges before it),
and after L4b-6b. Each PR adds its own `changelog.d/` entry. A PR that grows past 1,500 lines is split again before
review.

### The grid, widened (L3c-grid)

- **Why.** In `eval-20261119T151319Z`, `λ` and `δ` were chosen at the top of their ranges ("L3c's development
  evaluation"). That is weak evidence on its own: ranking level 6 prefers the larger `λ` and `δ` on a tie. Widening
  costs little and settles it.
- **The change**, in `CreditsCalibration`: `LAMBDAS` becomes {0, 0.5, 1, 2, 3, 4, 5} and `DELTAS` {0, 2, 5, 10, 15,
  20}. `G`, `θ`, `σ` and `θ_d` are unchanged. `POINTS` becomes 7 × 6 × 4 × 5 × 10 × 5 = 42,000, and steps 1–3 run
  168 times per film instead of 80, so an evaluation takes a little over twice as long. The grid under "Calibration
  and nested selection" is superseded by this one.
- **Tests:** the index-to-point round trip at the new first and last points, and `POINTS`.
- **Done when** merged before the L4b-2 release. The planner then evaluates model A's three runs again on the new
  grid, and records the result beside "L3c's development evaluation".

### ONNX Runtime and the encoder (L4b-1)

- **The dependency.** `com.microsoft.onnxruntime:onnxruntime` (the CPU build), compile scope. The builder pins the
  newest release at least two weeks old and writes its exact version in `pom.xml` and the PR. No GPU build, and no
  other new dependency.
- **The natives check comes first, before any code.** The builder lists the pinned jar (`unzip -l`) and records in
  the PR whether it holds `ai/onnxruntime/native/osx-x64/` and `ai/onnxruntime/native/linux-x64/`, and the jar's
  size.
  - `linux-x64` must be there: the image is `linux/amd64` on Ubuntu (`eclipse-temurin:25-jre`, glibc). Without it the
    builder stops and tells the planner.
  - **The library is in every image built after L4b-1 merges** (owner, 2026-11-19: accepted, no separate tools
    image), because tool runs use the released image. The server never loads it before L6a. The encoder file is
    added to the image only if B wins (L6a).
  - **If `osx-x64` is there:** `OnnxImageEncoder` is tested on the Mac as normal, under the usual floors.
  - **If it isn't:** `OnnxImageEncoder` stays a thin class behind `ImageEncoder`, holding nothing else. Everything
    else is tested on the Mac against a pure-Java stub encoder. The adapter's own tests run in CI only (enabled on
    Linux), and the PR asks the owner to approve that class's exception to the local coverage floor, with a
    per-class rule and its reason in `pom.xml`.
  - The library unpacks its natives into `java.io.tmpdir`. The tool run's `docker run` has no `--read-only`, so the
    container's `/tmp` is writable.
- **`EncoderSpec`** (a record): `name`, `file` (the ONNX file's name, in the same directory), `sha256`, `input` and
  `output` (the tensor names), `size` (the square side), `mean` and `std` (three each, in R, G, B order), and
  `dimension`.
  - The record checks: `size` and `dimension` ≥ 1; `mean` and `std` three finite values each, `std` > 0; `file` a
    plain name with no path separator.
  - `EncoderSpec.read(Path dir)` reads `dir/encoder.json`, then hashes `dir/<file>`. It refuses
    (`IllegalArgumentException`) a missing or unknown field, a SHA-256 that differs, and a `size` other than
    `FramePrep.COLOUR_SIZE` (224). The record itself allows any size, so pure tests can use small frames.
  - The planner writes `encoder.json` for each candidate at L4a, in `~/cinefilm-labels/encoders/<name>/` beside the
    ONNX file. The names, `mean`, `std` and `dimension` are copied from the export's own files (for CLIP, its
    `preprocessor_config.json`; the input is `pixel_values` and the output `image_embeds`).
  - **Fetching the files** (owner, 2026-11-19: yes). The planner writes the download command, with each file's
    published SHA-256; the owner runs it on the server, and the command checks the SHA-256 before keeping the file.
- **`EncoderInput`** (pure): `float[] tensor(List<RgbFrame> frames, EncoderSpec spec)`. NCHW, frames in list order,
  channels R, G, B. Each value is `(float) ((v / 255.0 − mean[c]) / std[c])`, computed in `double`. A frame that is
  not `size × size` is an `IllegalArgumentException`.
- **`ImageEncoder`** (an interface, `AutoCloseable`): `float[][] encode(List<RgbFrame> frames)`, one row of
  `dimension` floats per frame, in order.
- **`OnnxImageEncoder`** implements it.
  - **The session:** one intra-op thread, one inter-op thread, sequential execution, the CPU arena off, and the
    default graph optimisation level. The same settings are used by the benchmark, the embeddings run and, at L6a,
    the product. The session is opened in the constructor and closed by `close`.
  - **At open,** it refuses a model with no input named `input` or no output named `output`.
  - **`encode`** builds one `[n, 3, size, size]` tensor and runs it. An output whose shape is not `[n, dimension]`,
    or with a NaN or infinite value, is an `IllegalStateException`.
- **The tiny ONNX fixture** sits in `backend/src/test/resources/credits/learn/encoder-tiny/` (`tiny.onnx` and its
  `encoder.json`, `size` 224, `dimension` 3).
  - It is one `ReduceMean` node (`axes = [2, 3]`, `keepdims = 0`) from `pixel_values` to `image_embeds`: the mean of
    each normalised channel, so its output can be checked by hand. `ModelProto.ir_version` is 7 and the one
    `opset_import` is the default domain at version 13 (where `ReduceMean`'s `axes` is an attribute).
  - The input's dims are `["N", 3, 224, 224]` and the output's `["N", 3]`, all `float`. Only the batch is symbolic,
    because callers send batches of 1 to 16 frames (a test sends two); the rest are fixed, as the real encoders'
    are.
  - It is written by a test-source class, `TinyOnnx`, which writes the protobuf wire format directly, with no new
    dependency. A test proves the committed bytes equal what `TinyOnnx` writes, so the fixture's origin is in the
    repository.
- **Tests:**
  - each of `EncoderSpec`'s refusals, including the SHA-256 one;
  - `EncoderInput`'s exact values on 2 × 2 frames, the batch order, and a frame of the wrong size;
  - `OnnxImageEncoder` against the fixture: two frames of known colours in one batch give their exact channel means
    (to within 1e-6), in order; a spec naming a missing input or output is refused at open; a `dimension` other than
    3 is refused at `encode`;
  - the `TinyOnnx` byte check.

### `EmbeddingFile` (L4b-2)

One file holds a run's embeddings, `embeddings.bin`. It is binary, little-endian:

- **The header:** the 8 ASCII bytes `CFEMB001`, an `int` `dimension`, a `long` `rows`.
- **Each record:** a `long` `mediaFileId`, a `byte` `kind` (0 coarse, 1 dense), a `long` `offsetMs`, then
  `dimension` floats. A record is `17 + 4 × dimension` bytes: 2,065 at 512.
- **The writer** appends records and writes `rows` into the header when it is closed.
- **The reader** is a sequential cursor. It refuses (`IllegalArgumentException`) a wrong magic, a `dimension` below 1,
  a file length other than the header plus `rows` records, a `kind` other than 0 or 1, and a NaN or infinite float.
- **Tests:** a round trip, and each refusal (a truncated file and one with a trailing byte included).

### The benchmark (`CreditsEncoderBenchMain`, L4b-2)

L4a's measurement ("B. Pretrained image embedding…"), on released code.

- **Arguments:** `--encoder <dir> --out /out [--frames N] [--warmup W]`. `--frames` is 1–10,000 (default 520, one
  film's coarse and dense frames); `--warmup` is 0–1,000 (default 16). The batch is 16 frames, as in the product;
  the last batch may be shorter.
- **It reads no dataset and no labels,** so no test film can be opened. The frames are synthetic: 224 × 224 RGB from
  `SplittableRandom` with a fixed seed (20261119). Each goes through the product's round trip, `FramePrep.jpeg` then
  `FramePrep.decodeJpeg`, inside the timed part, then `EncoderInput` and the encoder.
- **What it measures:**
  - `openSeconds`, the wall time to open the session;
  - after the warm-up frames (not counted), the process CPU time (`com.sun.management.OperatingSystemMXBean
    .getProcessCpuTime()`, which counts the library's native threads too) and the wall time of the counted frames;
  - `cpuMsPerFrame` and `wallMsPerFrame`, and `film520CpuSeconds` and `film520WallSeconds` (the per-frame figures
    times 520), against the cost limit of 2 minutes a film;
  - `peakRssKb`, `VmHWM` from `/proc/self/status` after the run, or null where that file is missing (the Mac);
  - `maxHeapBytes` (`Runtime.maxMemory()`).
- **Output:** a fresh `<out>/bench-<stamp>/bench.json` holding those figures, the frame and warm-up counts, the
  session settings, the encoder's `name`, `sha256` and `dimension`, the ONNX Runtime version, `toolGitCommit` and
  `generatedAt`.
- **Refused** (exit 1, nothing written): a bad flag, an `EncoderSpec` refusal, an encoder that fails to open, and
  `--out` inside the encoder's directory.
- **Pure parts:** `PeakRss.parse(String status)` returns the `VmHWM` value in kB, or empty. The CPU and wall clocks
  are one injected seam, so a test gets exact figures.
- **L4a runs it** in the tool-run `docker run`, with `java -Xmx512m` (the server's `JAVA_TOOL_OPTIONS`), so its peak
  RSS is close to what the encoder adds to the server.
- **Tests:** with a stub encoder and a fake clock, the exact `bench.json`; the warm-up not counted; a last short
  batch; each flag's bounds; `PeakRss` on a sample status, one without `VmHWM`, and a malformed value; one run end to
  end on the tiny fixture (the ONNX branch above decides where it runs).

### `CreditsEmbedMain` (L4b-3)

- **Arguments:** `--data /data --features <features run> --encoder <dir> --out /out [--look=L5]`. It reads
  `features.csv`, `frame-labels.json`, `summary.json` and, under `--look=L5`, `look.json` from the features run; each
  film's `complete.json` and stored JPEGs under `<data>/frames/<mediaFileId>/`; and the encoder.
- **It embeds exactly the features run's rows.** That run already applied every check of the features tool (the
  side, the exclusion, `ok`, `stale`), so its rows decide which frames are read. There is no `--ids`.
- **Pass 1, before any frame is opened:**
  - `summary.json`'s feature version (`CreditsTrainJob.readSummary`); the header and order of `features.csv` (as
    `FeatureRunReader` checks them);
  - **a test row without `--look=L5` stops the run:** exit 1, nothing written. Pass 1 reads all of `features.csv`
    first, so no frame of any film, test or development, has been opened;
  - `--look=L5` on a features run with no `look.json` is refused;
  - for each film: a record in `frame-labels.json`; a `complete.json` whose `mediaFileId` is the directory's and
    whose `fingerprint` is the record's; a regular file `coarse/<offset>.jpg` or `dense/<offset>.jpg` for every row.
    Any failure is refused, not skipped: the features run read this film, so a difference now means the dataset
    moved;
  - `datasetSha256`, recomputed over the development films' `complete.json` bytes as `CreditsFeaturesMain` defines
    it, must equal `summary.json`'s;
  - `EncoderSpec.read`, and `--out` must not be inside `--data` or the encoder's directory.
- **Pass 2, the encoding.** Films in `features.csv` order. Each film's coarse rows, then its dense rows, go in
  batches of up to 16 consecutive rows of one kind of one film, so a batch never mixes films or kinds and test
  films never change a development film's batches. Each JPEG is decoded with `FramePrep.decodeJpeg`; a frame that is
  not 224 × 224 is refused. Every row gets a record, in `features.csv` order, whatever its label and with or without
  time features: the evaluation scores every row, and training drops rows by `features.csv`'s own rules.
- **A failed run leaves nothing.** Records stream to `<run>/embeddings.bin.partial`, renamed to `embeddings.bin` at
  the end. On any failure in pass 2, the tool deletes its own run directory (a fresh one, made with
  `createDirectory`).
- **Outputs**, in a fresh `<out>/embed-<stamp>/`:
  - `embeddings.bin` (`EmbeddingFile`);
  - `embeddings.json`: `{"toolGitCommit", "generatedAt", "featuresRun", "featuresCsvSha256", "featureVersion",
    "datasetSha256", "labelsSha256", "encoder": {"name", "sha256", "dimension"}, "onnxRuntimeVersion",
    "films": {"dev", "test"}, "rows": {"dev", "test"}, "devSha256", "cpuSeconds"}`. `featuresRun` is the features
    run's directory name, and `featuresCsvSha256` the SHA-256 of its `features.csv`. `datasetSha256` and
    `labelsSha256` are copied from its `summary.json`. `devSha256` is one SHA-256 over the development records'
    bytes, as written, in file order;
  - `look.json` under `--look=L5`, as in L3b-look.
- **Determinism.** Single-threaded, with fixed batches: the same inputs on the same host and image give a
  byte-identical `embeddings.bin`. Test films never enter `devSha256`, so a run with `--look=L5` and one without give
  the same `devSha256`. The planner checks that at L5, and a difference stops L5.
- **Where it lives and how long it takes.** `embeddings.bin` is library data (it holds media file ids), never
  committed. It stays in `credits-dataset-out` and later tools read it from `/out`; the planner copies only
  `embeddings.json` into `~/cinefilm-labels`. There are about 320,000 development rows (253,159 coarse frames, plus
  the dense ones). With MobileNet's 1,000 dimensions (L4a), a record is 4,017 bytes, so about 1.3 GB. At L4a's
  measured 32 ms a frame:
  - **the development run:** about 3 hours;
  - **L5's run with `--look=L5`, only if B won the development comparison,** embeds every row again, development and
    test, about 440,000: about 4 hours.
  - Long runs are fine (owner, 2026-11-19): any time of day, at niceness 19 on one CPU, with no overnight-only
    scheduling.
- **No reuse of an earlier run's records, decided.** An option to embed only the rows missing from a prior run
  would cut L5's run to the test rows (about an hour at L4a's 32 ms), but then `devSha256` would be equal by
  construction. Embedding the development rows again is what checks that the encoder is deterministic on the host,
  which model B's evaluation (L4b-6b) and the product (L6a) rely on; and the owner accepts long runs.
- **Tests:**
  - a smoke test on a fixture dataset and features run, with a pure-Java stub encoder (each frame's mean R, G, B),
    checking every output file and each record's key against its `features.csv` row;
  - each refusal with nothing written: a test row without the flag (a frame source seam records that no JPEG was
    opened), `--look=L5` without `look.json`, a stale fingerprint, a missing JPEG, a dataset hash that differs, an
    encoder SHA-256 that differs;
  - a JPEG of the wrong size found in pass 2, after records were written: the run directory is gone;
  - batching: 17 coarse rows make batches of 16 and 1, and a film's coarse and dense rows never share one;
  - under `--look=L5`, test records are written, `look.json` is right, and `devSha256` equals the run without it on
    the same fixture;
  - determinism: two runs give byte-identical `embeddings.bin`, with the stub and with the tiny ONNX fixture.

### The training scaffold (L4b-4a)

Model B trains on exactly model A's rows and folds, so `CreditsTrainJob`'s reading and fold structure are extracted
first, in their own PR, with no change to its output:

- **`TrainRows.read(Path features, boolean withDense, boolean litTextVariant)`** reads `features.csv` with the
  existing checks (the header, the order, the first row that is not a development row with a fold stops the run),
  applies `--lit-text-variant`, then drops `left_out` rows, rows without time features and, without `--with-dense`,
  dense rows. It returns a record with:
  - `kept`: each kept row's index among `features.csv`'s data rows (0-based, blank lines not counted), its fold,
    whether it is coarse, its label and its features;
  - `keys`: **every** data row's key in file order, kept or dropped, as three parallel arrays (`long[]
    mediaFileIds`, `byte[] kinds` with 0 coarse and 1 dense, `long[] offsetsMs`), whose length is the data row
    count. About 440,000 rows at most, 17 bytes each. Model B checks its embeddings against these (L4b-5).
- **`FoldModels<M>`:** given the kept rows' folds and a function that fits a model without a set of folds, it builds
  the five shipping, ten pair (`"k,j"`) and ten triple (`"k,j,m"`) models in the existing order and keys, and the
  out-of-fold log loss (`oof-logloss.json`'s shape) from a function that predicts a kept row.
- **`CreditsTrainJob`** uses both. Its tests pass unchanged. A new test runs it on the existing training fixture and
  compares each of its four files' SHA-256 with a constant recorded from the code before the change; the files
  themselves are not committed.
- **Tests:** `keys` holds dropped rows too, in order, with the right count; `kept`'s indices on a fixture with a
  blank line; the SHA-256 test.

### `LogisticRegression` (pure, L4b-4b)

- **Input.** A `float[][]` of rows (each `dimension` long) and an `int[]` of labels, in the order given. A NaN or
  infinite value, a label other than 0 or 1, a row of the wrong length, no rows, or a single class is an
  `IllegalArgumentException`. Rows stay `float`, because about 243,000 rows of 512 doubles would not fit the tool
  run's heap.
- **Parameters** (a record, `LrParams`): `l2` (default 1.0, finite and > 0), `maxIterations` (default 300, 1–10,000),
  `tolerance` (default 1e-6, finite and > 0).
- **Standardisation.** For each dimension `j`, `μ_j` is the mean over the rows and `s_j` the square root of the mean
  squared deviation, both in `double` in row order; `s_j` below 1e-12 becomes 1. A row's standardised value
  `x̃_ij = (x_ij − μ_j) / s_j` is computed when needed, never stored.
- **The fit.** `z_i = b + Σ_j w_j x̃_ij` (summed over `j` ascending, in `double`). It minimises
  `J = Σ_i (softplus(z_i) − y_i z_i) + (l2 / 2) Σ_j w_j²`, with `softplus(z) = max(z, 0) + log1p(exp(−|z|))`; the
  bias `b` is not penalised. The gradient is `Σ_i (σ(z_i) − y_i) x̃_i + l2 · w` for `w`, and `Σ_i (σ(z_i) − y_i)` for
  `b`, each summed in row order.
  - **Start:** `w = 0` and `b = base`, where `base = ln(p / (1 − p))` with `p` the share of label 1.
  - **L-BFGS**, memory 10: the two-loop recursion over the stored pairs, oldest dropped first; a pair is stored only
    when `s · y > 1e-10`. The first direction is the negative gradient scaled by `1 / ‖g‖`.
  - **Line search:** backtracking from step 1, halving up to 40 times, accepting the first step with
    `J(new) ≤ J + 1e-4 · step · (g · d)`.
  - **Stops** when the largest `|g| / n` is at most `tolerance` (`"gradient"`), after `maxIterations`
    (`"iterations"`), or when no step is accepted (`"lineSearch"`). The model records `iterations` and `stopped`.
- **Predict.** `p = 1 / (1 + e^−z)`, for one `float[]` row.
- **`base()`** is `ln(p / (1 − p))` of the training labels, as `GradientBoostedTrees.base()` is: the segmenter's
  `logitPi` is the mean of an ensemble's `base`s (L3c), so both model types give it the same meaning. It is not the
  fitted bias.
- **JSON.** `{"dimension", "base", "params", "mean", "scale", "weights", "bias", "iterations", "stopped"}`, doubles
  written by Jackson. Reading refuses (`IllegalArgumentException`) a missing field, a wrong type, an array whose
  length is not `dimension`, a non-finite number, and an unknown `stopped`.
- **Cost, estimated.** One evaluation of `J` and its gradient is about 2 × rows × `dimension` multiply-adds: about
  250 million for 243,000 rows at 512, well under a second. At up to 300 iterations, a fit takes a few minutes, so a
  training run's 25 fits take about an hour on one CPU. **At MobileNet's 1,000 dimensions (L4a)** an evaluation is
  about 486 million, so a training run takes about 2 hours. The first run's time is recorded and replaces this.
- **Tests:** exact standardisation, including a constant column; `J` and the gradient on a 3-row, 2-dimension set;
  the gradient against central finite differences; a 1-dimension, 4-row set whose optimum the test finds by
  Newton's method, matched to 1e-6; each stop reason; each refusal; the JSON round trip (the read model predicts
  identically); determinism (two fits of the same rows give byte-identical JSON).

### `CreditsTrainLrMain` (L4b-5)

- **Arguments:** `--features <run>/features.csv --summary <run>/summary.json --manifest <split manifest>
  --embeddings <embeddings run> --out /out [--l2 X] [--max-iterations N] [--tolerance T] [--with-dense]
  [--lit-text-variant]`. There is no `--look`: it is an unknown flag (exit 1).
- **Rows:** `TrainRows.read`, exactly as model A, with the same refusals. A test row or an empty fold stops the run:
  exit 1, no model written.
- **Embeddings:** `embeddings.bin` is read in step with `TrainRows`'s `keys`, record `i` with data row `i`.
  - Refused (exit 1, nothing written): a record whose key differs from `keys` at its index; a record count other
    than the length of `keys`; `embeddings.json`'s `featuresCsvSha256` not the SHA-256 of the given `features.csv`;
    its `datasetSha256`, `labelsSha256` or `featureVersion` not `summary.json`'s.
  - Only the kept rows' embeddings are held, as `float[]`; the others are checked and skipped.
  - **Memory:** before any float is held, kept rows × `dimension` × 4 bytes must be at most 70% of
    `Runtime.maxMemory()`, or the run is refused with the bytes it needs. The base run at 512 dimensions needs about
    500 MB, within `-Xmx2g`. **With MobileNet's 1,000 dimensions (L4a)** the base rows need about 1 GB and the
    `--with-dense` rows at most about 1.3 GB, both under `-Xmx2g`'s 70% (about 1.5 GB), so the standard tool-run
    command is kept.
- **Models:** `FoldModels` with `LogisticRegression.fit` and `LrParams`.
- **Outputs**, in a fresh `<out>/train-lr-<stamp>/`, the same four files as model A's run, so `CreditsEvaluateMain`
  takes a model-B run with `--train`:
  - `model.json`: `type` `"logreg"`, `featureVersion`, `encoder` (`name`, `sha256`, `dimension`), `params`,
    `withDense`, `litTextVariant`, `models` (the five shipping models), `toolGitCommit`, `datasetSha256`,
    `labelsSha256`, `manifestSha256`, `embeddingsRun` (the run's directory name) and `embeddingsDevSha256` (its
    `devSha256`). No thresholds yet (L6a copies them from `calibration.json`). No ids;
  - `oof-models.json` (ten pair models), `nested-models.json` (ten triple models), `oof-logloss.json`.
- **Determinism:** the same inputs give byte-identical files apart from the stamp.
- **Tests:** a fixture features run and embeddings run (written with `EmbeddingFile`): each drop rule and variant
  keeps the same row indices as `CreditsTrainJob` on the same CSV; each refusal above with nothing written,
  including a key that differs on a dropped row and the memory cap (with the maximum memory injected); `--look`
  refused; a recording fitter that proves no model used for fold `k`'s predictions saw a row of fold `k`, and no
  triple model saw a row of its folds; the log loss on a hand-checked case.

### `CreditsEvaluateMain` reads model B (L4b-6a, L4b-6b)

The segmenter, `FilmScore`, `CreditsCalibration`, `EvaluationReport` and the output files are unchanged. **One
evaluation holds runs of one model type** ("Choosing"): L5 evaluates model A's runs and model B's runs separately,
each with its own nested selection over the same grid and segmenter, and compares the two held-out summaries.

**L4b-6a: films one at a time, and model B's model files.** No behaviour change for model A.

- **`FeatureRunReader.forEachFilm(Path features, boolean look, FilmSink sink)`**, where `FilmSink.accept(FilmRows
  film)` may throw `IOException`, hands each film over as soon as its last row is read, with the same checks as
  `read`. `read` stays, as `forEachFilm` collecting into a list, for its existing tests.
- **`CreditsEvaluateJob`** uses `forEachFilm`. A development film's predictions are computed as it arrives (as
  `devFilm` does now). **The test path moves:** a test film's `Scene` keeps, for each run, the mean of its five
  shipping models' predictions, computed as it arrives; after `chooseFinal`, the final run's entry is scored.
  Neither path keeps a film's rows past its scoring inputs. The output is byte-identical to before on the existing
  fixtures (the existing tests pass unchanged).
- **`FrameModel`:** a sealed interface that `GradientBoostedTrees` and `LogisticRegression` implement, with
  `double base()`.
- **`TrainedRun.load`** reads `model.json`'s `type`: `"gbt"` as now, `"logreg"` through `LogisticRegression.fromJson`,
  anything else refused. It keeps the type and, for `"logreg"`, the encoder's `sha256` and `dimension` and
  `embeddingsDevSha256`.
- **Refused** (exit 1, nothing written): training runs of more than one type in one evaluation; any `"logreg"` run
  (until L4b-6b adds `--embeddings`).
- **Tests:** the existing evaluation tests unchanged; test films scored after `chooseFinal` by the final run's
  shipping models, through the moved path; a sink that records films proves each arrives once, in order, as its last
  row is read; `TrainedRun.load` on a hand-made `"logreg"` run and on an unknown type; mixed types refused.

**L4b-6b: scoring model B.**

- **`--embeddings <embeddings run>`** is a new single flag.
- **`EmbeddingCursor`** reads `embeddings.bin` in step with `forEachFilm`: for each film it returns that film's
  coarse and dense embeddings, checking each record's key against the film's rows. Only one film's embeddings is
  held at a time; all films' would take about 1.8 GB with the test films (1,000 dimensions), more than `-Xmx2g`
  leaves.
- **Refused** (exit 1, nothing written), beside L3c's refusals:
  - `"logreg"` runs without `--embeddings`, and `--embeddings` with `"gbt"` runs;
  - `embeddings.json`'s `featuresCsvSha256` not the SHA-256 of the features run's `features.csv`; its
    `datasetSha256`, `labelsSha256` or `featureVersion` not the features run's;
  - its encoder `sha256` or its `devSha256` not those of every run (so model B is scored on the embeddings it was
    trained on);
  - `--look=L5` with an embeddings run that has no `look.json`;
  - a record whose key differs from its `features.csv` row, or a record count other than the row count.
- **A frame's `p`:** NaN when its `features.csv` row has no time features, as for model A ("Finding the start",
  step 5); otherwise `LogisticRegression` on its embedding.
- **Tests:** a B-only evaluation on fixture films with hand-made `"logreg"` runs, checking every output file; a dense
  row with no time features gets no `p` although it has an embedding; each refusal above, with nothing written;
  under `--look=L5`, test films scored by the final B run's shipping models; a cursor seam that proves at most one
  film's embeddings is held; a model-B run written by `CreditsTrainLrMain` on a fixture, read back and evaluated.

### The PRs, and when each is done

- **L3c-grid** is done when the grid tests pass, merged before the L4b-2 release.
- **L4b-1** is done when the natives check is recorded in the PR, and the `EncoderSpec`, `EncoderInput`,
  `OnnxImageEncoder` and `TinyOnnx` tests pass (on the Mac or in CI, by the branch).
- **L4b-2** is done when the `EmbeddingFile` and benchmark tests pass, and a benchmark run on the tiny fixture writes
  `bench.json`. The owner releases after it.
- **L4b-3** is done when the embeddings smoke test, every refusal and the determinism test pass.
- **L4b-4a** is done when `CreditsTrainJob`'s four files match the recorded SHA-256 values and the `TrainRows` tests
  pass.
- **L4b-4b** is done when the `LogisticRegression` tests pass.
- **L4b-5** is done when a fixture model-B run writes its four files, and the row-identity, key and fold tests pass.
- **L4b-6a** is done when the existing evaluation tests pass unchanged on `forEachFilm` and the moved test path, and
  the `"logreg"` model files load.
- **L4b-6b** is done when a B-only evaluation, the refusals and the one-film test pass. The owner releases after it.

### The planner's runs

- **After the L4b-2 release:** model A's three runs evaluated again on the widened grid; then L4a, the benchmark on
  each candidate (the owner runs the planner's download command first).
- **After the L4b-6b release:** the embeddings run on the chosen encoder, then **at most seven model-B training
  runs**: `l2` ∈ {0.1, 1, 10} on the base rows and with `--with-dense`, and `--lit-text-variant` at `l2` 1. At about
  2 hours each at MobileNet's 1,000 dimensions, about 14 hours in all. Then one B-only evaluation over them (estimated at under an hour: the grid
  doubled, and there are more runs). Its choices and figures are recorded here before L5's look, beside model A's.

### Settled by the owner (2026-11-19)

- **ONNX Runtime in the image:** accepted. It is in every image built after L4b-1 merges, with no separate tools
  image. The encoder file is added only if B wins (L6a).
- **Encoder files:** fetched onto the server at L4a. The planner writes the download command, and the owner runs it;
  it checks each file's SHA-256.
- **Long tool runs:** any time, at niceness 19 on one CPU, with no overnight-only scheduling.
- **Releases:** after L4b-2 (for L4a), and again after L4b-6b.
- **No `osx-x64` natives (L4b-1's check, 2026-11-19):** `onnxruntime` 1.31.1 (pinned), 1.31.0 and 1.30.0 ship
  `linux-x64` but no `osx-x64`, and the dev Mac is Intel. The owner approved the CI-only branch:
  `OnnxImageEncoderTest` is `@EnabledOnOs(LINUX)`, and a Maven profile active only on macOS
  (`mac-no-onnx-natives`) leaves `OnnxImageEncoder` out of the JaCoCo `check`; on Linux CI the class is under both
  floors. The jar is about 49 MB (all platforms' natives); the owner accepted that growth of the image.

## Settled for later phases (from review round 4)

- **L3a, plain frames.** The "first 10 s run of plain frames" rule is evaluated on **coarse** frames (two consecutive
  plain coarse frames make one 10 s run). The labels it produces apply to dense frames too.
- **L2/L3, dense time features.** Superseded by L3a's rule ("`CreditsFeatures`", time features). A dense frame
  has time features only when both its ±10-frame neighbours exist in its own decoded list. Neighbours are counted by
  index, with no clamping.
  - **In the dataset,** a full range (target ± 30 s, 120 frames) gives indices 10–109: `[target − 25 s,
    target + 25 s)`. A list cut short, at the file's end or by ffmpeg writing fewer frames, covers less.
  - **In the product** (`[t − 15 s, t + 35 s)`), a full range covers `[t − 10 s, t + 30 s)`. That includes the
    segmenter's dense search (`f ≥ t − 5 s`, over `[f, f + 5 s]`).
  - **L6a must handle the cut-short case:** a range clamped at the file's end, where the search has fewer frames
    with time features.

These go into each phase's brief as written:

- **L2a, the luma range.** ffmpeg's `rgb24` output is full range (black 0), but the product's `format=gray` keeps the
  limited range (black 16).
  - `FramePrep` produces full-range luma.
  - `ActiveArea.of` keeps getting the product's existing `format=gray` frames, unchanged.
  - Every threshold in this plan that applies to `FramePrep` frames (`OpeningType`'s 35, 120 and 40) was a
    placeholder. L3a re-derived them on development frames ("L3a specification"). The P0 table was measured on
    limited-range Y.
- **L2a/L2b, one command shape for dense frames.** The dataset decodes dense frames exactly as the product does:
  one `fps=2` sequential decode per range, not one seek per frame. The dataset's dense range is the target ± 30 s.
- **The time features** of a dense frame use dense neighbours ±10 frames (±5 s), in both training and the product.
  There is no clamping: see the L2/L3 item above. Coarse frames use coarse neighbours, and at a window end the one
  neighbour is used for both.
- **L2b creates the directories** `credits-dataset` and `credits-dataset-out` as uid 1001 at the job's first start.
- **L2b ships a stills and measurement `main`.** The planner's 10-film measurement before L3a (grey and colour
  statistics, stills) runs on released code. Nothing is installed.
- **L6a checks ffmpeg drift.** It compares the runtime ffmpeg version with `complete.json`'s. If they differ, it
  re-scores 20 development films through the product before the backfill.
- **Protecting the test split in code (L3b onwards).**
  - The training `main` always refuses test rows (L3b: any `side` other than `dev`, or an empty `fold`).
  - The features, embedding and evaluation `main`s refuse them unless an explicit `--look=L5` is passed.
  - The gate's look is a database query, logged by hand ("The gate, revised").
  - **Where a look is logged.** Tool runs mount `/labels` read-only, so a tool never edits the manifest. Each run
    with `--look` writes `look.json` into its own output directory: the flag, the time, the git SHA and the count of
    test films. The planner copies each one into this file's looks log, under "Phases".
  - **What counts as a look.** Only `CreditsEvaluateMain`'s run with `--look=L5`, and the gate's query. Extracting
    test features or embeddings under `--look` is logged, but it is not a separate look: such runs happen only after
    L5's choice is recorded, as part of its single look.
  - **The opening thresholds.** P0's were picked by eye on all 849 films, test films included. L3a mapped them to
    full range and confirmed them on development films (`PLATE`), or replaced them from development data (`DARK`).
    Nothing was fitted to test films, and P0's by-eye choice is accepted as a prior.
- **The model file is public.** `credits-model-v1.json` holds no titles, paths or media ids, only weights,
  thresholds and hashes.
- **L6a stores two new columns.** It adds `credits_detection.opening_type` and `margin_band` (an expand-only
  migration). Both are computed at the coarse `t` from steps 2–4 of "Finding the start", before the dense step: the
  margin band from `S(t, u*)`, and the opening type from `OpeningType.of(coarse, t)`. The reasons text's `<opening>`
  is that same value. The gate's per-band test rule groups the test rows by
  those stored values, the same values `AUTO` will be switched by. Plex's start is used only for the "opening" column
  in reports.

## Risks

- **Plex's habits are learned too.** The ±5 s exclusion, leaving out lit openings, stills of the worst films, and
  recorded left-out reasons answer this.
- **The library is biased** towards recent studio films. Every `FOUND` among unmarked films is checked by stills,
  and `OWNER` stays the last word.
- **Overfitting.** Answered by grouping by film and collection, nested cross-validation, the safety margin, the
  ensemble, and two looks at the test split.
- **Load.** Answered by the in-server checkpoint before every ffmpeg call, no lock, yielding to new films, niceness
  19, the disk floor, and tool runs capped at one CPU in their own container.
- **Model and code drift.** Answered by one implementation, the CI version test, and the hashes in the model and the
  reasons text.
- **Archive loss.** It is kept outside the cache, on the server and on the Mac, and re-taken before Plex is retired.
