# Forced subtitles: timing from the soundtrack (3d) — plan

Status: plan, 2026-11-20 (revised after adversarial review). Replaces the `alass` sketch under "3d" in
`forced-timing-reference-plan.md`.

## Why

The owner's goal is to import a film and let CineFilm do the rest. Forced subtitles (the foreign-language parts only)
are checked today in two ways:

- **Against the film's own English subtitle track (`EMBEDDED`).** This counts as proof and may set the offset.
- **Against a full English file stored from OpenSubtitles (3c).** This is display only, because two files agreeing
  says nothing about this copy of the film.

Many films have neither. Dances with Wolves (edition 210) has no subtitle track, and OpenSubtitles has only
Lakota-parts files for its cut. Its two forced files have the same lines 14 s apart all the way through. The owner
heard the words about 14 s before sidecar 48's lines: sidecar 2 is right, and sidecar 48, which is the one served, is
about 14 s late. **The owner is leaving it unfixed as this plan's test case (2026-11-20).**

The film's own soundtrack is the evidence every copy has. Two signals can be read from it:

- **A. Speech activity.** When anyone is speaking. A forced file's lines must sit on speech. Its weakness is that a
  forced file covers only a small share of the speech, because the English dialogue is speech too.
- **B. Spoken language.** Each stretch of speech is labelled **English**, **foreign** or **neutral**:
  - **Neutral** means too short, a low VAD probability, Whisper's no-speech probability high, or the English
    probability between two thresholds. It covers shouting, battle, music and singing.
  - **The idea:** a forced file's lines should cover foreign stretches and avoid English ones. That also exposes a file
    made for another cut.

Lakota and Sindarin are not Whisper languages. Only one number is used, the probability that a stretch is English
(p(en)), and where unknown languages land is measured before it is trusted.

**Why this answers settled decision 1** (forced files of 3 to 40 lines can lock on to the wrong lines when fitted to
a full file). Score B does not search a full file's thousands of lines for a match. It needs foreign speech under the
cues and no English speech under them, a pattern a wrong offset rarely reproduces. The gate below tests that claim
rather than assuming it, with a minimum number of cues that land on speech.

The old 3d (`alass` aligning a full OpenSubtitles file to the audio) needed a full English file, which is exactly what
these films lack. It is superseded.

## The fit (shared by measurement and product)

- **Cues.** The raw cue times, never with an offset already applied. During the search every cue counts: B is
  divided by the span of **all** cues, so parking cues on silence only lowers it. Only after the best offset is chosen
  are cues on no speech at that offset (a sign, a caption such as "Berlin, 1945", a letter) set aside, for the
  coverage and the "8 cues on speech" count.
- **Score A.** The share of the total cue span that lies on speech.
- **Score B.** (span on foreign speech − span on English speech) / the span of all cues. Neutral time and silence
  score 0.
- **Search.** Offsets from −120 s to +120 s, on a 100 ms grid, then refined at 10 ms.
- **Margin.** A z-score of the best offset against the scores over all offsets more than 2 s away. It has no units, so
  one threshold works across films.
- **Halves.** The first and second halves of the cues are fitted separately and must agree within 300 ms. This catches
  frame-rate drift and another cut, which a single offset cannot.
- **Bias.** Cue starts lead or lag speech by a roughly constant amount. 3d-M estimates that global bias by
  cross-validation and subtracts it.
- **`ALIGNED`** requires all four:
  - the margin is over the threshold;
  - at least 8 cues land on speech (`TimingFit.MIN_CUES`);
  - the halves agree;
  - |offset| ≤ 30 s, inside 3c's other-cut rule.

  An offset over 10 s (`TimingFit.MAX_ALIGNED_OFFSET_MS`) is allowed here only because the halves check holds.
  Otherwise the result is `CHECK`.

## Measure first (3d-M: planner, no PR)

No production code is written until the signal is proven on films whose answers are known.

### How it runs

This is **not** credits L4a's convention: L4a ran the released image, installed nothing and read only local files.
3d-M needs a third-party Python set and must read the media share, so it is set up like this:

- **Image.** `python:3.12-slim`, pinned by digest, pulled once by the owner (network).
- **Packages.** `faster-whisper` ≥ 1.1 (for the standalone `detect_language`) and its dependencies, pinned with hashes.
  Wheels are fetched once into `~/cinefilm-labels/soundtrack/wheels/` by running `pip download` **inside that image**,
  so they are Linux wheels (network). Audio is decoded with PyAV, a faster-whisper dependency, so no ffmpeg or `apt` is
  needed.
- **Models.** CTranslate2 conversions `Systran/faster-whisper-tiny` and `-base` at a named revision, fetched by the
  owner with a SHA-256-checked command (network).
- **The runs.** `--network none`, `--cpus 1`, `nice -n 19`, `--memory 2g`, with the share mounted read-only. The owner
  starts each run at a time when nothing is playing. Wall time, CPU time, peak RSS and bytes read are recorded per
  film.
- **The scripts** live in `~/cinefilm-labels/soundtrack/`, outside the repo, as credits L3a's did. Their results are
  library data and are never committed.

### What is measured

- **The audio stream.** The one playback selects (the original language, not commentary or description). Its index
  and language are logged.
- **VAD.** Silero, through faster-whisper's `VadOptions`, set explicitly: minimum silence 250 ms and speech pad 30 ms,
  not the defaults of 2,000 ms and 400 ms, which merge alternating lines and blur timing.
- **Language.** One encoder pass per stretch, the stretch alone padded to Whisper's 30 s window: the method the
  product uses, so 3d-M's cost is the product's pass count. Stretches under 1 s are neutral and skipped.
  - p(en) comes from `detect_language` on that stretch.
  - The no-speech probability comes from CTranslate2's `generate(..., return_no_speech_prob=True)` at the start-token
    step, since `detect_language` returns languages only.
  - The VAD probability of a stretch is the mean of Silero's per-frame probabilities, from calling the bundled Silero
    model frame by frame, since `get_speech_timestamps` returns stretches with no probability.
- **Per film:** the share of speech labelled foreign, English and neutral; a p(en) histogram; and, for Dances with
  Wolves, the p(en) distribution on its Lakota stretches.
- **The classifier's own accuracy,** on films with an embedded full English track and an `ALIGNED` forced file. The
  full track also translates the foreign dialogue, so it cannot mark English on its own. At the forced file's true
  offset:
  - **Foreign:** speech under a forced cue that a full-track cue starts within 300 ms of (the `TimingFit` window).
    This drops most sign and caption cues, which sit over English speech.
  - **English:** speech under a full-track cue that no forced cue overlaps.
  - **Left out:** a stretch with no cue at all, and one that straddles the two.

  Forced files skip some foreign lines, so this truth is approximate. It is used to read the p(en) and neutral-band
  thresholds, never as a pass or fail gate.

### The test set

The first export (2026-11-20) found 151 forced files on 148 single-part films. Only **one** has an `EMBEDDED`
`ALIGNED` fit and only **one** is owner-verified: the owner checks films by watching, not in Admin. So known answers
are scarce, and the main tests are ones that need none. The owner exports the inputs read-only with `psql`.

- **Shift recovery, the main test (no answers needed):**
  - **Base fit.** Every forced file gets a base fit b.
  - **Recoverable shifts.** The same file at 16 random shifts s within ±30 s (the `ALIGNED` cap) must fit at b + s.
    That holds whatever the file's true timing is, so all 151 files count.
  - **Beyond the cap.** 4 shifts beyond 30 s must come out `CHECK`.
- **Cross-film controls (no answers needed).** Every forced file is run against other films' audio (a sample of 40
  when there are more). None may come out `ALIGNED`.
- **Full English tracks (answer 0 by definition).** Every single-part film with an embedded full English track (its
  cue starts, from `embedded_cue_starts`) is fitted with a starts-only version of score A: the share of cue starts
  that fall in speech within ±200 ms. This tests the speech timing at scale, but not the language labels.
- **Absolute answers.**
  - **Dances with Wolves.** The fits of sidecars 2 and 48 must differ by 14 ± 0.3 s, and sidecar 2 must come out
    within ±1 s of 0.
  - **The one `EMBEDDED` `ALIGNED` file:** its fitted offset on the raw cues.
  - **The one owner-verified file:** its current offset.
- **Base offsets for the owner's ear.** Every `ALIGNED` base fit more than 1 s from 0 is listed, by margin. The owner
  checks up to five by ear, as for Dances with Wolves.
- **For information, not truth:** the five OpenSubtitles films and Taken 3 (spot-checked at 32–37 min), plus an
  optional accented-English film chosen at export.

### The gate for building

- **Thresholds are not judged on the films they were set on.** The margin and neutral-band thresholds are chosen by
  leave-one-film-out, with the held-out film left out on both sides. The margin threshold must be at least 1.5× the
  largest margin of any wrong pick: a cross-film fit, or a shift recovered more than 1 s off.
- **Accuracy.** At least 90% within ±200 ms on:
  - shift recovery (of files whose base fit has 8 or more cues on speech);
  - the full English tracks;
  - the absolute answers.
- **Never confident and wrong:** zero `ALIGNED` results more than 1 s off, across shift recovery, the cross-film
  controls, the full tracks and the absolute answers. **A file the owner checks by ear and finds right as it is,
  while the fit confidently said otherwise, also counts.**
- **Useful coverage:** with the held-out thresholds, at least 70% of forced files' base fits come out `ALIGNED`.
- **Dances with Wolves** passes as stated above, with **both** sidecars `ALIGNED`.
- **Cost (Python):** at most 15 minutes of one CPU per film, with peak RSS under 1.5 GB. One film is timed first. If
  148 films would take too long, the run covers the films with answers, the full-track films and a seeded sample of
  the rest, and the report says what that sample cannot show.

If B passes and A does not, build B. If both pass, build B and log A. If neither passes, stop and report. The chosen
thresholds and the bias are written into this plan before 3d-1a is briefed.

## Build (after the gate; each PR at most 1,500 changed lines)

The shape is fixed here. Details come from 3d-M's numbers.

### 3d-1a — VAD and the speech map, stored

- **Runtime.** ONNX Runtime, already in the image, runs Silero VAD. The Silero version is pinned by SHA-256, with its
  streaming state loop written for that version.
- **Storage.** A `speech_map` row per media file holds:
  - its fingerprint;
  - the audio stream index and language;
  - the model and map versions;
  - the stretches as compact text, as `embedded_cue_starts` does.

  The key reads only the fingerprint and version columns, never the text.
- **Testing on Linux only.** ORT has no `osx-x64` natives (the L4b lesson), so the adapter is a one-method class
  behind an interface, tested in CI only, and the PR asks the owner for that class's coverage exception.

### 3d-1b — language

- **The model.** A Whisper encoder and decoder exported to ONNX, run for one decoder step: language-token logits after
  the start token, with the token ids pinned to the tokenizer version.
- **A Java log-mel front end.** It is its own pure, tested unit, checked against reference values computed in Python.
- **One encoder pass per stretch**, padded to 30 s, as measured in 3d-M. Stretches are never packed: a mixed window
  gives one mixed p(en). The no-speech probability is read from the `<|nospeech|>` logit at the start-token step.
- **The export.** A published ONNX Whisper export (for example `onnx-community/whisper-tiny` or `-base`) at a pinned
  revision with SHA-256, its licence checked in the brief, or an export made with a pinned Optimum version. Parity
  covers whichever variant (fp32 or int8) ships.
- **Image size.** fp32 sizes are counted against the disk: `tiny` about 150 MB, `base` about 290 MB, encoder and
  decoder together. A smaller int8 export is used if parity allows.
- **CI.** The model is fetched with a checksum and cached.

### 3d-1c — the job, and parity

- **The job.** A background job decodes the stream playback selects, piped from ffmpeg (mono 16 kHz) and never
  written to disk. It runs at nice 19, one film at a time, **serialised with the credits jobs**.
- **Yielding to playback.** The job yields to playback through `PlaybackGuard` and stops as soon as an HLS session
  starts encoding.
- **Order:**
  - films with a forced file and no `EMBEDDED` proof;
  - films whose forced files disagree with each other;
  - then the rest.
- **Cost.** About 850 files at up to 15 min is roughly 200 CPU-hours, plus reading every file over SMB once. The PR
  states the measured rate and the expected days.
- **Map parity, the gate before 3d-2 is briefed.** A tool-run `main` (`SpeechMapMain`) builds the maps of the whole
  3d-M test set in a throwaway container from a released image. **The owner releases after 3d-1c.**
  - Unlike credits' tools, it mounts the share read-only, as 3d-M does, and the owner starts it when nothing is
    playing (no `PlaybackGuard` in a throwaway container).
  - It runs with `--network none`, so it never touches the database. It writes the maps to an output directory, as
    credits' tools write to `credits-dataset-out`.

  It must give:
  - stretch edges within 30 ms of 3d-M's;
  - per-stretch p(en) within the tolerance 3d-M sets;
  - CPU per film within 15 minutes;
  - peak RSS inside the app container's `mem_limit: 2g` alongside a transcode (L4a's limit).

  The `whisper.cpp` fallback, if ever used, must pass the same parity.

### 3d-2 — the fit and the verdict (`SoundtrackFit`, pure)

- **The fit.** As in "The fit" above, with 3d-M's thresholds.
- **The source.** A new reference source, `SOUNDTRACK`, added by a migration that widens `reference_source`'s check.
- **One row per sidecar.** When the sidecar's edition has a speech map, the row is the soundtrack fit. With no map yet,
  the row stays the OpenSubtitles fit. The OpenSubtitles result for an edition with a map is still logged.
- **The key.** The map's fingerprint and versions plus `SoundtrackFit.RULES_VERSION`. The four key sites (`state`,
  `job`, `saveLocked`, `current`) are each tested.
- **The order of references:** the film's own English track, then the soundtrack, then OpenSubtitles (display only).
- **Split editions.** A whole-edition file is fitted to the parts' maps joined on the edition timeline. A per-part file
  is fitted to its own part's map.
- **Admin's reasons:** "Lined up with the film's speech", or "No clear match with the film's speech".
- **Fit parity, the gate before 3d-3 is briefed.** A tool-run `main` fits the 3d-M test set from `SpeechMapMain`'s
  output files and the `psql` export of the forced files, never the database. The
  offsets must be within 10 ms of 3d-M's, and it must meet the same accuracy and never-wrong gate. This needs the
  owner's release after 3d-2.

### 3d-3 — trust: the soundtrack may decide (needs the owner's yes)

3c-2's critical finding still holds: counting a fit in `SidecarTrust.isAligned` would turn a 2d `CHECK` into proof
and let a download beat the film's embedded forced track. So:

- **A new `Proof`, `SPEECH`.** It never overrides a 2d `CHECK` and never outranks the embedded forced track. The PR
  names each `ForcedChoice` step it may move, with a test per step.
- **The offset.** `shouldApply` accepts `SOUNDTRACK` (it refuses OpenSubtitles today), under its rules: never over an
  `OWNER` offset or an owner-verified file.
- **The acceptance check.** Dances with Wolves plays sidecar 2, or sidecar 48 at about −14 s, with no one touching it.
  3d-3's brief first records both sidecars' current trust and `ForcedChoice` step from production, and names the
  route that acceptance takes under the `SPEECH` limits:
  - **the choice moves to sidecar 2**, possible only if nothing outranks it; or
  - **sidecar 48 keeps its place** and gets −14 s applied.

  If neither route is open, the plan says so before building.

## Open questions for the owner

1. **The measurement container.** May the owner run a throwaway Python container on the server? It needs a one-time
   image pull, wheel fetch and model fetch (each pinned and hash-checked), then runs with no network and only while
   nothing is playing. It is measurement only and never part of CineFilm.
2. **Trust (asked again before 3d-3, with 3d-M's numbers).** When the soundtrack says `ALIGNED`, may CineFilm set the
   offset and choose between forced files by itself, within the `SPEECH` limits above?

## Not in scope

- Burned-in or picture-based subtitles.
- Recognising which foreign language is spoken.
- Sung foreign parts. These are labelled neutral where Whisper marks music or no speech. 3d-M reports how often songs
  are still labelled foreign speech.
