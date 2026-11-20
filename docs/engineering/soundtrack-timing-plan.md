# Forced subtitles: timing from the soundtrack (3d) — plan

Status: plan, 2026-11-20 (revised after adversarial review, and for B's proof by ear after the header scan).
Replaces the `alass` sketch under "3d" in `forced-timing-reference-plan.md`.

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
- **Bias.** Cue times lead or lag speech by a roughly constant amount. For each score, 3d-M takes it as the median
  fitted offset of the synthetic draws (below), leaving one film out. Its sign: the corrected offset is the fitted
  offset **minus** the bias. B's synthetic draws are all decoys (see "The
  test set"), so B takes A's bias. Nothing measures B's own bias: B's timing is proven only to about ±0.5 s, by
  ear, and 3d-3's trust question says so. The starts-only bias is reported beside A's, so a dependence on the cue
  ends shows. The `EMBEDDED` and owner-verified files are a reported check only.
  Real forced files' cue ends are not tested by the draws.
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
- **Language labels, for choosing the p(en) and neutral thresholds,** leave-one-film-out: every fit and decoy of a
  film is judged with thresholds chosen without that film. They come from each film's
  original language (`film.original_language`, from TMDB), because almost no forced file has a known answer:
  - **Foreign:** all speech in a film whose original language is not English. No English subtitle track is needed.
    The library has about 20 such films; a seeded sample of up to 10 is mapped.
  - **English:** speech under full-track cues in an English-original film with no forced file.
  - **Lakota, reported only:** Dances with Wolves' sidecar 2 cue spans at offset 0. The film is an absolute answer,
    so these spans never take part in choosing a threshold.

  These labels are approximate (an English-original film has some foreign lines, and the reverse), so they set the
  thresholds and are never a pass or fail gate.

### The test set

The first export (2026-11-20) found 151 forced files on 148 single-part films. Only **one** has an `EMBEDDED`
`ALIGNED` fit and only **one** is owner-verified: the owner checks films by watching, not in Admin. So the known
answers are built from the films' own full English tracks. The owner exports the inputs read-only with `psql`.

A header scan of all 1,102 films (2026-11-20) found 61 with a full English subtitle track. **All 61 are bitmap
tracks** (pictures, as on a disc), and **all 61 films are English-original**: no foreign-original film has an English
track. A bitmap track still carries each line's display and clear times, which is all a fit needs. The kit reads
them from that one subtitle stream in the same offline container, by codec (the header scan records it):
- **PGS** (Blu-ray): every display set that shows new content ends the line before it and starts a new one; an empty
  one ends the open line.
- **VobSub** (`dvd_subtitle`, DVD): the start and end come from each packet's own display commands (or the
  container's duration when it states one).
- A line with no readable end ends at the next start, at most 3 s on, as for the text tracks; the report counts
  them.
 That read covers most of each file over SMB and is
counted in the per-film cost. The 3 text tracks the app already read (`embedded_cue_starts`, `USABLE`) are used as
well, whether or not the scan counted them; the report lists them and which films they are on. So:

- score A gets its known answers from those 61 films;
- score B has none: it is proven by the owner's ear instead (below).

- **Synthetic forced files, the main test (answer 0).**
  - **What they are.** Every single-part film with an embedded full English track gives 10 seeded draws of 8–40 of
    its cues, in 1–4 runs of consecutive cues, as forced files are. Starts and ends are read from the track itself:
    a bitmap line's display and clear times, or a text cue's start and end. The 3 text tracks found in
    `embedded_cue_starts` hold starts only; there each end is the next start, capped at 3 s.
  - **Score A** is judged on every draw.
  - **Score B** is never judged on them. Draws from films whose `original_language` is `en` and that have **no forced file** sit on English speech, so a B fit
    must never come out `ALIGNED`, at any offset: they are decoys for B. Draws from films with a forced file are
    left out for B, since a full track also subtitles the foreign lines, and so are draws from films whose language
    is not `en` or unknown; the report counts both. The decoys
    are chosen without the classifier. Such a film can still have a short foreign line under an English track's
    cue, so the decoy set is settled **before any margin threshold is chosen**: after the language thresholds, every
    decoy whose best B offset is within 1 s of 0 with a B score above 0 is heard once by the owner. This screen runs
    for each model before the model is chosen. For each such draw the kit clips (as in the ear-check) every cue whose
    span at that offset holds speech labelled foreign, at most 3 per draw, largest first. The draw is dropped, counted, only if foreign
    speech is heard under **every** clipped cue and those cues hold most of the foreign-labelled span at that
    offset; otherwise it stays a decoy. The margin threshold is then computed once on the rest. If more than 10 draws need
    hearing across both models, the report stops for a decision. **B needs at least 5 films and 50 decoy draws left
    after the screen**; below that, B is unproven. The report says how many decoys sit mostly
    on speech labelled foreign, as a check on the labels.
  - **What the draws cannot show.** A's draws are dense runs of subtitled lines, not a sparse forced file among
    English dialogue, A's known weakness. B's accuracy on foreign speech is not shown by any draw; the ear-check
    below is its proof.
- **Absolute answers, each judged on its own.** The **films** of all three are left out entirely of everything that
  chooses a threshold or the bias, on both sides: their synthetic draws, their language labels, the bias median, and
  every decoy, whether as the forced file or as the audio (cross-film pairs included). Their results are still
  reported.
  - **Dances with Wolves.** The fits of sidecars 2 and 48 must differ by 14 ± 0.3 s, sidecar 2 must come out within
    ±1 s of 0, and both must be `ALIGNED`.
  - **The one `EMBEDDED` `ALIGNED` file:** its fitted offset on the raw cues, ±200 ms.
  - **The one owner-verified file:** its current offset, ±200 ms.
- **Robustness of the real forced files, against each one's base fit b.** These are changes the score curve is not
  invariant to:
  - drop 30–50% of the cues, or jitter cue starts by ±300 ms: must stay within ±200 ms of b;
  - move only the second half by 2–20 s, as another cut would: must come out `CHECK`. This is the only test of the
    halves rule.

  A plain shift of every cue is reported as a check of the search code only, outside any gate. Shifts are kept within
  the window: |b + s| ≤ 110 s, with "beyond the cap" meaning |b + s| > 30 s.
- **Decoys,** each a wrong pick for the threshold rule:
  - every forced file against other films' audio (a seeded sample of 40 when there are more);
  - the forced file with its cue order and gaps shuffled (durations kept) against its own film;
  - each fit's second-best peak more than 2 s away.
- **The owner's ear, B's proof (owner, 2026-11-20).** It runs only after every threshold and the bias are frozen, and
  only for **one model**, chosen before it on the decoys and cost alone (the one that passes the decoys; if both do,
  the cheaper). The absolute answers are then judged once, on that model, before the ear-check. If the chosen model
  fails any later gate, B fails: the other model is never tried. The list is drawn only from forced files whose films have maps, from their fits
  under the **final frozen** thresholds and bias, the ones the product will use (nothing is tuned on what is heard).
  It holds:
  - **10 distinct real forced files** whose B fit is `ALIGNED`, by a seeded random draw;
  - **plus, heard on top,** the lowest-margin file and the one furthest from 0, if the draw missed them.

  The player's clock shows whole seconds, so the owner does not listen in the player. For each file the kit cuts
  three **audio clips** from the same file, in the same offline container, from the start, middle and end of the
  cues. Each clip's line **lands on speech** at the fitted offset and is the **first line of a run**: no cue in the
  3 s before it, so a fit shifted by a whole
  line cannot land its click on other foreign speech unnoticed. Each clip runs from 6 s before the point where the
  fit puts that line's speech to 2 s after where it puts the line's end (its raw time plus the fitted offset, the bias not applied, since the bias is
  the gap between display and speech). One click marks where the line starts and a second where it ends, and each
  clip is listed with the line's text. A file with fewer than three such lines is clipped on those it has, stays in the draw,
  and is reported; a file with none is left out of the draw before it is made, does not count toward the 10, and is
  reported. The owner plays them on the
  Mac. A file is right only if, in every clip, the foreign speech begins on the first click, within about half a
  second, with no foreign speech in the 3 s before it, and stops near the second. The rule is strict on purpose: an
  unsubtitled foreign line just before a run makes a right fit fail, which can only make B fail, never pass.

  Forced files on films whose original language is not `en`, unknown included (none today: all 151 are on
  English-original films), get no B verdict,
  here or in the product, since B has no English to penalise there; they are left out of the ear-check and useful
  coverage, and counted.

  **B is proven only if 10 random files are heard and every file heard is right**; fewer than 10 `ALIGNED` files
  means B is unproven. Ten random files right bound B's error rate on `ALIGNED` fits below about 26% (one-sided
  95%), for errors the ear can catch (about 0.5 s), not the ±200 ms A is held to; the report says so, and the decoys
  and absolute answers carry the rest. A's ear-check, if A is ever considered, is a list drawn the same way from the
  files whose A fit is `ALIGNED`, with the same rule: 10 random files heard and every file heard right, otherwise A
  is unproven. Files not heard keep the verdict the gate gives; 3d-3's trust question carries the
  ear-check's bound.
- **For information, not truth:** the five OpenSubtitles films and Taken 3 (spot-checked at 32–37 min), plus an
  optional accented-English film chosen at export.

### The gate for building

- **Thresholds come first and are never tuned to raise coverage.**
  - **Margin.** 1.25× the largest margin of any decoy or wrong pick, by leave-one-film-out (the held-out film left
    out on both sides). Coverage is reported at 1.0×, 1.25× and 1.5×. A second-best peak's z-score is taken against
    the same offsets as the best peak's, with ±2 s around both left out.
  - **p(en) and neutral band.** Chosen on the language labels.
  - **Held out.** The absolute answers are left out of all of these and judged once, on the chosen model, before the
    ear-check.
  - **Frozen.** After the leave-one-film-out gates, the same rules (margin 1.25× the largest decoy or wrong-pick
    margin, the p(en) and neutral band, the bias median) are applied once to every film except the absolute answers.
    That frozen set is what the product uses; the frozen record of every file's fit and verdict is what useful
    coverage, the ear-check and 3d-2's parity are taken from.
- **Accuracy.** A: at least 90% of synthetic draws within ±200 ms, after the bias. B: the ear-check, every file
  heard right, to about ±0.5 s.
- **Robustness:**
  - at least 90% of the drop and jitter changes within ±200 ms of b;
  - at least 95% of the second-half moves `CHECK`;
  - **no false rejections by the halves rule (A):** at least 90% of synthetic draws (one cut, answer 0) have halves
    that agree. The share of draws that come out `ALIGNED` is reported. B has no draws with an answer, so for B the
    report counts the real forced files rejected by the halves rule alone, for information.
- **Never confident and wrong:** zero `ALIGNED` results more than 1 s off, among items with a known answer, and none
  on a decoy. This counts:
  - the synthetic draws;
  - the robustness changes;
  - every decoy; for B, any `ALIGNED` on a decoy draw counts, whatever its offset, except a draw dropped after the
    owner heard foreign speech under it (see "The test set");
  - every ear-check the owner finds wrong.
- **The absolute answers** each pass, as stated above.
- **Useful coverage:** at least 70% of eligible real forced files come out `ALIGNED` in the frozen record. Eligible means 8 or more cues on
  speech. This is reported beside the ear-check's precision.
- **Which score is built:** for each score, every gate above that applies to it must pass.
  - **B** first, if its gates pass in this order: the decoy screen and the decoy minimum; never confident and wrong;
    robustness; useful coverage; the absolute answers on the chosen model; |A's bias| at most 300 ms (the report
    stops past that, since B takes it untested); and last the ear-check, run only after all the others have passed.
  - **Otherwise A,** if its gates pass, Dances with Wolves and the other absolute answers pass under A, A's ear-check
    finds none wrong, and the owner says yes. The synthetic draws cannot show A's known weakness: locking a sparse forced file on
    to English dialogue.
  - **Otherwise neither.**
- **Cost (Python):** at most 15 minutes of one CPU per film, with peak RSS under 1.5 GB.
  - One film is timed first.
  - If the films with forced files and the full-track films together would take too long, the run covers the
    full-track films, the absolute answers, the foreign-label sample and a seeded sample of the rest. The report says
    what that sample cannot show.

If neither score passes, stop and report. The chosen
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
- **One row per sidecar.** When the sidecar's edition has a speech map, the row is the soundtrack fit, except when
  the score built is B and `film.original_language` is not `en` (a null language included): then the row stays as with no map
  (3d-M never measured B there), with tests for a foreign and a null language. If A is built, the exception does not apply. With no map yet,
  the row stays the OpenSubtitles fit. The OpenSubtitles result for an edition with a map is still logged.
- **The key.** The map's fingerprint and versions plus `SoundtrackFit.RULES_VERSION`. The four key sites (`state`,
  `job`, `saveLocked`, `current`) are each tested.
- **The order of references:** the film's own English track, then the soundtrack, then OpenSubtitles (display only).
- **Split editions.** A whole-edition file is fitted to the parts' maps joined on the edition timeline. A per-part file
  is fitted to its own part's map.
- **Admin's reasons:** "Lined up with the film's speech", or "No clear match with the film's speech".
- **Fit parity, the gate before 3d-3 is briefed.** A tool-run `main` fits the 3d-M test set from `SpeechMapMain`'s
  output files and the `psql` export of the forced files, never the database. 3d-M's gate judges each film with
  thresholds chosen without it, but the product uses one frozen set, so after freezing 3d-M also records every
  file's fit and verdict under the final thresholds and bias, which is the record the ear-check is drawn from. The
  tool's offsets must be within 10 ms of that record, with the same verdict on every file, and it must meet the same
  never-wrong gate. A's accuracy is measured again; B's carries over from 3d-M's ear-check. This needs the
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
   offset and choose between forced files by itself, within the `SPEECH` limits above? If B is chosen, the question
   says that the speech position is proven only by ear, to about ±0.5 s and not the ±200 ms A is held to; that the
   display correction (A's bias, stated in ms) is taken from A and untested for B; and that ten heard fits right
   bound B's error rate on `ALIGNED` fits below about 26% (one-sided 95%) for errors of that size.

## Not in scope

- Burned-in subtitles, and picture-based forced files as fit input. Picture-based full tracks are read in 3d-M only
  for their display times.
- Recognising which foreign language is spoken.
- Sung foreign parts. These are labelled neutral where Whisper marks music or no speech. 3d-M reports how often songs
  are still labelled foreign speech.
