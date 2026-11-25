# Subtitles that follow the film's frame rate — plan

Status: plan, 2026-11-25, written at the owner's request ("Fix playback first"), revised the same day after an
adversarial review. Revised again the same day: no rate choice for the owner ("The owner's rate" below).
Found during 3e (`soundtrack-confirm-plan.md`), which waits until this is released.

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
  disagree keeps its references raw (below).
- **A new download is copied from a reference's original cues** (`original_cues_text` when set, else `cues_text`),
  with its `timed_fps`, and then gets its own k. A download is never scaled twice.
- **k is worked out again on every run, from the original.** When it differs from `retimed_by` (the film file was
  replaced by one at another rate, or an edition was edited), the file is
  rewritten from its original, or restored when the new k is 1.
- `ReferenceFetcher.store` resets `original_cues_text` and `retimed_by`, and sets `timed_fps`, on every write. So a
  re-fetched reference is never marked as already retimed. The job skips only references with no cues
  (`!isUsable()`). Whether a reference fits an edition's runtime is judged afterwards, on its rewritten last cue end,
  because a drifting reference ends about 4% past the runtime until it is retimed.
- In the same write:
  - the file's stored last end (`lastEndMs` / `lastCueEndMs`) is recomputed;
  - an AUTO offset is reset to 0, because it was fitted on the drifting cues;
  - `updatedAt` is set.
- Every reader then sees film-timeline cues with no change of its own: what plays, the served version, the URL
  version (`ForcedSubtitleService.version`), the embedded fit, the comparisons, the timing references, the full-file
  probe and the Admin.
- The cue hash in `TimingKeys.identity` and the served version change, so each rewritten file's fits and checks are
  stale. That is exactly the set that needs them made again.

**The job.** `SubtitleRetimeJob`, run by `cinefilm.subtitles.retime`: `off` (the default until the last phase; the job
writes nothing), `on`, or `restore` (the rollback, below).
- **Which files.** Each run, it visits:
  - every download and every stored reference with a known stated rate;
  - every row with `retimed_by` set.

  For each, it works k out again. When k differs from `retimed_by`, the row is rewritten from its original, or
  restored when a known rate gives k = 1. When the film's rate is unknown for now (a replaced file not yet indexed,
  say), a retimed row is left as it is. It skips downloads with `retime = OFF` (restoring them if they were retimed), and applies the
  owner-timing rule below.
- **Under the lock.** Each rewrite, restore and owner-rule decision is made under the `LibraryLock`, the same lock
  the Admin's edits take. The row is read again under the lock, and `retime`, the offset source
  and the verification are checked again. A `BUSY` is retried, so an owner's edit made at the same moment is never
  overwritten.
- **The checks.**
  - **A rewritten file's failing rows are carried over.** A `CHECK` or `FAILED` check, or an alignment that is not
    `ALIGNED`, is re-keyed to the new served version and identity, with `stale_since` set. So a download that failed
    for wrong lines, not drift, stays failed until its new verdict replaces the row.
  - **Its proving rows are not.** An `AGREES` check or an `ALIGNED` alignment was made on the old cues and proves
    nothing about the new ones. It goes non-current, and the file is unchecked until its re-check.
  - **Every due test treats a row with `stale_since` set as due, whatever its trust:** the timing fit, and the file
    and download checks of `SidecarCheckService`. The timing references and the full-file probe keep no rows; their
    in-memory "done" sets lose each rewritten or restored sidecar id, so those are measured again. Both only log.
  - **Rows that name a changed reference are not deleted.** Whenever a reference's `cues_text` changes, by a rewrite,
    a re-scale or a restore, every `ForcedSubtitleCheck`, `ForcedDownloadCheck` and `ForcedSubtitleAlignment` that
    names it, in every edition of the film, gets `stale_since` set (a new column on the three tables). Those keys name
    the reference, not its cues.
  - A stale row still decides what plays. What is due treats it as due. The new verdict replaces it in one write.
    - **No verdict for a passing reason** (the budget, an outage, the candidate cap): the old row stays, and stays
      due. So an owner's file that failed is never put back on screen by a deletion.
    - **Only definite skips** (unknown rate, a rate conflict): every stale row is kept, a carried-over failure
      included, until a real verdict replaces it. A wrong subtitle is never put back on screen by a skip. The cost:
      a download that failed only because of drift, and is judged only by references it can't be compared with,
      stays failed until a reference of known rate judges it, or the owner picks another. Phase 1
      counts these cases.
  - **A save never lands on cues that have changed.** `opensubtitles_reference` gets a `cues_version`, raised on
    every change of its `cues_text`. Every check and fit carries the version it read and drops its save if it has
    changed, as it already does for the served version. A run racing the job therefore can't save a verdict made on
    the old cues.
  - **The re-check runs at once.** For each touched edition in turn, the job runs the timing fit first, then the
    checks, since a download's check waits for its fit. Stored references come first. A save that comes back `BUSY`
    is retried inside the job.
- **When it runs.** At startup, nightly, and after an index pass. So a film whose rate arrives later, or a new
  download, is retimed then. A new download or reference is also retimed as it is stored, when the switch is on.
- **The film's rate.**
  - It comes from `media_keyframes.fps`, used only when the row's `source_size` matches the media file's
    `size_bytes`.
  - A whole-edition file on a split edition takes a rate only when every part has a current row with the same rate.
  - Anything else gives k = 1, and the file is not retimed.

**Undo.**
- A file is restored by writing `original_cues_text` back and clearing `retimed_by`, and its checks are made again
  the same way.
- A download set to `retime = OFF` is restored at once.
- **Any rewrite or restore the job makes** (a new k, a restore, `restore`) clears an owner verification and resets
  an OWNER offset that were set after the file's last rewrite, by the same rule as turning retime on. They were set
  on cues that are no longer there. When retime is turned off, the owner's own choice, the owner re-verifies.
- `off` restores nothing: the rewritten files stay right.
- **The rollback is `restore`.** It restores every retimed download and reference by the same rule, and marks their
  rows as above. It is tested in phase 2a (downloads) and 2b (references). An image rollback alone would leave the
  rewritten cues in place, so `restore` is run first; `operations.md` says so.

**How two files are compared** (a download or an owner's file against a reference). One rule, applied whatever
the switch says, because rewritten rows outlive it:
- **A row's rate** is the stated one, else unknown. The owner's own files (`FILE`) and
  downloads that fit as is (`OFF_FITS`) take the film's rate as their known rate in every rule below. So does the
  film's embedded English track (`EMBEDDED`), which is on the film's timeline by nature.
- **Neither side retimed** (`retimed_by` null on both): they are compared as stored, exactly as today. That covers
  every pair on a 23.976 film with nothing retimed. It also covers an owner's file against a reference with no rate,
  and an owner-offset download that is off against a raw reference.
- **One or both retimed, and both rates known:**
  - each retimed side is compared through its film-timeline cues;
  - a raw side whose rate differs from the film's is compared through the other side's `original_cues_text`, when
    both have the same rate;
  - otherwise the two are compared on the film's timeline.
- **One side retimed, the other's rate unknown:** no verdict. The reference is skipped, the case is counted, and
  it never gives `CHECK`.
- **A rate conflict:** a retimed side and a raw side whose stated rates differ, which give `CHECK` on the film's
  timeline but agree when both are compared as originally stored. One stated rate is wrong, and the plan can't tell
  which. That is no verdict, counted and shown in the Admin as "rate conflict". The Bridge of Spies risk is a
  reference stated as 25 that is timed like 24, and this is where it lands. This pair is the only protection against a wrong stated rate.
  In every other compared pair a wrong rate can still fail a file, or make one fail: raw against raw (compared as
  stored, as today), and a pair whose two sides state the same rate. Where both sides are retimed at different
  stated rates, a 23.976 file mislabelled 24.0 (or the reverse) can newly drift by about 7 s over two hours.
- **After a skip,** the walk moves on to the next candidate with a known rate, stored ones first. If none is left,
  the edition has no verdict this run and is counted.
- **Tests:**
  - an owner file against a reference with no rate, on a 23.976 film, still gets a verdict;
  - an owner-offset download that is off, against a retimed reference, is compared raw against raw;
  - a reference of unknown rate gives no verdict against a retimed download;
  - a raw 23.976 reference against a retimed 23.976 download on a 25 fps film is compared on the originals and
    agrees;
  - a retimed download against the embedded English track gets a verdict;
  - a rate conflict gives no verdict, never `CHECK`;
  - a split-rate film with a retimed download and a raw reference of the same stated rate is compared on the
    originals.

**What the owner set by hand is left alone.**
- `retime` starts **undecided** (null). The job decides it once:
  - **off** for a download with an OWNER offset or owner verification, which then plays as it does today;
  - **fits as is** (`OFF_FITS`) for one whose original cues fit the film's English track `ALIGNED`. That fit proves
    they are already on this copy's timeline, so its stated rate is wrong (the reverse of Bridge of Spies). Such a
    file counts as on the film's timeline in every comparison, like an owner's file. On a film with an English
    embedded track, the decision waits until a fit of the original cues has been tried, at arrival too. Only 3
    forced files have one today;
  - **on** otherwise, including a download that arrives while the switch is on.
- Once decided, the job never decides it again. A later verify or offset nudge on a retimed file leaves it retimed.
- The Admin says when the file may drift because retime is off. The owner can turn it on. That retimes the file,
  resets the offset and clears the verification, since the rewritten cues are not what the owner verified.
  Tests cover "verify after retime" and "turn on, then the next run".
- A file the owner chose in the picker with neither is retimed, if its rate is known (phase 1).

**The owner's rate.** The owner sets no rate (owner, 2026-11-25: a subtitle file carries no frame rate, only
timestamps, so there is nothing for the owner to read it from).
- The stated rate decides. The Admin says what was done, for example "Timed for 23.976 fps; this film is 25 fps, so
  it is sped up 4%", or "rate unknown".
- A wrong stated rate (Bridge of Spies: stated as 25, timed like 24) is caught only in the pair that "A rate
  conflict" (above) names; elsewhere it can still fail a file. 3e's soundtrack check may later settle such a file's
  rate with no owner input.
- `owner_timed_fps` (added in phase 1) stays unused, kept for that automatic rate. V39's comment on it ("it wins
  over timed_fps") is out of date and stays as it is (Flyway checksum); whatever later sets the column says what it
  means.
- The owner's one per-file control is **don't retime** (`retime = OFF`), for a file that played better before, and
  turning it back on. Both are judged by watching.

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

     It also adds:
     - on `subtitle_sidecar`, `retime` (nullable: undecided, `AUTO`, `OFF` or `OFF_FITS`);
     - on `opensubtitles_reference`, `cues_version` (integer, default 0);
     - on both tables, `owner_timed_fps` (nullable);
     - on `forced_subtitle_check`, `forced_download_check` and `forced_subtitle_alignment`, `stale_since` (nullable).
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
     - comparisons of unknown against retimed;
     - retime candidates with a current `EMBEDDED` `ALIGNED` fit, which will be set off;
     - downloads that fail today and whose only references have an unknown rate (they stay failed after retime);
     - references stated as 25 that act as judges, on 25 fps films and on 23.976 or 24 fps films (where 2b would
       slow them down). These are the Bridge of Spies risk; "A rate conflict" (above) says which of their comparisons
       end as rate conflicts and which can still fail a file. If more than 10 in all, the owner is asked before 2a:
       go ahead, or wait for 3e to settle their rate.

     **If fewer than 80 of the 92 are backfilled, the owner is asked before phase 2.** The choice is a one-off
     file-info lookup on OpenSubtitles, or leaving them listed.
   - If phase 1 is estimated over 1,200 lines, it is split: 1a is V39, `FrameRateRetime`, the cue scaler and storing
     the rate on new rows; 1b is the V40 backfill and its integration test.
2. **2a: the job for downloads, behind the switch.**
   - `stale_since` is honoured by every due test (above), with a test for each path.
   - `SubtitleRetimeJob`, for downloads only:
     - the rewrite, with k worked out again on every run;
     - the AUTO reset and restore;
     - the owner-timing rule;
     - the immediate fit, then the checks, with the `BUSY` retry.
   - Comparisons by the rule in "How two files are compared".
   - Tests:
     - with the switch off, nothing is written;
     - a retimed file's served cues, fits and checks follow the rewrite;
     - a failed download is checked again within the job's run;
     - a film file replaced at another rate is rewritten from the original.
     - `restore` puts every retimed download back.
     - verify after retime, then `restore`: the file is no longer proven by the verification;
   - `operations.md` gains the rollback order: set `cinefilm.subtitles.retime` to `restore`, wait for the run to end,
     then roll back the image.
3. **2b: references and arrivals, behind the switch.**
   - References are rewritten under the agreed-rate rule.
   - The `cues_version` guard: it is raised on every change of a reference's `cues_text`, and a check or fit drops
     its save when the version it read has moved. Test: a check that read a reference before its rewrite drops its
     save.
   - Rows that name a changed reference get `stale_since`, in every edition of the film. They keep deciding until
     replaced, and are due.
   - `restore` covers references too.
   - `ReferenceFetcher.store` resets the retime columns.
   - Downloads are copied from the original cues.
   - A file is retimed as it arrives.
   - Tests:
     - a download copied from a retimed reference is scaled once;
     - a film whose editions disagree keeps its references raw;
     - another edition's check against a rewritten or restored reference is made again;
     - a retimed download and a reference of unknown rate give no verdict, never `CHECK`;
     - an owner edit made while the job holds a row is kept.
   - If either 2a or 2b is estimated over 1,200 lines, it is split again along these bullets.
4. **The Admin, then on.**
   - `PUT /api/admin/subtitles/{id}/retime` takes "retime" or "don't retime" (no rate: "The owner's rate").
   - The rate line and the switch go in the Admin's subtitle view.
   - The switch's default becomes on.
   - **Released on its own.** 2a and 2b are tagged and deployed before phase 4 is tagged. Any rollback of phase 4's
     release, including the automatic one when smoke fails, then lands on an image that understands retimed rows.
     The `operations.md` note says so.

Phase 4 may be built beside 2a and 2b (different files), but is merged after them. If it is estimated over 1,200
lines, it is split: the endpoint and contract first, then the Admin view and the switch's default.

## Checks after release

- The owner plays Jack Ryan: Shadow Recruit at about 1:28:57. The Russian line's subtitle should be on screen.
- When the job's first run has finished, a read-only query counts the following. The job's run checks stored
  references first, and may fetch within the checks' share.
  - the downloads that now play retimed, and those set to off;
  - how long the first run took, and how many rows are still stale at its end;
  - the editions left with no verdict;
  - the proven count of each kind, against the same count taken before release. Expect no drop beyond the skipped
    no-verdict cases (unknown rate and rate conflicts), which phase 1 counted.

## Decisions taken in this plan

1. **The stated rate decides.** It held on 5 of the 6 files with signal. A wrong one is caught only as "A rate conflict"
   describes. The owner sets no rate (2026-11-25).
2. **Only across the PAL speed-up.** 30 fps copies run at film speed, and odd rates are left alone.
3. **23.976 and 24.0 are kept apart** (k = 23.976/25 against 24/25). They differ by 0.1%, about 7 s over two hours.
   The stated value is used as given.
4. **No re-ranking, and no replacing downloads for their rate.** A retime fixes them without spending downloads or
   re-picking.
5. **Hand-set timing is kept.** Owner offsets and verified files are not retimed unless the owner turns retime on.
6. **The cues are rewritten, not retimed when served.** Every reader then sees one timeline, and the existing cue
   hashes mark exactly what must be checked again. The original is kept for undo.
7. **No Plex input or answer.** The film's own rate and OpenSubtitles' stated rate only.
