# Subtitles that follow the film's frame rate — plan

Status: plan, 2026-11-25, written at the owner's request ("Fix playback first"), revised the same day after an
adversarial review. Found during 3e (`soundtrack-confirm-plan.md`), which waits until this is released.

## Why

Most of the library is 25 fps, but the subtitle code assumes every film is 23.976 fps.

**The library** (owner's query, 2026-11-25, `media_keyframes.fps` of every `ORIGINAL` media file):

| Frame rate | All files | With a forced file |
|---|---|---|
| 25.000 | 659 | 96 |
| 23.976 | 420 | 102 |
| 24.000 | 10 | 0 |
| 30.000 | 7 | 1 |
| others or none | 12 | 1 |

A 25 fps file is a European (PAL) copy: the 24 fps film played 4% fast, so it is about 4% shorter. A subtitle timed
for a 23.976 or 24 fps release drifts on it, at about 2.4 s per minute: in sync at the start, about 4 minutes late
after 100 minutes. The reverse holds for a 25 fps subtitle on a 23.976 film. A 30 fps file is a telecined copy that
runs at the film's own speed, so it needs no change.

**The served subtitles** (owner's query, 2026-11-25, every present sidecar by the film's rate and the rate
OpenSubtitles states for the file):

- **Forced, the owner's own files:** 28, all on 23.976 films. None drift.
- **Forced, downloaded by CineFilm, on 25 fps films:** 84 stated as about 24 fps (77 at 23.976, 2 at 23.98, 5 at
  24.0), 4 stated as 25, and 10 with no stated rate.
- **Forced, downloaded, on 23.976 films:** 71 stated as about 24 fps, 1 stated as 25, and 5 with no stated rate.
- **Full, downloaded:** 8 at 23.976 on 25 fps films, and 4 at 23.976 on 23.976 films.

So about 92 downloaded files drift today, chosen by CineFilm itself: `OpenSubtitlesRanking` ranks candidates by
closeness to 23.976 (`TARGET_FPS`), which is the wrong rate for a 25 fps film.

**The stated rate mostly holds.** The planner checked it against the soundtrack on 3e's design films: half A, the
`tiny` speech maps, score B. That uses no Plex data and no held-out data. For each forced file on a 25 fps film with 8
or more cues, the best z-score near the offset the file plays with (within ±2 s) was measured at ratio 1 and at
ratio stated/25:

| Film | Stated | As played | Retimed |
|---|---|---|---|
| 1917 | 23.976 | 1.5 | 3.4 |
| Comic Book Origins: Captain America | 23.976 | 1.4 | 4.5 |
| Hitman | 23.976 | 0.1 | 3.1 |
| Inferno | 24.0 | 0.0 | 4.1 |
| Jack Ryan: Shadow Recruit | 23.976 | −0.3 | 3.2 |
| Bridge of Spies | 25.0 | −0.4 (no change) | 3.5 at 24/25 |

- On the five files stated as about 24 fps, the retimed file sits on the foreign speech and the file as played does
  not. Hitman as played reads 0% foreign and 97% English under its cues; retimed, it reads 67% and 6%.
- Bridge of Spies is stated as 25, but behaves like a 24 fps file. A stated rate can be wrong.
- Four more (A Brilliant Young Mind, A Most Wanted Man, Batman v Superman, Severance) show too little signal either
  way. Rocky IV is a 30 fps copy and fits best as it is.

The owner can hear it: Jack Ryan: Shadow Recruit's "Mr. Cherevin, we need to act now." is shown at 1:32:45, but the
Russian it translates is spoken at about 1:28:57.

## What changes

**The retime.** A downloaded subtitle is put on the film's timeline: every cue time t becomes k·t, scaled from 0,
and then the part's start and the offset are added as today. k is the subtitle's stated rate over the film's rate, and
it applies only across the PAL speed-up:
- **25 fps film** (24.85–25.15, which takes in the 24.887 and 24.98–25.001 files) and a subtitle stated as about 24:
  k = 23.976/25 when it is stated as 23.976 or 23.98, and 24/25 when it is stated as 24.0;
- **about 24 fps film** (23.9–24.1) and a subtitle stated as 25: k = 25/23.976, or 25/24 for a 24.0 film;
- **anything else**, k = 1: an unknown rate on either side, a 30 fps copy (telecined, so it runs at film speed), a
  matching rate, or retime switched off.

The owner's own files (origin `FILE`) are taken as on the film's timeline: k = 1, and never retimed. Embedded
tracks are already on it.

**The stored cues are rewritten, once.** Retiming at serve time would need every reader of cue times, every cache
key and every stored verdict to know about k, and a careful hand-over while the checks are redone. Instead, the
cues themselves are put on the film's timeline once, and every reader sees them as they are now.
- A retimed file's `cues_text` is rewritten with every time scaled by k. The original goes to a new column,
  `original_cues_text`, and k to `retimed_by`, with the film rate k came from in `retimed_for_fps`.
- **A stored reference** (`opensubtitles_reference`) is rewritten the same way, but only when every original part of
  every edition of its film has one agreed rate. References are kept per film, not per edition. A film whose editions
  disagree keeps its references raw, and those count as unknown-rate (below).
- **A new download is copied from a reference's original cues** (`original_cues_text` when set, else `cues_text`),
  with its `timed_fps`, and then gets its own k. A download is never scaled twice.
- **k is worked out again on every run, from the original.** When it differs from `retimed_by` (the film file was
  replaced by one at another rate, an edition was edited, or the owner changed the file's rate), the file is
  rewritten from its original, or restored when the new k is 1.
- `ReferenceFetcher.store` resets `original_cues_text` and `retimed_by`, and sets `timed_fps`, on every write. So a
  re-fetched reference is never marked as already retimed. The job skips references that can't be used.
- In the same write:
  - the file's stored last end (`lastEndMs` / `lastCueEndMs`) is recomputed;
  - an AUTO offset is reset to 0, because it was fitted on the drifting cues;
  - `updatedAt` is set.
- Every reader then sees film-timeline cues with no change of its own: what plays, the served version, the URL
  version (`ForcedSubtitleService.version`), the embedded fit, the comparisons, the timing references, the full-file
  probe and the Admin.
- The cue hash in `TimingKeys.identity` and the served version change, so each rewritten file's fits and checks are
  stale. That is exactly the set that needs them made again.

**The job.** `SubtitleRetimeJob`, behind `cinefilm.subtitles.retime-enabled` (default off until the last phase):
- **Which files.** Each run, it visits:
  - every download and every stored reference with a known rate (stated, or the owner's);
  - every row with `retimed_by` set.

  For each, it works k out again. When k differs from `retimed_by`, the row is rewritten from its original, or
  restored when k is 1. It skips downloads with `retime = OFF` (restoring them if they were retimed), and applies the
  owner-timing rule below.
- **Under the lock.** Each rewrite, restore and owner-rule decision is made under the `LibraryLock`, the same lock
  the Admin's edits take. The row is read again under the lock, and `retime`, `owner_timed_fps`, the offset source
  and the verification are checked again. A `BUSY` is retried, so an owner's edit made at the same moment is never
  overwritten.
- **The checks.**
  - It deletes the check and alignment rows of the rewritten files and of each touched edition's served pick.
  - Whenever a reference's `cues_text` changes, by a rewrite, a re-scale or a restore, it also deletes every
    `ForcedSubtitleCheck`, `ForcedDownloadCheck` and `ForcedSubtitleAlignment` that names it, in every edition of the
    film. Those keys name the reference, not its cues.
  - Then, for each of those editions in turn, it runs the timing fit first and the checks after it, since a
    download's check waits for its fit. Stored references come first. A save that comes back `BUSY` (the library
    lock is held) is retried inside the job, so an edition is not left for the nightly pass.
  The time an edition spends unchecked is the length of that one run, not a nightly pass. During that run a download
  that failed could play, unchecked. That is accepted and measured (Checks after release).
- **When it runs.** At startup, nightly, and after an index pass. So a film whose rate arrives later, or a new
  download, is retimed then. A new download or reference is also retimed as it is stored, when the switch is on.
- **The film's rate.**
  - It comes from `media_keyframes.fps`, used only when the row's `source_size` matches the media file's
    `size_bytes`.
  - A whole-edition file on a split edition takes a rate only when every part has a current row with the same rate.
  - Anything else gives k = 1, and the file is not retimed.

**Undo.** A file is restored by writing `original_cues_text` back and clearing `retimed_by`. The cue hash changes, so
its checks are made again the same way. A file set to `retime = OFF` is restored at once. Turning the switch off
restores nothing by itself: the rewritten files stay right. A rollback, if ever needed, restores every retimed file
by the same rule.

**Unknown against retimed: no verdict.**
- **The case.**
  - A downloaded file is on its edition's timeline when its k for that edition is 1, or when `retimed_by` equals it.
  - A reference is on an edition's timeline on the same terms. That is, its k for that edition's rate is 1, or
    `retimed_by` equals that k.
  - Anything else is unknown for that comparison: no stated rate, a film whose editions disagree, or an edition
    whose rate is unknown. Compared with a retimed side, it can't be judged.
- **What happens.** That reference is skipped, the case is counted, and it never gives `CHECK`. The walk moves on to
  the next candidate with a stated rate, stored ones first. If none is left, the edition has no verdict this run
  (unchecked) and is counted.
- **The scope.** The rule applies whenever either side has `retimed_by` set, whatever the switch says, because the
  rewritten rows outlive it. The owner's files count as on the film's timeline, so they are compared as usual. Tests
  pin both.

**What the owner set by hand is left alone.**
- A download with an OWNER offset or owner verification when the job first reaches it is set to **retime off**, and
  plays as it does today.
- The Admin says the file may drift and that retime is off because the owner set its timing. The owner can turn it
  on, which retimes it and resets the offset.
- A file the owner chose in the picker with neither is retimed, if its rate is known (phase 1).

**The owner's choice per file.** Each downloaded file has **Timed for:**
- **as stated**, the default: the rate OpenSubtitles gives, or none;
- **23.976**, **24** or **25**: the owner's choice wins over the stated rate. This fixes Bridge of Spies, which is
  stated as 25 but timed like a 24 fps file;
- **don't retime**: the same as `retime = OFF`.

It is stored as `subtitle_sidecar.owner_timed_fps` beside `retime`. The Admin says what was done, for example
"Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%", or "rate unknown".

**Ranking is not changed.** Once downloads are retimed, a file stated as about 24 plays right on a 25 fps film. On
the design films that held 5 of 5 times, against 0 of 1 for a file stated as 25. Re-ranking would also re-pick each
film's best file and make every check stale a second time. `OpenSubtitlesRanking` and `TimingReferenceRule`'s plausible
rates stay as they are.

Not in this plan:
- the 10 forced downloads on 25 fps films with no stated rate. They stay as they are, and the Admin says the rate is
  unknown;
- finding a rate from the soundtrack (3e) or from an embedded track (3 forced files have one).

## Phases (each PR at most 1,500 changed lines, tests included; merged in this order)

1. **The rate data.** Nothing that plays changes.
   - Migration `V39` adds, on both `subtitle_sidecar` and `opensubtitles_reference`:
     - `timed_fps`, `retimed_by` and `retimed_for_fps` (nullable `DOUBLE PRECISION`);
     - `original_cues_text` (nullable `TEXT`).

     It also adds, on `subtitle_sidecar`, `retime` (`AUTO` or `OFF`, default `AUTO`) and `owner_timed_fps`
     (nullable).
   - New downloads, picker downloads (`Offered.result().fps()`) and newly fetched references store their stated
     rate.
   - The Java migration `V40__BackfillSubtitleFrameRates` (as `V27__BackfillPlaybackHealthFigures` does) fills
     `timed_fps` on stored rows by file id, from `film.os_candidates`, `os_full_candidates` and `os_best`. A row it
     can't match stays null and is never retimed. Picker downloads are the likeliest misses, because the picker's
     results were kept in memory only.
   - The pure `FrameRateRetime` gives k, with unit tests at each band edge. The pure cue scaler is tested on its
     rounding, its order and its end times.
   - Tests: the migration on H2, and a `*IntegrationTest` for V39 and V40 on PostgreSQL (CI).
   - After release, a read-only query on the server counts:
     - rows backfilled, out of the expected 92;
     - downloads the job would set to off;
     - comparisons of unknown against retimed.

     **If fewer than 80 of the 92 are backfilled, the owner is asked before phase 2.** The choice is a one-off
     file-info lookup on OpenSubtitles, or leaving them listed.
2. **2a: the job for downloads, behind the switch.**
   - `SubtitleRetimeJob`, for downloads only:
     - the rewrite, with k worked out again on every run;
     - the AUTO reset and restore;
     - the owner-timing rule;
     - the immediate fit, then the checks, with the `BUSY` retry.
   - Unknown against retimed: no verdict, with each side judged on the edition's timeline as defined above.
   - Tests:
     - with the switch off, nothing is written;
     - a retimed file's served cues, fits and checks follow the rewrite;
     - a failed download is checked again within the job's run;
     - a film file replaced at another rate is rewritten from the original.
3. **2b: references and arrivals, behind the switch.**
   - References are rewritten under the agreed-rate rule.
   - Rows that used a rewritten reference are deleted in every edition of the film.
   - `ReferenceFetcher.store` resets the retime columns.
   - Downloads are copied from the original cues.
   - A file is retimed as it arrives.
   - Tests:
     - a download copied from a retimed reference is scaled once;
     - a film whose editions disagree keeps its references raw;
     - another edition's check against a rewritten or restored reference is made again;
     - a film that disagrees or is partly unknown, a retimed download and a raw stated reference give no verdict,
       never `CHECK`;
     - an owner edit made while the job holds a row is kept.
   - If either 2a or 2b is estimated over 1,200 lines, it is split again along these bullets.
4. **The Admin, then on.**
   - `PUT /api/admin/subtitles/{id}/retime` takes "as stated", 23.976, 24, 25 or "don't retime".
   - The rate line and the switch go in the Admin's subtitle view.
   - The switch's default becomes on.

Phase 4 may be built beside 2a and 2b (different files), but is merged after them.

## Checks after release

- The owner plays Jack Ryan: Shadow Recruit at about 1:28:57. The Russian line's subtitle should be on screen.
- When the job's first run has finished, a read-only query counts the following. The job's run checks stored
  references first, and may fetch within the checks' share.
  - the downloads that now play retimed, and those set to off;
  - how long the first run took, which is the longest any edition was unchecked;
  - the editions left with no verdict;
  - the proven count of each kind, against the same count taken before release. Expect no drop beyond the skipped
    unknown-against-retimed cases, which phase 1 counted.

## Decisions taken in this plan

1. **The stated rate decides, unless the owner says otherwise.** It held on 5 of the 6 files with signal. The owner's
   choice of rate covers a wrong one, either way.
2. **Only across the PAL speed-up.** 30 fps copies run at film speed, and odd rates are left alone.
3. **23.976 and 24.0 are kept apart** (k = 23.976/25 against 24/25). They differ by 0.1%, about 7 s over two hours.
   The stated value is used as given.
4. **No re-ranking, and no replacing downloads for their rate.** A retime fixes them without spending downloads or
   re-picking.
5. **Hand-set timing is kept.** Owner offsets and verified files are not retimed unless the owner turns retime on.
6. **The cues are rewritten, not retimed when served.** Every reader then sees one timeline, and the existing cue
   hashes mark exactly what must be checked again. The original is kept for undo.
7. **No Plex input or answer.** The film's own rate and OpenSubtitles' stated rate only.
