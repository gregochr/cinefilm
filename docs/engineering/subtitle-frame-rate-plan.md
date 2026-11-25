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

**One helper, every reader.** A single `FilmTimeline` helper turns a stored file into film-timeline cues:
- inputs: the cue text, the stated rate, the film rate and the retime setting;
- it is used for both sidecars and references;
- it also gives the scaled last cue end.

Every path that reads cue times uses it. The build brief lists each one, and a test pins each one:
- **what plays:** `ServedSubtitles.vtt`, `ServedSubtitles.of` and the served version string;
- **the embedded fit:** `SidecarTimingService`'s fit of a sidecar against the embedded English track;
- **the comparisons:** the reference comparisons (`SidecarCheckRefresher`, `DownloadPlan`, `DownloadedFit`). The
  reference is retimed by its own stated rate;
- **timing references:** `TimingReferenceFetch`'s fits, and `TimingReferenceRule.usable` / `lastCueEnd`, which compare
  the scaled last cue end with the runtime;
- **the full-file probe:** `FullFitProbe` / `FullFitService`;
- **the Admin:** the cue list (`SubtitleAdminService.cues`) and its "ends after the film" warning;
- **stored end times:** `SubtitleSidecar.lastEndMs` and `OpenSubtitlesReference.lastCueEndMs` are raw, and they are read
  before the cue text, so they are scaled by k wherever they are read.

**Fits and checks are made again, and nothing flips while they are.**
- **k marks what changed.** k is appended to `TimingKeys.identity` and to the served version **only when k ≠ 1**.
  Every k = 1 identity and version stays byte-for-byte as today, and a test pins that.
- **The rules versions follow the switch.** `SubtitleCheckBase.RULES_VERSION` and `DownloadedFit.RULES_VERSION` go up
  only when the switch is on (the effective version is the base + 1). So there is one re-check pass, at switch-on, and
  none at a merge.
- **Two tests of a stored check or fit row:**
  - **current** is today's exact match: served version or identity, and rules version.
  - **deciding** also accepts a row that differs only by k or by the rules version.
  - Trust (`ForcedResolver`, `ForcedChoice`) uses deciding. What is due (`SidecarCheckService.due`, `addFileCheck`,
    `addDownloadCheck`) and the timing refresher's state use current.
  - So a download that failed stays failed across switch-on and is due. A file proven only by `ALIGNED` stays proven
    until it is fitted again. Neither lets the wrong file play while the pass runs. Tests pin both.
- **An AUTO offset whose alignment row has another k is ignored when read.** The file plays retimed without the
  drift-fitted offset, until the refit writes a new one. A write alone would leave a gap between the deploy and the
  refresher's pass.

**Unknown against retimed: no verdict.**
- **The case.** A downloaded file or a reference with no stated rate (origin `OPENSUBTITLES`, `timed_fps` null) may
  or may not drift. Compared with a side whose k ≠ 1, it can't be judged.
- **What happens.** That reference is skipped and the case is counted. It never gives `CHECK`.
- **Not this case.** The owner's files count as on the film's timeline, so they are compared as usual. An owner file
  on a 23.976 film is compared with a 23.976 reference, and also with a reference stated as 25, which is retimed. Both
  cases are pinned by tests.
- **A skip ends the walk.** It fetches no further candidate. The edition keeps its previous row, which is deciding,
  and a stored skip marker for the current identity stops it being due on every pass. The brief decides the marker's
  form.
- **Otherwise the re-check pass uses stored references first.** It may still fetch new candidates within the checks'
  share of the daily downloads and `MAX_CANDIDATES`, as today.

**The film's rate.**
- It is read from `media_keyframes.fps`, batch-loaded for all the editions a request touches. The fixed query count
  of `ForcedResolver` and the Admin summary holds.
- A row is used only when its `source_size` matches the media file's stored `size_bytes`. A missing or stale row
  means k = 1. When the rate arrives, the version and identity change, so the file is checked again.

**What the owner set by hand is left alone.**
- A download with an OWNER offset or owner verification gets **retime off**, and plays as it does today. The Admin
  says the file may drift and that retime is off because the owner set its timing. The owner can turn it on and set
  the offset again.
- This is set twice: by the backfill, and, while the switch is off, by the offset and verify endpoints whenever they
  touch a download. A nudge made between phase 1 and switch-on is kept too. After switch-on, the owner's offset is
  in film time and retime stays as it is.
- A file the owner chose in the picker with neither is retimed, if its rate is known (phase 1).

**The switch per file.** Each downloaded file has **Retime: automatic or off**. Bridge of Spies shows that a stated
rate can be wrong. The Admin also says what was done, for example "Timed for 23.976 fps; this film is 25 fps, so
it is sped up 4%", or "rate unknown".

**Ranking is not changed.** Once downloads are retimed, a file stated as about 24 plays right on a 25 fps film. On
the design films it held 5 of 5 times, against 0 of 1 for a file stated as 25. Re-ranking would also re-pick each
film's best file and make every check stale a second time. `OpenSubtitlesRanking` and `TimingReferenceRule`'s plausible
rates stay as they are.

**A release switch.** The retime is behind `cinefilm.subtitles.retime-enabled`, default off, until the last phase. No
merge in between changes what plays.

Not in this plan:
- the 10 forced downloads on 25 fps films with no stated rate. They stay as they are, and the Admin says the rate is
  unknown;
- finding a rate from the soundtrack (3e) or from an embedded track (3 forced files have one).

## Phases (each PR at most 1,500 changed lines, tests included; merged in this order)

1. **The rate data.**
   - Migration `V39`: `subtitle_sidecar.timed_fps`, `opensubtitles_reference.timed_fps` (nullable `DOUBLE
     PRECISION`), and `subtitle_sidecar.retime` (`AUTO` or `OFF`, default `AUTO`).
   - New downloads, picker downloads (`Offered.result().fps()`) and newly fetched references store their stated
     rate.
   - While the switch is off, setting an OWNER offset on a download, or verifying one, also sets `retime = OFF`.
   - The Java migration `V40__BackfillSubtitleFrameRates` (as `V27__BackfillPlaybackHealthFigures` does) does two
     things:
     - it fills stored rows by file id from `film.os_candidates`, `os_full_candidates` and `os_best`;
     - it sets `retime = OFF` on downloads with an OWNER offset or owner verification.

     A row it can't match stays null and is never retimed. Picker downloads are the likeliest misses, because the
     picker's results were kept in memory only.
   - The pure `FrameRateRetime` gives k, with unit tests at each band edge.
   - `FilmTimeline` is added, unused.
   - Tests: unit tests for both classes; the migration on H2; a `*IntegrationTest` for V39 and V40 on PostgreSQL
     (CI).
   - After release, before phase 2a merges, a read-only query on the server counts:
     - rows backfilled, out of the expected 92;
     - rows set to off;
     - comparisons of unknown against retimed that would be skipped.

     **If fewer than 80 of the 92 are backfilled, the owner is asked before 2a.** The choice is a one-off file-info
     lookup on OpenSubtitles, or leaving them listed.
2. **2a: every reader on the film's timeline, behind the switch.**
   - Every path listed above uses `FilmTimeline`.
   - The film rate is batch-loaded.
   - With the switch off, every result is unchanged, and a test pins that for each path.
3. **2b: identity, rules and offsets, behind the switch.**
   - k is appended to `TimingKeys.identity` and the version when k ≠ 1.
   - The rules versions follow the switch.
   - Current and deciding are split.
   - AUTO offsets of another k are ignored when read, and refitted.
   - Unknown against retimed gives no verdict, with a skip marker.
   - With the switch off, nothing changes for any file, and a test pins that.
4. **3: the Admin, then on.**
   - `PUT /api/admin/subtitles/{id}/retime` takes `AUTO` or `OFF`.
   - The rate line and the switch go in the Admin's subtitle view.
   - The switch's default becomes on.

Phase 3 may be built beside 2b (different files), but merged after it. 2a is the largest; if its estimate passes
1,200 lines, it is split by reader (what plays and the Admin first, then the comparisons and fits), and both halves
stay behind the switch.

## Checks after release

- The owner plays Jack Ryan: Shadow Recruit at about 1:28:57. The Russian line's subtitle should be on screen.
- When the re-check pass has finished (stored references first; it may fetch within the checks' share), a read-only
  query counts:
  - the downloads that now play retimed;
  - the proven count of each kind, against the same count taken before release. Expect no drop beyond the skipped
    unknown-against-retimed cases, which phase 1 counted.

## Decisions taken in this plan

1. **The stated rate decides.** It held on 5 of the 6 files with signal. The owner's switch covers a wrong one.
2. **Only across the PAL speed-up.** 30 fps copies run at film speed, and odd rates are left alone.
3. **23.976 and 24.0 are kept apart** (k = 23.976/25 against 24/25). They differ by 0.1%, about 7 s over two hours.
   The stated value is used as given.
4. **No re-ranking, and no replacing downloads for their rate.** A retime fixes them without spending downloads or
   re-picking.
5. **Hand-set timing is kept.** Owner offsets and verified files are not retimed unless the owner turns retime on.
6. **No Plex input or answer.** The film's own rate and OpenSubtitles' stated rate only.
