# Forced subtitles with no English track in the film: a timing check — plan

Written 2026-11-16 at the owner's request ("plan it"), after the owner asked how a forced file picked in Admin's
OpenSubtitles picker is known to line up and to be the right subtitles. Revised the same day after an adversarial
review. Extends `forced-subtitles-plan.md` (phase 3).

## The gap

- Phase 3a times a forced file against the full English track **inside the film**. Only about 25 films have one. For
  every other film the fit is `NO_REFERENCE`: the file plays at its own timing and nothing checks it.
- Phase 2d compares a forced file with a second *forced* OpenSubtitles file (the right lines, ±2 s). Two such files
  often come from the same release, so they agree whether or not they fit the owner's copy (decision 65: two
  OpenSubtitles files agreeing "does not prove they match this rip").
- An owner's pick in the picker is proven by being picked (`SidecarTrust.assess`, `Proof.CHOSEN`). The owner will not
  watch films to check subtitles, so a pick should still be checked and the result shown.

## The idea

A full English file has hundreds of lines and includes the English of the foreign lines. So:

1. **Right lines:** a correct forced file's cue starts are a subset of the full file's, at one offset. `TimingFit`
   measures this already (3a uses it against the embedded track).
2. **In sync with the film:** a full file is long enough to align to the film's **speech** reliably; a forced file
   (3 to 40 lines) is not. Aligning the full file to the audio gives its offset in the owner's copy. That offset, added
   to the forced-to-full offset, gives the forced file's offset in the owner's copy.

The proof is a chain: the forced file to the full file (`TimingFit`), then the full file to the film's audio (`alass`).
Step 1 shows the lines match a second release's full file. Only step 2 ties the timing to the owner's copy. **Until
step 2 exists, a fit against a downloaded file never changes trust or playback.**

## Three PRs, each 1,500 changed lines or fewer

### 3c-1 — find and fetch a full reference; measure only

Built as two PRs (owner's 1,500-line limit): **3c-1a** finds the editions and widens the full search; **3c-1b**
fetches and measures. Changes made while building (2026-11-16) are in "Build notes" at the end of this section.

Shaped like 5d-1's `FullFitProbe`. **Stores no fit and acts on nothing.** The only rows written are the stored
references, the search columns, and new reference metadata.

- **Timing-reference editions** (one pure predicate, unit-tested row by row). An edition qualifies when all of these
  hold:
  - The timing check covers its forced files (`SidecarTimingService.served`).
  - At least one of those files has **8 or more cues** (fewer is `TOO_FEW` anyway) and fewer than
    `SubtitleComparison.FULL_LINES` (300).
  - None of those files is owner-verified.
  - It does not play the embedded forced track.
  - Its embedded read is `NO_REFERENCE` for **every** part. Mixed split editions (one part has a track, another has
    none) are left out on purpose.
  - `Edition.runtimeMs` is above 0 (it is 0 when part lengths are unknown).
  - It is a single part, or its file is timed for the whole edition. Per-part files on split editions are left out:
    the reference would have to be cut at part boundaries.

  Editions with an owner's pick (`CHOSEN`) come first, since they are the gap this plan was written for.
- **Search.** `OpenSubtitlesRefresher.runFull` also searches films with a timing-reference edition. It uses the same
  `searchFull`, columns (`os_full_candidates`, V23) and due rules. The PR lists every reader of these columns:
  - **Gated on full need `NEEDED`, so English films are unaffected:** `ForcedDownloadService.fullDue`,
    `PendingDownloads.full`, and the counts in `FullNeedService.unresolvedCount`.
  - **Unaffected because forced and full file ids never overlap:** the forced path's `DownloadPlan.toFetch`.
  - **Unaffected because it searches live:** the picker.
  - **Changed: `FullFitService.due`.** Its `retainAll(findOpenSubtitlesFull)` would now let in an English film that has
    a FULL download the owner picked. Keep its old scope by also requiring full need `NEEDED`.
  - **Changed: `FullNeedService`'s `searched()`.** For these films it changes, so if the owner later marks one `NEEDED`,
    a FULL download is due at once from the stored candidates. That is acceptable; list it.

  Tests show that a timing-reference film with full candidates gets no FULL download and is not measured by
  `FullFitProbe`.
- **Fetch** (new `TimingReferenceFetch`, run by `SidecarCheckRefresher` **after the 2d loop**, so the owner's files are
  compared first, and **at most 2 fetches a run**). It goes through `ReferenceFetcher` and the check ceiling
  `DownloadShares.forChecks`.
  - **Candidates:** from `OpenSubtitlesRanking.rankForFull` for the edition's name. Single file only. Leave out any
    whose `fps` is known and not about 23.976. Use a hearing-impaired file only when no other is left, and log the flag.
  - **Storing:** each fetched file is stored as an `opensubtitles_reference` (never fetched twice, the existing rule).
    The fetch also stores **`cue_count` and `last_cue_end_ms`** (an expand-only migration on
    `opensubtitles_reference`), so nothing parses cue text on the playback path.
  - **Usable** means all of these:
    - 300 lines or more;
    - the last cue ends before the edition's runtime plus 60 s;
    - when credits detection is `FOUND`, the last cue ends after the credits start minus 10 minutes. This is a
      two-sided cut check, the shape of `FullFitService.creditsStart` and decision 65. The numbers are starting
      values, logged and revisited from the measurements.
  - **On a low result**, try the next candidate within the cap: a match share under 30%, an unusable file, or a fit
    shaped like decision 38's (the reference may leave out the foreign lines).
  - **Cap:** at most **3 reference files per edition and IMDb id**. This needs **no ledger and no `SubtitleKind` change**:
    count the film's stored references (current IMDb id) whose file ids are among its current `os_full_candidates`.
    Never write `FULL` rows to `forced_check_candidate`: `fullDueFor` would treat them as already compared.
- **The chosen reference** (deterministic): the highest-ranked usable stored reference, by `rankForFull`, among the
  current `os_full_candidates` of the current IMDb id.
- **Measure.** For every forced file of a timing-reference edition (the owner's, downloads and picks), fit it with
  `TimingFit` against the chosen reference. Write one INFO line, once per process per file and reference: film,
  edition, the file's origin, verdict, offset, share, residual, line counts, the reference's release and HI flag, its
  last cue against the runtime and credits, and whether the file has 300 lines or more.
- **Tests.**
  - The predicate, row by row.
  - The fetch: fps, HI last, the usable checks at their boundaries, the cap counted from references, a low share
    trying the next candidate, a spent budget or an outage ending the pass, and the order and limit after the 2d loop.
  - The search scope, and that a timing-reference film gets no FULL download and no `FullFitProbe` measurement.
  - Integration (Testcontainers): the migration.

#### Build notes (2026-11-16)

- **No chosen reference in 3c-1.** Each forced file is measured against **every** usable stored reference of its
  edition (at most 3 per film), one line per (file, reference). Measuring only the top-ranked one threw away the better
  file fetched after a low share. 3c-2 picks the rule from these lines.
- **Frame rate:** a candidate whose known fps is more than 0.01 from 23.976 is left out (24.000 drifts about 7 s over
  two hours). The measurement line carries the reference's fps.
- **Hearing-impaired strictly last** for the timing reference, ahead of cut affinity: a hearing-impaired file is
  fetched only when no other single-file candidate is left.
- **The walk moves on** when the best share is under 30% (integer arithmetic, `matched * 100 < served * 30`, as decision
  38) **or** the fitted offset is more than 30 s either way (another cut).
- **At most 2 timing fetches per UTC day** (counted in memory, so a restart can allow 2 more), as well as 2 per run,
  so 2d keeps most of the comparison share on every day. One map of stored references per film is shared across a
  run's editions, so two editions of one film never exceed the cap or count a free reuse as a fetch.
- A failure finding timing-reference films never stops the full search of films that need full subtitles.

### 3c-2 — store the fit and show it (trust decided from 3c-1's lines)

- **Fit** (`SidecarTimingService`): for a timing-reference edition, the reference starts are the chosen reference's cue
  starts (whole-edition timeline).
  - **Key** = the existing embedded key + `|os:<imdbId>:<osFileId>`. A re-rip, a new IMDb id or a new chosen reference
    each make the fit stale. Existing `NO_REFERENCE` rows go stale through the key change and move to `NEEDS_FIT`.
  - The chosen reference is bulk-loaded in `reads()` / `fits()`. Four call sites compute the key: `state()`, `job()`,
    `saveLocked()` and `current()` (`current()` runs on every `ForcedResolver.resolve`). Their tests cover the new key.
  - A fit never uses the forced file's own OpenSubtitles file id as its reference.
  - New column `forced_subtitle_alignment.reference_source` (`EMBEDDED` default, `OPENSUBTITLES`).
- **Verdicts against a downloaded file** (pure, with boundary tests):
  - **A file with 300 lines or more, or with more than half the reference's lines, is never `ALIGNED`.** It is `CHECK`,
    "Looks like full subtitles, not forced". Amsterdam and Olympus Has Fallen are real cases (decision 38).
  - `TimingFit`'s `ALIGNED` rules **and** |offset| of 1 s or less give `ALIGNED`, "Lines match a full English file from
    OpenSubtitles (<release>)".
  - The same match more than 1 s out gives `CHECK`, "…but <n> s apart: one of them is timed for another release".
  - Anything else is `CHECK`, with reasons that name the OpenSubtitles file and do not blame the forced file (the
    reference may be at fault).
  - **No offset is ever applied** (`shouldApply` is false for `OPENSUBTITLES`).
- **Trust: no change in 3c-2** (the review's critical finding). `SidecarTrust.assess` lets an `aligned` override a 2d
  `CHECK`, so counting this fit would make 2d-failed files play, stop replacements, let a download beat the embedded
  forced track, and stop 2d second opinions. `SidecarTrust.isAligned` therefore returns true only for
  `reference_source = EMBEDDED`. Whether a downloaded-file `ALIGNED` should ever stand in for "no 2d verdict" (never
  override a 2d `CHECK`, never beat the embedded forced track) is decided from 3c-1's lines, as an owner question if the
  answer is not clear.
- **Display.** `AlignmentDto` gains `source` (`foundation-plan.md` updated). `ForcedChoice.proofText` /
  `downloadProof` and `TimingFit`'s reasons stop hard-coding "film's own English track". Admin shows a source line:
  "Checked against a full English file from OpenSubtitles (not yet against the film)". An owner's pick that does not
  match shows its `CHECK` there, and still plays. Built (3c-2b): `proofText`/`downloadProof` and `TimingFit`'s reasons
  were left as they are, because an `ALIGNED` proof is only ever an embedded one until trust changes.
- **Tests.** The verdict boundaries (1 s / 1.001 s, a share of 0.80, 7 / 8 cues, 299 / 300 lines, half the reference),
  `isAligned` false for `OPENSUBTITLES`, `ForcedChoice` unchanged with such a fit (one test per step it could have
  moved), staleness on each key part, the DTO JSON (integration), and the frontend source line.

#### Built ahead of 3c-1's lines (owner, 2026-11-17)

Built before 3c-1's lines exist, with provisional rules the lines then adjust (owner). Trust and playback stay
untouched, so a wrong provisional rule can only mislabel a row in Admin.

- **Scope:** single-file editions with a `NO_REFERENCE` embedded read, searched under the film's current IMDb id, not
  marked `NONE_EXIST`. Wider than `TimingReferenceService`'s (its line-count, verified and plays-embedded conditions
  would need `ForcedResolver` in `current()`); display only. The background check fills in pre-V30 last-cue ends.
- **Choice (provisional):** fit to every usable stored reference (never the sidecar's own file); keep `ALIGNED` first,
  then the larger share, the smaller median residual, the lower file id. Key: the embedded key +
  `|os:<imdbId>:<usable file ids, sorted>`. `ALIGNED` within 1 s, one constant marked provisional.
- **Split:** 3c-2a is the backend; 3c-2b the copy (`proofText`, `downloadProof`, reasons) and the Admin source line.
  A follow-up adjusts the choice or the 1 s limit from the lines; the trust question above is asked then.

### 3d — tie the full file to the film's speech (`alass`)

Built after 3c-1's lines. If forced and full files nearly always agree, 3d is the missing proof. If they often disagree,
3d tells which file is right.

- **Tool.** `alass-cli` 2.0.0, x86_64, pinned by SHA-256 in the Dockerfile (rule 8). It decodes even a WAV through
  ffmpeg and ffprobe (its ffmpeg path environment variable), so both must be reachable in the image. Run it with
  `--no-split` and **`--disable-fps-guessing`**; OpenSubtitles files are not all 23.976. Before building, check the
  arguments against the release.
- **Audio.** CineFilm extracts it: an argument list, `-protocol_whitelist file`, the audio track the film plays, mono
  8 kHz WAV in the cache, deleted afterwards.
  - For a split edition, each part is padded or trimmed to its `timelineDurationMs` before joining, so the WAV matches
    `EditionTimeline`.
  - `nice`, a 20-minute timeout, and **stopped as soon as an HLS session starts encoding**, not only checked before.
- **Fit.** alass aligns the **full reference** (never the forced file) to the WAV. CineFilm takes one offset from the
  input and output cue times and stores it on the reference per edition. The key holds the media fingerprints, the
  reference file id and the alass version.
- **Verdict.** The forced file's offset in the film is the forced-to-full offset plus the full-to-film offset, judged
  on the raw file.
  - `ALIGNED` (film-anchored) when 3c-2's match holds at 10 s or less and alass's shift is trusted. Its residual
    threshold is set from the first logs.
  - Then the offset is applied with 3a's `shouldApply` rules: never over an `OWNER` offset or a verified file.
  - This `ALIGNED` counts as proof, as 3a's does. That trust change is the point of 3d, so its `ForcedChoice` tests are
    redone.
  - Admin says "Lined up with the film's speech using a full English file".
- **Cost.** One extraction per timing-reference edition, redone only when a film file changes.
- **Tests.** A real-tool test in CI: a generated clip with bursts of speech-band noise at cue times (alass's voice
  detection may not fire on a pure tone) and a full `.srt` shifted by a known amount. alass must recover the shift
  within 100 ms. Unit tests cover the composition, the verdicts and stopping when encoding starts.

## Decisions taken in this plan

1. **The full file, not the forced file, goes to alass.** With 3 to 40 lines, speech alignment can lock on to the
   wrong lines. Alternative: alass on the forced file (3b as first planned).
2. **A fit against a downloaded file never changes trust or playback before 3d.** Two OpenSubtitles files agreeing
   is not proof against the owner's copy (decision 65), and in `SidecarTrust.assess` an `aligned` overrides a 2d
   `CHECK`. Alternative: count it as `AGREES`. Rejected: `AGREES` never overrides a `CHECK`, so it is not the same
   strength.
3. **Measure first (3c-1), then store (3c-2).** This fits the owner's "measure first" and the 1,500-line limit.
   Alternative: one PR, about 2,000 lines.
4. **No ledger: the cap is counted from stored references.** A `FULL` or new `SubtitleKind` ledger row would leak into
   full downloads.
5. **Fetches run after 2d, two a run.** The check share is small (6 a day on a 20 allowance), and the owner's own files
   come first.
6. **Split editions with per-part files, and mixed ones, are out.** They are rare and need part cutting.

## Owner questions

None blocking. Reading 3c-1's lines needs the owner's `docker logs` on dockermacmini, as before. Whether a
downloaded-file `ALIGNED` should count before 3d may become a question after those lines.
