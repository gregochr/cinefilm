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
   **Measured 2026-11-13 on v0.6.1** (below, "Phase 2: what the server showed"). The detector as built finds almost
   nothing; the fixes are phase 2b.
2b. **Detector fixes, still report only.** The frame-classifier changes and the motion changes measured below, and
   re-analysis of every stored row: an `analysis_version` column on `credits_detection` (expand-only, default 1); a
   row whose version is older than the code's `CreditsTuning.ANALYSIS_VERSION` is due again, like a changed
   fingerprint. Unit tests use synthetic frames for each measured failure (anti-aliased white text that reaches only
   0.6–0.8 of the text-like share, thin grey text far below the window's peak, dense columns that leave only 75% of
   the frame dark, burned-in subtitles in the bottom band of a dark scene, a sub-pixel crawl, cards fading through
   black, credits starting 3 minutes after an end-title sequence over picture); no frames from real films go into the
   repository. After its release, and **before phase 3 applies anything**, the stored rows are compared with the
   imported Plex markers (phase 2c) across the library: a `FOUND` more than 15 s before Plex's start is a defect to fix
   first.
2c. **One-off Plex marker import, stored only** (owner, 2026-11-13: "Yes to the one time import, but … it's not a
   durable solution"). Plex is being decommissioned, so this runs once (an Admin action, like Import from Plex, which
   reads the same server and token) and keeps what it found after Plex is gone. Stored per media file (Plex lists
   each file of a split edition as a part; its offsets are edition-global, so a part's offset is the marker minus the
   earlier parts' durations). The first credits marker at least 60% into the item is the start (Plex marks a credits
   run followed by a mid-credits scene as a non-final marker before the final one). Nothing is applied.
3. **Apply, source column, Admin.** `credits_marker_source` and its carrying, applying, the watched-rule change if the
   owner agrees, the Admin list and overrides. Precedence: `OWNER`, then `PLEX`, then the detector's `FOUND` plus a
   20 s margin (`AUTO`), then the 97% fallback. **A late overlay is better than an early one** (owner, 2026-11-13):
   the margin applies to detected markers only.

## Phase 2: what the server showed (2026-11-13)

v0.6.1 analysed its first five files: four `NOT_FOUND` ("black or text on a dark screen lasts only 5 s") and one
`UNSURE`, on films that all end with ordinary credits. The pure classes were then run in a scratch harness on frames
pulled from the server in the detector's own format (480 px wide, grey), for 30 films, and compared with **Plex's own
credits markers**: Plex Pass is active, and Plex has a credits marker for 864 of the 1,087 films (none for either LOTR
extended edition). Plex's markers are the ground truth from here on.

**Why credit frames were `PICTURE`:**

- `TEXT_LIKE_SHARE` 0.8 sits at the top of what real credits score. White text on black at 480 px scores 0.63–0.80
  (anti-aliased letter edges often sit just above the sharp-edge limit); film frames score at most 0.04.
- Small or thin lettering never reaches the bright limit (65% of the window's peak). *Wicked*'s serif credits top out
  at about 148 grey with a limit of 143, so the frame has almost no bright pixels.
- Dense multi-column credits leave only 75% of the frame dark (*Fellowship* extended), under `TEXT_DARK_SHARE` 0.80.
- Measuring at 960 px instead was tried and is not a fix: it helped *Dune Part Two* and made *Endgame* and
  *Fellowship* worse.

**The classifier change (measured):**

- A frame whose dark share reaches `TEXT_DARK_SHARE` measures its bright and sharp limits against its own 99.9th
  percentile luma, clamped to between half the window's peak and the peak (dark share is still measured against the
  window's peak).
- `TEXT_DARK_SHARE` 0.70, `TEXT_LIKE_SHARE` 0.30.
- **Subtitles are not text on dark:** a frame whose text-row pixels lie 80% or more in the bottom 30% of the picture
  area is not text-like. *Psycho*'s dark scenes with burned-in subtitles scored up to 0.67 before this rule and 0
  after.

With it, the coarse step finds a candidate in 23 of the 30 films, and the 7 rejections are endings the picture cannot
read: *Return of the King* extended (dark text on a grey parchment texture), *Jaws* (credits over the sea),
*Goldfinger* and *A Close Shave* (almost no credits), *Psycho*, and *The Batman* (grey text on a lifted, grainy
black). Plex has markers for four of those, which the import covers.

**Against Plex** (the coarse candidate, then the real `refine` on a dense decode around it):

| Result | Films | Offset from Plex's first marker |
|---|---|---|
| `FOUND` | *Gravity*, *Oppenheimer*, *2001*, *Blade Runner*, *Mamma Mia*, *Skyfall*, *Interstellar*, *Love Actually*, *Guardians* | 0 to +23 s |
| `FOUND`, late | *Schindler's List*, *WALL·E*, *The Proposal* | +77 to +193 s |
| `UNSURE` "never moves", right time | *Wicked*, *Gladiator II*, *Ratatouille*, *Dune Part Two*, *Notting Hill*, *Inside Out* | −5 to +2 s |
| `UNSURE`, other | *The Dark Knight*, *Contagion*, *Endgame*, *Civil War* | −89 to +122 s |

No `FOUND` was early. The coarse step places the start well; the motion check is what refuses ordinary crawls (see
"The motion check" below, phase 2b). *Contagion*'s candidate is 89 s early because the black before the crawl
counted as credits-like, and its dense window (centred on the candidate) only reaches the crawl at its end.

### The motion check

The `UNSURE` "never moves" results have three causes, found by instrumenting `CreditsMotion` on the dense frames:

1. **Crawls missed** (*Civil War*, *Gladiator II*, *Notting Hill*: plain crawls of 11–16 px a frame). Each pixel row
   is compared by its count of bright pixels; small anti-aliased text moving by a fraction of a pixel makes the counts
   noisy, so the best match errs 0.16–0.39 against `CRAWL_MATCH_MAX_ERROR` 0.15.
2. **Cards fading through black** (*The Dark Knight*: one credit per card). Text is only a third of the frames
   (`MOTION_MIN_TEXT_SHARE` 0.5), and a card change alters 0.0015–0.0048 of the pixels (`CARD_CHANGE_MIN_SHARE`
   0.005; the noise within one card is about 0.0001).
3. **The dense window misses the credits** (*Contagion*, *Endgame*, *Dune Part Two*, *Ratatouille*, *Inside Out*,
   *Wicked*). The candidate is the start of an end-title sequence over picture (animation, panels, light rays, a cast
   montage); the credits on a dark screen begin 86–200 s later, past the window's end at candidate + 90 s. The 90 s
   before the candidate were never used (`refine` starts 5 s before the candidate).

**Changes (measured on all 23 candidate films):**

- `CreditsMotion` row profiles are smoothed over three rows (each count plus its neighbour above and below) before
  matching.
- `CRAWL_MATCH_MAX_ERROR` 0.25 (the match must still be under half the error of "not moving", so still text cannot
  pass as a crawl); `MOTION_MIN_TEXT_SHARE` 0.3 (black frames already count towards the 0.8 non-picture share);
  `CARD_CHANGE_MIN_SHARE` 0.001.
- The dense pass decodes **from 10 s before the candidate to 290 s after** (600 frames at 2 a second, instead of
  3 minutes centred on it). `DENSE_TIMEOUT` grows in proportion.

Result: **every one of the 23 candidate films is `FOUND`, none early.** The earliest is *Gladiator II* at 2 s before
Plex's marker; *Contagion* 1 s before; the rest 0 to +254 s late (*Endgame* +2 s against Plex's final marker, +254 s
against its first, which starts the cast montage over picture). Each change was needed: smoothing alone fixes only
*Notting Hill*, and an error limit of 0.20 alone misses *Civil War*. 0.0015 for card changes gives the same results
but sits exactly at *The Dark Knight*'s smallest change, so 0.001 leaves margin.

**Remaining risk, accepted and measured:** a story that ends in moving text on black (an epilogue crawl) before the
credits would be marked at the epilogue. Phase 2b's run against the 864 Plex markers counts these; the 20 s margin
and Plex's precedence limit the damage.

**Time per film:** 127–221 s on the server for the five files analysed by v0.6.1.

## Open owner decisions

1. **Stopgap — decided (owner, 2026-11-12): 97%.** With no marker the overlay appears at 97% of the runtime; watched
   stays at 92% (backend `WatchedRule`, decision 1). Shipped as its own small PR before phase 1.
2. **Watched rule (open):** `min(marker, 92%)` as above, or keep "watched at the marker".
