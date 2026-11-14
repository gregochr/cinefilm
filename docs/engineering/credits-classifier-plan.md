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
- **The baseline**, version 2, is scored the same way on the same films.
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
  - **one variant is tested in cross-validation**: including the lit frames that also show text. It is adopted only
    if the earliest out-of-fold error does not get worse.
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
computed on the product's 240 px grey frames, inside the active area:

- **`frameKind(frame)`:**
  - `DARK` when the mean luma is below 35;
  - `PLATE` when the mean is at least 120 and the 10th–90th percentile spread is at most 40;
  - `LIT` otherwise.
- **`OpeningType.of(start)`** uses the coarse frames nearest to `start` + 5, 20 and 45 s:
  - `DARK` if the +5 s frame is `DARK`;
  - otherwise `PLATE` if any of the three is `PLATE`;
  - otherwise `LIT`.
- The P0 table was measured with ffmpeg on the centre crop. L3 recomputes it with this definition, on development
  films only, and the recomputed figures replace the P0 table.
- **Margin bands**, in units of the calibrated `σ`: `[σ, 2σ)` is `low`, `≥ 2σ` is `high`.

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
  margin band. It is never retuned and scored again. Test films are never relabelled, so a Plex mistake on a test
  film can switch `AUTO` off for its band. That cost is accepted, and is put to the owner with the L5 report.
- **Thresholds keep a safety margin:** the earliest out-of-fold error must be at least −5 s.

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
  - When playback blocks it, it sets a deferred flag, and `CreditsScheduler`'s hourly nudge restarts it.
- **Niceness:** set for each job (default 19). It is passed into the reader, which today takes niceness only from
  `cinefilm.transcode.nice`.

## Two model types

Both produce `p(credits)` for each frame, and both feed the same segmenter. They are compared on development
through **offline evaluation**: the shipping model and segmenter code, run on the stored frames.

- When the product's dense range for a predicted `t` (`[t − 15 s, t + 35 s]`) is **not fully** stored, the film is
  scored at **`t − 5 s`** for every early metric. That is the earliest the dense step could ever return, so the early
  check is worst-case and never favourable. For the ±10 s and ±60 s goals it is scored at `t`.
- **Neither model has a position feature.** Nothing tells a model how far into the film a frame is. That is a rule.

### A. Gradient-boosted trees on measured features

- **Features (`CreditsFeatures`).** Measured on the 240 px grey frame inside the active area, treating light and dark
  alike:
  - background plainness;
  - ink, both polarities;
  - thin sharp runs, text rows, central and bottom-band shares, line spacing;
  - edge energy, and a 4×4 layout grid;
  - two time features against the frames ±5 s away: mean absolute difference and changed-ink share. At the window's
    ends, the one neighbour is used for both. There is no crawl-shift feature, because at a 5 s spacing it aliases.
- **The planner owns the exact feature list and formulas.** It is written into L3a's brief after measuring 10
  films; Sonnet implements it and does not design it.
- **Trees (`GradientBoostedTrees`).** Logistic loss, shallow trees, quantile bins, unweighted classes.
  - Depth, count and learning rate are chosen in cross-validation.
  - **Deterministic:** single-threaded; inputs sorted by (file, offset); ties broken by the lowest feature index,
    then the lowest threshold; quantiles from sorted values. A test trains twice and compares the JSON byte for
    byte.
- **Where it is computed:** features and training run in a **throwaway container** on the server, from the released
  image, reading the dataset read-only (see "Tool runs"). Only the model JSON comes back.

### B. Pretrained image embedding plus logistic regression

- **The encoder** is chosen by the planner in L4a. That happens after L4b is released (L4b ships the benchmark
  `main`) and before any product change. Candidates, in order:
  1. CLIP ViT-B/32's image tower, quantised, from the `Xenova/clip-vit-base-patch32` ONNX export (MIT, about
     90 MB, outputs `image_embeds`);
  2. a smaller MobileNet-class ONNX model from the ONNX Model Zoo (Apache 2.0), if CLIP is too slow or too large.
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
  there. **The production image and server change only if B wins** (L6).
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
- **A tie goes to A**, which needs no dependency and no download.
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
   - If there is none, the start is the coarse `t`, never earlier.
   - This `CreditsFrameReader` change (from 300 s to 50 s, with `DENSE_TIMEOUT` scaled down) belongs to L6a.
6. **Vetoes:**
   - the edition guard;
   - the dialogue check, where cue times are already known; it moves out of `CreditsFinder` in L6a;
   - only the last original part is analysed.
7. **Reasons text:** `Credits model v1 (A|B): credits from 2:18:47, <opening> (margin 41.2, min p 0.97 over 15 s)`.

**Calibration.** `λ`, `δ`, `G`, `σ`, `θ` and `θ_d` are chosen together in cross-validation: first meet the −5 s
margin, then maximise the ±10 s share.

- **Negatives come from real films.** Each labelled film's window is cut off at the target start − 5 s, and that
  must give `NOT_FOUND` or `UNSURE`.
- For each film, the best film-only stretch score (stretches ending at or before start − 5 s) must sit below `σ`,
  with margin.
- Negatives follow the same folds.

**Epilogue cards** that Plex itself counts as credits can't be seen by the Plex gate. The development films whose
result lands more than 10 s before the first credits-on-plain frame are looked at as stills and reported.

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
    releases after L1, L2b, L3c and L4b.

  - It has its own memory limit, separate from the server's, and one CPU at the lowest priority.
  - It reads only local files, never the share.
  - Nothing is installed: it is the same image.
  - The Mac has 17 GB free, so it does not hold the dataset.

## The gate, revised

The phase 2b gate runs after the version 3 backfill:

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
- **L3a — `OpeningType`, the end-title flag and `CreditsFeatures`**, to the planner's exact list. Synthetic frames: text on black, text on white, white on
  light grey, a card change, a burned-in subtitle, a bright textured scene, a dark picture.
- **L3b — `GradientBoostedTrees`**, the model JSON, the determinism test and the feature-version test.
- **L3c — `CreditsSegmenter`.** Tests:
  - a clean step;
  - a short `p ≈ 0.5` stretch early in the window before the real credits;
  - a mid-credits gap;
  - a post-credits scene;
  - an isolated false positive;
  - one card, then picture, then credits;
  - epilogue cards running into the credits;
  - no credits;
  - the dense fallback.
- **L4a — the encoder check (planner, no PR, after L4b is released):** CPU and RSS for each candidate, measured with
  L4b's benchmark in a tool run.
- **L4b — model B's tool code:** ONNX Runtime, the embeddings, logistic regression and the benchmark `main` (CPU
  time and peak RSS), with a tiny ONNX fixture. No product wiring.
- **L5 — the comparison (planner).** Tool runs, nested cross-validation, the choice, then the first look at the test
  split. The results go into this file as "L5: what the models showed".
- **L6a — the model in the product.**
  - The winner and the segmenter go in `CreditsDetectionService`.
  - The dense reader becomes 50 s, and the cue check moves.
  - `ANALYSIS_VERSION = 3`, with the model JSON committed.
  - If B won, the Dockerfile adds the encoder `ADD`.
  - The v2 snapshot is taken **before L6a merges**, so an unrelated release of `main` can't deploy version 3 first.
    The pre-release `pg_dump` is the backstop.
- **L6b — removal:** the rule classes (once nothing uses the 2b flag), the losing model, and the dataset job.
- **Then** the revised gate (the second look at the test split), then phase 3 of `credits-detection-plan.md`.

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
  - `looks` records each use of the test split (`--look=L5`, `--look=gate`) and is copied unchanged from the previous
    manifest.
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

- **The v2 detector's results on test films.** The v2 backfill is running now. Until L5, its results are compared
  with Plex on development films only. The baseline is scored on the test split only within L5's single look.

### Re-import

- **Rows can go stale.** The 2c import replaces rows but never deletes them (`PlexCreditsImportService.java:52`).
  After Plex re-analyses a film, a row can outlive its marker. Taking the archive and the export in one sitting
  mostly covers this, and L1's shift check catches a changed earliest marker.

## Settled for later phases (from review round 4)

- **L3a, plain frames.** The "first 10 s run of plain frames" rule is evaluated on **coarse** frames (two consecutive
  plain coarse frames make one 10 s run). The labels it produces apply to dense frames too.
- **L2/L3, dense time features.** These are computed only for dense frames that have ±5 s neighbours inside **both**
  the dataset's range and the product's. That is the part the two ranges share.

These go into each phase's brief as written:

- **L2a, the luma range.** ffmpeg's `rgb24` output is full range (black 0), but the product's `format=gray` keeps the
  limited range (black 16).
  - `FramePrep` produces full-range luma.
  - `ActiveArea.of` keeps getting the product's existing `format=gray` frames, unchanged.
  - Every threshold in this plan that applies to `FramePrep` frames (`OpeningType`'s 35, 120 and 40) is a
    placeholder. L3a re-derives it on development frames before use. The P0 table was measured on limited-range Y.
- **L2a/L2b, one command shape for dense frames.** The dataset decodes dense frames exactly as the product does:
  one `fps=2` sequential decode per range, not one seek per frame. The dataset's dense range is the target ± 30 s.
- **The time features** of a dense frame use dense neighbours ±10 frames (±5 s), clamped at the range ends, in both
  training and the product. Coarse frames use coarse neighbours.
- **L2b creates the directories** `credits-dataset` and `credits-dataset-out` as uid 1001 at the job's first start.
- **L2b ships a stills and measurement `main`.** The planner's 10-film measurement before L3a (grey and colour
  statistics, stills) runs on released code. Nothing is installed.
- **L6a checks ffmpeg drift.** It compares the runtime ffmpeg version with `complete.json`'s. If they differ, it
  re-scores 20 development films through the product before the backfill.
- **Protecting the test split in code (L3b onwards).** The training and evaluation `main`s refuse rows with
  `side = test` unless an explicit `--look=L5` or `--look=gate` flag is passed. Each use is logged in the manifest.
  The P0 opening thresholds, measured on all films, are only placeholders (above).
- **The model file is public.** `credits-model-v1.json` holds no titles, paths or media ids, only weights,
  thresholds and hashes.
- **L6a stores two new columns.** It adds `credits_detection.opening_type` and `margin_band` (an expand-only
  migration). Both are computed at the product's own start `t`. The gate's per-band test rule groups the test rows by
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
