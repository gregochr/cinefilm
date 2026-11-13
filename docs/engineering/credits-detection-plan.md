# Credits detection — plan

Written 2026-11-12, revised the same day after an adversarial review (checked against the code). Owner report: the
"The end" overlay came up during a film's climax.

## Why it happens

`edition.credits_marker_ms` (V1) exists, is carried through regrouping (`StateCarry`, `EditionJoin`) and is read by
the player (`resume.js` `endThresholdMs`), the watched rule (`WatchedRule`, used by `PlaybackService`) and the Plex
merge (`PlexWatchMerge`), but **nothing ever sets it**. Every film falls back to 92% of its runtime (handover
decision 1). On a two-hour film that is about 9.5 minutes before the end: often still the climax.

There is no public database of film credit start times worth relying on (the skip-intro/credits databases are almost
entirely TV episodes; Plex computes its own markers, Plex Pass only, from the owner's files). So CineFilm works out
the marker itself, **once per file, from the picture**, and stores it.

## Goals

- Every edition gets a credits marker, worked out in the background with no owner effort.
- **Every film added to the library is analysed after the scan that finds it** (owner, 2026-11-12), as the
  OpenSubtitles search already runs on `ScanFinished`.
- **An early marker is the failure that matters.** Only a confident result is applied; anything else keeps the
  fallback. Every rule below is biased to "late or nothing".
- Playback is never slowed by the analysis (network resilience is the owner's top priority).
- The owner can see what was found and correct it.

## Split editions (owner: "watch for two-part films, e.g. LOTR extended")

A split edition is one timeline (`EditionTimeline`); the marker is an **edition time**. Credits are only ever at the
end of the **last part**:

- Only the last original part (`Edition.originalParts()`, play order) is analysed. Part 1's end (a fade, an
  intermission card, "End of Part One") is never looked at, so it can never produce a marker.
- The detection is stored **against the file** (`media_file_id`, fingerprint, offset inside the file). The edition
  time is derived when applying, with `EditionTimeline.of(edition).toGlobal(lastIndex, offset)`. Regrouping then
  never re-reads a file: it re-applies (or clears, when the analysed file is no longer an edition's last part).
- An edition whose parts have no durations (`timelineDurationMs` null) gets no marker (`NO_TIMELINE`).
- Each edition is analysed on its own (theatrical and extended cuts are different files).
- Long credit rolls must work: *Return of the King* extended has about 25 minutes of credits.

Fixtures must include a two-part edition whose part 1 ends on black: the marker lands in part 2, part 1 yields
nothing.

## Detection (backend, package `gregochr.cinefilm.credits`)

### Reading frames

Frames are written by ffmpeg as **greyscale PGM files into a per-job temp directory** and read by Java, the way
`StillService` already writes images to disk. Nothing binary is piped through `ProcessLineStreamRunner` (it decodes
stdout as text). Every command follows the existing rules: argument list, `-nostdin`, `-protocol_whitelist file`,
absolute path last, `nice`, `-an -sn -dn`, `-threads 1`, a timeout with the process killed, the temp directory
always deleted.

1. **Active picture.** `cropdetect` on 5 single frames between 30% and 60% of the part gives the image area without
   letterbox or pillarbox bars. All shares below are measured **inside that area only**: a 2.39:1 film's bars are
   about 25% of a 16:9 frame and would otherwise count as dark.
2. **Coarse pass.** One single-frame input seek (`-ss`, like `StillCommand`) every 5 s across the window, scaled to
   480 px wide. A seek reads one GOP, so the window costs about 1 GB over SMB instead of the 15–20 GB a sequential
   decode of a 4K remux would.
3. **Dense pass.** One sequential decode at 2 frames a second over 3 minutes around the coarse candidate, to place
   the marker to the second and to see motion. Timestamps come from the seek time plus the frame's index at the fixed
   output rate, never from counting decoded frames.

### Window

The last `min(35 minutes, 40% of the part)` of the last part. Credits starting before the window are not found.

### Per-frame features (pure, `FrameFeatures`)

Thresholds are **relative to the window's own peak** (the 99.5th percentile luma across the coarse frames), so PQ/HDR
sources whose white text lands around 140–150 luma after the grey conversion behave like SDR ones.

- dark share: pixels below 15% of peak; bright share: pixels above 65% of peak.
- text-likeness: bright pixels in thin horizontal runs with sharp edges, mostly inside the central 70% of the width.
- `BLACK`: dark share ≥ 0.98. `TEXT_ON_DARK`: dark share ≥ 0.80, bright share 0.002–0.20 and text-like.
  `PICTURE`: anything else.

### Finding the start (pure, `CreditsFinder`)

Searched **backwards from the end of the file**, so the answer is the start of the steady credits, not the start of
some earlier dark stretch:

- Walk back from the end while seconds stay credits-like (`BLACK` or `TEXT_ON_DARK`), allowing `PICTURE` gaps up to
  120 s (mid- and post-credits scenes). The run must be at least 60 s long with at least 30 s of `TEXT_ON_DARK`.
- **Motion anchor:** the marker is placed **where the credits start moving**, never earlier. In the dense pass, 30 s
  windows are tried from the first text frame onwards, 5 s apart; the first window that looks like credits moving
  gives the start: a crawl (rows of text shifting up by a steady number of pixels per frame, found by matching
  row-brightness profiles between frames; the start is the first moving pair) or cards (static text that changes
  abruptly at least twice; the start is the first change, so the first card's few seconds are given up). Still text
  before that point (an epilogue card, a starfield, candles, a night sky) is never part of the marker, so searching
  past it can only move the marker later. Still text with no motion anywhere in the dense pass is `UNSURE`.
- At most 10 s of `BLACK` immediately before the motion start counts as the start (the fade out); longer black, or
  black separated from the motion by still text, is part of the film. (Revised 2026-11-13 after local Codex: the
  first rule anchored the start to the first text frame, which could put the marker on an epilogue card.)
- The run must not start with a single `TEXT_ON_DARK` card followed by `PICTURE` ("Ten years later").

Known limit, accepted: a stylised colour title sequence before the crawl (Bond, Pixar, Marvel) is `PICTURE`, so the
marker lands where the plain crawl starts. That is late, not early.

### The last spoken line (only when already known)

`embedded_cue_starts` (V17) holds every cue start of a file's full English track, but is **only filled for films with
forced sidecars** (`SidecarTimingService`), a small minority. The detector uses it when present: dialogue continuing
more than 30 s past the found start (cues after a 120 s gap ignored) makes the result `UNSURE`. It never reads a
track just for this (that demuxes the whole file), and **no threshold relies on it**.

### Verdicts

- `FOUND`: every rule met and no subtitle contradiction. Applied (phase 3).
- `UNSURE`: a rule narrowly broken, or a subtitle contradiction. Stored with reasons, not applied.
- `NOT_FOUND`: no qualifying run (a short "The End" card, credits over picture, credits before the window).
- A failed or timed-out read is not stored; the file is retried with a back-off like `SidecarTimingRefresher`'s, so
  one bad file never loops.

Sanity guard on `FOUND`: the edition-time marker must be at least 60% into the edition and at least 60 s before its
end, else `UNSURE`.

## Storage (migration `V19__credits_detection.sql`, expand-only)

- Table `credits_detection`, one row per **media file**: `media_file_id` (PK, FK cascade), `fingerprint` (size and
  modification time, as V17), `verdict` (CHECK in `FOUND`, `UNSURE`, `NOT_FOUND`), `offset_ms` (inside the file,
  nullable), `reasons` (plain sentences), `checked_at`.
- `edition.credits_marker_source VARCHAR(10)` nullable: null (never set), `AUTO`, `OWNER`.
- **The source travels with the marker everywhere the marker moves:** `StateCarry`, `EditionJoin` (both places it
  carries `markerMs`) and `EditionArranger` (which clears the marker when the parts change: it clears the source
  too). A carried `OWNER` marker stays `OWNER`.
- Applying (a short write under a new `LibraryLock` holder `CREDITS`, revalidated against the fingerprint; the lock
  is never held while ffmpeg runs; a refused lock is retried later): for each edition whose last part has a `FOUND`
  row, set `credits_marker_ms` and `AUTO` unless the source is `OWNER`. When the source is `AUTO` and the last part no
  longer has a `FOUND` row, clear both.

## When it runs

`CreditsRefresher`, shaped like `OpenSubtitlesRefresher`:

- On `ScanFinished`: every last-part file with no row or a changed fingerprint (the "every new film" path), then
  re-apply for editions whose parts changed.
- On `ApplicationReadyEvent` and nightly after the 04:30 scan: the same, so the backfill of the ~1,090 films and any
  failures catch up. Order: Continue watching first, then most recently added.
- One file at a time. It **waits while anything is playing**: a transcode is running (`SessionManager.isEncoding()`)
  or any playback progress was reported in the last 5 minutes (direct play also reads over SMB).

## Watched rule

Today the marker is also the watched threshold. A late marker (after a title sequence) would stop a film counting as
watched when the owner stops during those titles, keep it in Continue watching, and change what a Plex re-import
counts as watched. Proposed: **watched at `min(marker, 92%)`** in `WatchedRule` and in `resume.js`'s watched check;
the overlay keeps using the marker alone. Existing in-progress states are not re-evaluated when a marker lands. This
touches handover decision 1, so it is an owner decision (below).

## Admin (phase 3)

An Admin "Credits" list: film, edition, verdict, detected time, reasons, and two stills (the existing
`GET /api/editions/{id}/still?atMs=`) at the detected time and 30 s earlier, so a glance shows "crawl" vs "still the
film". Actions: **Set** (owner time, `OWNER`), **Use detected** (back to `AUTO`), **Clear**.

- `GET /api/admin/credits`; `PUT /api/admin/editions/{id}/credits` `{ markerMs }` (400 outside the edition);
  `DELETE /api/admin/editions/{id}/credits` returns to automatic.

## Phases (each its own PR, Sonnet builds)

1. **Detector, report only.** Package, migration (table only), refresher with the playback wait and back-off; stores
   rows, **applies nothing**. Pure `FrameFeatures` and `CreditsFinder` unit-tested with synthetic frames: crawl,
   cards, black-only ending, short "The End" card, mid-credits scene, colour title sequence, letterboxed night scene
   with lights, starfield ending, epilogue card, dim HDR-like text, two-part edition, credits before the window.
   **Built 2026-11-13 in two PRs: #128 (the pure analysis) and this PR (reading, storage, refresher).**
   Differences from the text above, all in phase 1b:
   - The migration is `V20__credits_detection.sql` (V19 was taken by forced downloads). Table only, as planned.
   - Timeouts: 90 s per single-frame seek and 5 minutes for the dense decode (`CreditsFrameReader`). A whole file is
     abandoned on the first failure or timeout and retried after 6 hours (or at once when it changes).
   - Frames are written as PGM under `<cinefilm.cache.dir>/credits/job-*`, one fresh directory per job, deleted when the
     job closes and again at start-up for what a killed process left. Every single-frame seek reuses one file name and
     the reader deletes it after reading, so a seek that exits 0 without writing a frame can never be mistaken for the
     previous one. No `cropdetect`: the active area uses the pure `ActiveArea` on the five 30-60% frames.
   - A coarse frame is never taken closer than 1 s to the end of the file (a seek there can find no frame to decode).
   - The playback wait uses `PlaybackState.lastPlayedAt`, which `reportProgress` sets on every report: one
     `select max(last_played_at)` per check, no new in-memory timestamp. The check runs before each file and before
     every ffmpeg call (the reader takes a checkpoint), so a film that starts mid-file stops the run within one seek and
     the file is simply analysed again later.
   - "Continue watching first" is approximated per edition as a playback position past 0 and not removed from Continue
     watching (the real rule looks at the film's last played edition and the minimum watch time); it only orders the
     backfill. Due files are found with three queries however many films there are.
   - An hourly `CreditsScheduler` nudge (like `SidecarTimingScheduler`) re-starts a run that stopped for playback or a
     scan, so the backfill does not wait for the next scan. `LibraryLock.CREDITS` is the new holder; a scan refused
     because of it says "Credits are being saved".
   - Config: `cinefilm.credits.enabled` (default true; false in the test profile), documented in
     `application.properties`. `cinefilm.tools.ffmpeg`, `cinefilm.transcode.nice` and `cinefilm.cache.dir` are reused.
   - The real-tools test (`CreditsRealToolsTest`, CI only) does not use `drawtext` (it needs libfreetype and a font): it
     writes a crawl as PGM frames, encodes them with libx264 at 2 frames a second after 6 minutes of plain colour, and
     asserts `FOUND` within a few seconds of 6:00. It could not be run on the dev Mac (no ffmpeg), so CI is its first run.
2. **Measure on the server** before anything is applied. About 30 films checked by still: both LOTR extended
   editions, a Bond, a Marvel film with mid- and post-credits scenes, a Pixar film, a letterboxed night ending, a
   starfield ending, an HDR remux, a black-and-white film, a film with credits over picture. Also the time a film
   takes. Thresholds are tuned in this plan from what is found.
3. **Apply, source column, Admin.** `credits_marker_source` and its carrying, applying `FOUND`, the watched-rule
   change if the owner agrees, the Admin list and overrides.

## Open owner decisions

1. **Stopgap — decided (owner, 2026-11-12): 97%.** With no marker the overlay appears at 97% of the runtime; watched
   stays at 92% (backend `WatchedRule`, decision 1). Shipped as its own small PR before phase 1.
2. **Watched rule (open):** `min(marker, 92%)` as above, or keep "watched at the marker".
