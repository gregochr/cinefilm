# Credits: the soundtrack check on automatic markers — plan

Status: plan, 2026-11-22 (revised after two adversarial reviews).

## Why

3c switched automatic (`AUTO`) end-credits markers on (2026-11-22, v0.10.1): version 2's detections mark 83 editions
that have no usable Plex marker. The owner then set the direction (2026-11-22):

- **Plex is gone.** CineFilm must work as the only server. Stored Plex markers keep working, but a new or replaced
  file never gets one, so version 2's detections are the only automatic source from now on.
- **A working server, not an archive of training data.** 3c's test-split hold (deny rule 2, the unit key's hash side,
  and the deny file's `film` and `unit` entries, rules 1, 3 and 6) keeps every edition on the test side off `AUTO`,
  only to keep a fair test for a version 3 that may never come. **The test split and the version 3 gate on it are
  retired** (owner, 2026-11-22: "I want a media server that works...not an archive of training data", then "Yes, go"
  to this plan's outline, which lifts the hold). Any later detector is judged on the whole library's Plex markers,
  the weaker check, and says so.
- **Belt and braces.** Every automatic marker gets an independent sanity check before it is used.

The owner first asked for subtitles as that check (the credits cannot start before the last line of dialogue). The
library has almost no English subtitle tracks (all 174 files read so far had none), and OpenSubtitles files cover
about one edition in six and are timed to someone else's copy. So the owner chose the **soundtrack** (2026-11-22):
every film has one, it is timed to this copy exactly, and it costs no download quota. The same idea, read from the
audio: **if people keep talking well after the detected start, the credits have not started.**

## The check

For an edition whose `AUTO` marker would be set (3c's other rules all hold), with version 2's start `S` in **file
time** on the last original part (`Detection.offsetMs`):

- **The window** is `[S + L, S + 180 s]`, clipped to the file's end. The lead-in `L` leaves out a last line that
  runs a moment past the start. 30 s is the starting point, the value of `CreditsTuning.MAX_DIALOGUE_AFTER_START_MS`
  (version 2's allowance for dialogue after its start, which applies only when it has English subtitle cue times, so
  in this library almost never). `L` is measured, not inherited.
- **The speech** in the window is Silero VAD's stretches (3d's settings: minimum silence 250 ms, speech pad 30 ms),
  counting stretches of at least 1 s.
- **The check passes** when the window holds less than `T` seconds of such speech, and **fails** (no `AUTO`; the
  edition stays on the 97% fallback) when it holds `T` or more.
- `L`, the window's end, `T` and the 1 s floor are set by the measurement and then fixed in `CreditsTuning`, beside
  `MAX_DIALOGUE_AFTER_START_MS`.

Songs over the credits are the known risk: singing can read as speech. The measurement counts how often it does; if
songs make the check fail too often, the Whisper no-speech probability 3d already reads is added to set singing aside
(a revised plan, measured again).

What the check does not catch, said plainly: an early start with no talking after it (an epilogue card, a title
card, a silent last scene). Version 2's few real early starts are mostly of that kind. The check guards against the
other kind, a detection that lands in a scene that still has dialogue, which matters more now that every new film
depends on version 2 alone.

## Measure first (S-M: planner, no PR)

Nothing is built until the check is shown to work on films whose answer is known.

- **The kit.** 3d-M's soundtrack kit on the server (the pinned `python:3.12-slim` image, faster-whisper wheels,
  Silero through `VadOptions`, its `speech_map.py` mounted read-only), with S-M's own script and output directory,
  `~/cinefilm-labels/credits-tail/`. Runs are `--network none`, `--cpus 1`, `nice -n 19`, the share read-only, named
  `credits-tail`, started by the owner when nothing is playing and never beside a 3d-M run. Results are library data
  and stay in `~/cinefilm-labels/`.
- **The films.** Every edition's last original part with a Plex marker or a version 2 `FOUND` row. **Truth** is
  Plex's start `P` (file time) where it is usable: the Plex row's fingerprint is the detection's.
- **What is read.** The VAD stretches (with p(en) and the no-speech probability per stretch of 1 s or more) from 8
  minutes before the earliest known start (`P` or `S`), or the last 20 minutes if that is earlier, to the end. So
  every window below is covered, however long the credits.
- **The sets:**
  - **Agree:** films where `S` is within 20 s of `P`. Both say the credits start here.
  - **Ambiguous:** `S` 20–120 s before `P`. Designed title cards (the owner's 2026-11-22 case: the first card is the
    start, and Plex marks the later crawl) make either answer possible. Left out of both rates, reported.
  - **Early:** `S` more than 120 s before `P`. Version 2's real early starts.
- **What is reported**, for each candidate `T` (0 to 30 s in 1 s steps) and `L` (15, 30 or 45 s):
  - **False alarms (gated):** the share of **Agree** films the check fails at their own `S`, and at `P`.
  - **Synthetic catches (gated):** the check at `S = P − 120 s` and `P − 300 s` on Agree films, only where that start
    lies inside the last part and, converted to edition time with `EditionTimeline.toGlobal`, passes
    `EditionGuard.plausible`. These stand in for a detection that lands in a scene with dialogue.
  - **Real catches (reported):** the share of **Early** films the check fails at their `S`, with n. n is expected to be
    tiny (L5: none of 238 more than 15 s early; one development film of 602, the title-card case), too few to gate.
  - **Preview (reported):** the verdict for the 83 `AUTO` editions and the editions the split holds, at their `S`.
  - Per film: speech seconds in the window, in the 3 minutes before `S`, the longest stretch, and how much of the
    window's speech has a high no-speech probability (singing and music, for the songs question).

**The gate** (written before any number is seen): for each `L`, pick the smallest `T` whose false-alarm share on
Agree films at their own `S` is at most **5%**; keep the `L` whose `T` catches most at `P − 120 s`. The check is built
only if that catch share is at least **80%**. Otherwise the numbers and the songs that tripped it go to the owner with
a revised plan.

**What goes public.** Only the frozen `L`, window end, `T` and floor, and the gate's pass or fail, go in this file.
The sets' sizes, the rates and the preview stay in `~/cinefilm-labels/`: they mix in test-side films' version 2
results, which were never published beyond L5's bins.

## Build (after the gate; each PR at most 1,500 changed lines)

### S-1a — the VAD adapter and the stored tail

- **Runtime.** ONNX Runtime (already in the image) runs Silero VAD, pinned by SHA-256 to the model the kit's
  faster-whisper version bundles, with the same `get_speech_timestamps` rules (thresholds, hysteresis, minimum
  speech, padding). This is 3d-1a's adapter: whichever of S-1a and 3d-1a is built first writes it, and the other
  reuses it. Tested on Linux only, as L4b, and the PR asks the owner for that class's coverage exception.
- **Storage.** A `speech_tail` row per media file: its fingerprint, the audio stream index, the model version, the
  stream-rule version, the window read (file time), a status (`READ` or `FAILED`), the reason when it failed, and the
  stretches as compact text (as `embedded_cue_starts`; empty on a failed read). S-1b's picking and S-2's verdict read
  only these columns.
- **The stream** is chosen by the rule playback uses, the same rule as the kit's (default disposition, never
  commentary or description).

### S-1b — the job, the trigger and parity

- **The job.** A background job decodes, from ffmpeg (mono 16 kHz, never written to disk), only
  `[S − 180 s, S + 180 s]` of each last original part with a version 2 `FOUND` row. It picks, in this order:
  1. files with no tail for their fingerprint, or whose stored window does not cover the current check window
     (`[S + L, min(S + 180 s, file end)]` for the current `S`);
  2. files whose tail was read with an older model or stream-rule version (used until replaced).

  A file that cannot be read (no audio stream, ffmpeg fails) is stored as a failed read for that fingerprint and
  version, with the reason, and is not picked again until either changes. It runs at nice 19, one file at a time,
  serialised with the credits jobs, and yields to playback through `PlaybackGuard`. Its log line says
  `speech tails: N read, F failed, M to read`.
- **The trigger.** Storing a tail publishes `SpeechTailRead`, and `CreditsApplier` listens for it (as for
  `CreditsDetected`), with a test, so a new film's marker follows its tail without waiting for an unrelated pass.
- **Parity, before S-2 is briefed.** A tool-run main from the released image reads the S-M films' product span
  `[S − 180 s, S + 180 s]`, and the kit re-reads exactly the same span from the same start sample. Edges more than
  1 s from the span's ends must agree within one Silero chunk plus the resampler's delay (stated in the brief, about
  50 ms); the speech seconds in each check window within 1 s; and the verdict at the frozen `T` and `L` on every film.
  A miss is fixed in S-1b before anything uses the tail.

### S-2 — the check gates `AUTO`

- **Inputs.** The tail joins `AutoInputs` inside `CreditsAutoReader.lookup` and `candidateLookup`, one query per
  batch, so the pass, the candidates endpoint and Admin Clear (`CreditsAdminService.clear`) all see the same tail. Like
  the other reads there, it is skipped when the switch is off or no deny list is in force. The fixed query count per
  batch that `CreditsApplierIntegrationTest` pins goes up by one, and the test says so.
- **Rule.** `CreditsApply` works out version 2's marker and, separately, the soundtrack verdict (`PASS`, `FAIL` with
  the speech seconds, or `NO_TAIL`). `NO_TAIL` covers no tail, a failed read, and a tail whose window does not cover
  the current check window. `apply` sets `AUTO` only on `PASS`. Pure, with the new constants in `CreditsTuning`.
- **Stale.** A fingerprint change, or a window that no longer covers the current start, makes a tail unusable. A model
  or stream-rule change does not: those tails are re-read in the background and used until then, so such a change
  never blanks markers.
- **Candidates.** `CreditsApply.candidate` returns a hold reason instead of null, and the endpoint (3c-1d) lists
  held editions too:
  - held by the soundtrack check when it fails or there is no usable tail;
  - held by the split when the edition is denied by rule 1, 2, 3 or 6 (the test split: a `film` or `unit` entry, the
    hash side, or a listed film's unit) and by no `edition` or `file` entry. A test with a manifest test film that has
    both `film` and `unit` entries pins it.

  The hold is reported in two separate fields, so neither hides the other: `split` (true or false) and `soundtrack`
  (`PASS`, `FAIL` with the speech seconds, or `NO_TAIL`). A held edition is listed only when every other rule holds
  (version 2 `FOUND`, the kind allowed, the timeline, the guard, and no `edition` or `file` entry). A test pins a
  split-denied edition that fails the check showing both.

  Its invariant becomes: every candidate not flagged `owner`, with `split` false and `soundtrack` `PASS`, is exactly
  what a pass sets `AUTO`.
  `foundation-plan.md`'s row for the endpoint and `operations.md`'s "Fetching the candidates" section are updated in
  the same PR.
- **The sheet tool.** `AutoCheckCandidate` gains `split` and `soundtrack`. `AutoCheckOptions` gains a filter flag
  (`--held-split-pass`: only `split` true with `soundtrack` `PASS`; without it, only editions with `split` false and
  `soundtrack` `PASS`, today's set), and `AutoCheckJob` applies it before writing sheets and `list.csv`. `--full`
  takes the same flag and finds its edition only within the filtered set. `list.csv` gains both columns, with tests.
  The runbook's sheet section is updated in the same PR: the default set in place of "all candidates", `list.csv`'s
  two new columns, and the flag the S-3 look uses (with `--full` too).
- **Log.** The pass line adds `N held by the soundtrack check`.
- **Tests** include Clear on a failing edition not setting `AUTO`, and the candidates endpoint and the pass agreeing.
- **Release order.** S-1b is released first and its job runs until it says `0 to read`. Only then is S-2 released,
  so the check never blanks markers because the job has not reached a file yet. A file added later has no `AUTO`
  marker until the job reads it.

### S-3 — lift the test hold

- **An interim deny file first.** G0's file denies the test films' editions and files too (`edition` and `file`
  entries), so they would be held by those and never listed with `split` true. Before the look, the planner writes an
  interim deny file:
  - **every** `film` and `unit` entry of the file in force (any `SPLIT_CONFLICT` unit key included);
  - G1's unclear editions as `edition` and `file` entries;
  - the `edition` and `file` entries of any edition that rules 1, 2, 3 and 6 no longer deny (one merged or moved
    since G0 into a film they don't catch). A read-only query the owner runs gives each listed edition's and file's
    current film and unit key, and the planner checks it before writing the file. Each such edition also gets a
    stills look of its own before S-3, since S-3's final file drops its entries.

  The owner puts it in place. Every edition it releases is still held by the split, so the pass that reads it sets no
  new `AUTO`, and the candidates endpoint now lists them with `split` true.
- **The look**, after S-2 is released and before S-3 is built: the owner fetches the candidates, and the
  editions with `split` true and `soundtrack` `PASS` get G1's stills check (the sheet tool, G1's rules and
  G1's ends). The planner judges them:
  - an unclear one goes in the deny file as an `edition` and a `file` entry;
  - **an early card turns cards off, and an early crawl turns `AUTO` off**, as in 3c: the look is a gate, and an end
    needs a revised plan. Denying an early edition and lifting the hold anyway is not allowed.
- **The change.** `CreditsApply.denied` loses rule 2. The planner writes a new deny file with only the unclear
  editions (G1's 13 and the look's), no `film` or `unit` entries, and the owner puts it in place when S-3 is
  released.
- After that, new films need no stills: version 2, the guard and the soundtrack check decide.

## Not in scope

- **The editions version 2 found nothing on, or was unsure about.** Using the soundtrack to find their credits
  (the start of the long stretch with no talking near the end) is a later plan, measured on S-M's data.
- Owner markers and Plex markers: the check never touches them.
- The learned classifier (L6), still held.
