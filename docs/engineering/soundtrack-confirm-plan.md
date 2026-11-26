# Forced subtitles: confirm a file's own timing from the soundtrack (3e) — plan

Status: plan, 2026-11-24, written at the owner's request ("Yes, plan it") after 3d ended unproven
(`soundtrack-timing-plan.md`, "Result"). Revised the same day after two rounds of adversarial review. Measure first
(3e-M, planner, no PR); build only if every gate passes.
**2026-11-24: the first dry run failed on `tiny`'s labels (2 of 26 confirmed); 3e continues only with a better
language detector** ("The dry run, and a better detector" below). The evaluation set is unspent.
**2026-11-26: 3e ends, unproven** ("Result: 3e ends" below). Neither a better labeller nor the right frame rate
lifts it: the speech maps cannot place a file to the 300 ms the confirmation needs. The evaluation set is unspent.
Nothing is built.

## Why

3d searched ±120 s on a 100 ms grid for each forced file's best offset, and asked whether that best offset stood
out. It often found the right offset (Dances with Wolves' two sidecars exactly 14 s apart; The Two Towers within
30 ms), but it was never sure. About 2,400 offsets give every wrong pairing about 2,400 chances to look good, so
chance pairings reached a margin of 7.60 and no real file reached the threshold of 9.50.

3e stops searching. It asks one question per file: **does the forced file, at the offset it plays with, sit on
foreign speech?** That is one try per file, and one per decoy. A file that does is `CONFIRMED`. A file that doesn't
stays unconfirmed and is shown as today, and the owner can pick another. No offset is found or applied. This proves
file choices, which is most of the value: the forced files that play correctly gain a proof they lack today.

**Precision: about ±1 s, by ear.** A one-try check can't tell a file that is right from one that is 0.5–1 s off with
a flat-topped score (lines shown 3–4 s over shorter speech). Only the ear-check bounds that. `CONFIRMED` means "on
the right foreign speech, to about ±1 s", not "to the frame".

**Not in 3e: the chain through a full English file.** Composing 3c's forced-to-full fit with a whole-track fit of
the full file to the speech would give a second candidate offset. At the 2026-11-20 export only 5 of about 148
forced files had a full OpenSubtitles reference. 3c fetches at most 2 a day, and many films have no reference that
fits their cut, so the chain could not be proven on this library. It waits until enough references exist for a
sample of its own.

**No Plex, ever (owner, 2026-11-24).** Plex is going away, so films added later will have nothing from Plex. No step
of 3e, measured or built, uses Plex's subtitles, markers, timings or play history, as input or as a known answer.
Every known answer comes from the film itself (its own English subtitle track), or from the owner's ear on CineFilm
clips.

## The confirmation (score B at the offset the file plays with)

The labels, the grid and the score are 3d's (`tiny`, `LABEL_DEFAULTS`, B = (foreign − English span) / span of all
cues).

- **The candidate c** is the offset the file plays with: its applied `offset_ms` (an owner offset or an applied
  `EMBEDDED` fit), else 0. A decoy's candidate is its forced file's applied offset, else 0. The absolute answers are
  tested at the offsets gate 4 states.
- **The local peak.** B is refined on a 10 ms grid over c ± 2 s. Call its peak p.
- **The confirmation score.** The z-score of B at p against B at every offset in ±120 s of c that is more than 2 s
  from c.
- A file is **`CONFIRMED`** when all of these hold:
  - the confirmation score is over the threshold;
  - |p − c| ≤ 300 ms;
  - at least 8 cues land on speech at p;
  - each half of the cues, refined on its own over c ± 2 s, peaks within 300 ms of p;
  - at least one cue that opens a run (no cue in the 3 s before it) has speech labelled foreign under it at p, so
    every confirmation can be heard.
- Anything else is **not confirmed**. p − c (minus 3d's 100 ms bias) is reported only and never applied.

## Measure first (3e-M)

The kit and container are 3d's (`~/cinefilm-labels/soundtrack/` on dockermacmini), with no network. The owner runs
each command. The kit gains a confirm mode in `evaluate.py`.

### Data, and what is spent

- **Design set:** every media file already mapped, which includes 3d's 60. Its 3d results have been seen. It is used
  to build the method, to set the threshold and to run the decoy gate, and it never enters coverage, robustness or
  the ear-check.
- **The absolute answers' films** (Dances with Wolves and the Lord of the Rings editions) are left out of
  everything that sets or judges a threshold, as forced file and as audio. They are judged only in gate 4.
- **Evaluation set: every remaining forced media file.** That means every media file with a forced file, minus every
  media file with a speech map on the server at the time of the draw, minus every Fellowship of the Ring edition and
  the absolute answers' films. All of them are used, not a sample. About 80 files are expected, and the step-0 PR
  writes in the exact list. At about 20 a night that is about 4 nightly batches. Between batches only counts and
  cost are shown, and the set is evaluated **once**. An eligible file that can't be mapped counts as not confirmed
  and is never replaced. **This is the library's last unseen set:** if 3e fails, the next attempt waits for films
  added later.

### Step 0, before any evaluation file is mapped

1. The owner copies `~/cinefilm-labels/soundtrack/out/` and the server's `maps/` listing to the Mac.
2. **Where the answers came from (owner, 2026-11-24).** Dances with Wolves' "sidecar 48 is 14 s late" was heard
   while watching in CineFilm, so it stands. The owner doesn't recall watching The Return of the King, so its
   owner-verified offset has no known source.
3. **The Return of the King is dropped as an answer.** Its offset can't be shown to come from CineFilm, and 3d's fit
   was 3 s from it, so neither can be trusted without a listen. It stays out of every decoy and gate, as the
   absolute answers' films do.
4. **Dry run on the design set (half A only).** The kit computes the threshold from half A's decoys and reports,
   for the design set's own eligible forced files outside half B's films, how many would be `CONFIRMED` and how
   those pass gate 2's robustness changes. **Half B's decoys are not scored.** If coverage is below 50% or below 10
   files, or either robustness bar fails, the evaluation does not go ahead: the method may be changed and the dry run
   repeated (half A and these files are design data), and 3e ends, with the last unseen set unspent, if no change
   passes. Every attempt is counted and recorded at the freeze.
5. **Freeze.** The method and the threshold are written into this plan (docs PR) and not changed again.
6. **Half B, once.** Half B's decoys are scored once on the frozen method, and the plan records that they were. Any
   `CONFIRMED` among them fails gate 1 and ends 3e before the last unseen set is spent.
7. The evaluation list is written into this plan (docs PR), and only then mapped.

### Decoys (design set only)

Each decoy gets exactly one try, the same confirmation at its candidate, so it has the same chances as a real file.

- **Cross-film:** each design-set forced file on the speech maps of 3 seeded other design-set films that have a
  forced file, so there is foreign speech to land on by chance.
- **Shuffled:** each design-set forced file with its cues moved to seeded random times within its own first-to-last
  cue span. Durations are kept, but no cue is anchored to its own speech, so no run is left intact by construction.
- 3d's English-only draws are reported but don't count: B is low on them at almost any offset.

**Threshold, held out by film.** The design set's films are split into halves A and B by a seeded draw (seed 3,
fixed now, before any 3e score). A decoy
belongs to a half only if every film it involves (the forced file's film, and for a cross-film pair the map's film)
is in that half; mixed pairs are dropped and counted. **The threshold is 1.25 × the largest confirmation score among
half A's decoys.** It is frozen at step 5, and that frozen threshold is used for every gate and for the build. Half B needs at least 50 decoys from at least 10 films, or 3e is unproven.

### Gates, in order (all declared before any score; any failure ends 3e)

1. **Never confident and wrong:** zero `CONFIRMED` among half B's decoys (step 6), and zero heard wrong in the
   ear-check.
2. **Robustness** on at least 10 `CONFIRMED` evaluation files:
   - dropping 20% of the cues, or jittering each cue by ±150 ms, keeps at least 90% `CONFIRMED`;
   - moving the second half of the cues by 5 s leaves at least 95% not confirmed.

   Fewer than 10 `CONFIRMED` files means unproven.
3. **Useful coverage:** at least **50%** of the evaluation set's eligible files are `CONFIRMED`. Eligible means 8 or
   more cues in the forced file itself, counted before any fit, with an English-original film. The bar is lower than
   3d's 70% because confirming a file's own timing can only cover files that already play right. The owner may raise
   it (question 1).
4. **The absolute answers**, judged once on the frozen threshold, each at a stated offset in raw cue time:
   - Dances with Wolves: sidecar 2 at 0 is `CONFIRMED`. Sidecar 48 at 0, 14 s from the speech, is **not** confirmed. Its answer stands (step 0).
   - The Two Towers: at its `EMBEDDED` fit, `CONFIRMED`. At that fit + 14 s, not confirmed.
   The Return of the King is not judged (step 0). The Two Towers' split edition is handled as in 3d's kit.
5. **Cost:** within 3d's budget (168 CPU-hours to map every forced-file edition). The confirmation adds only seconds
   per file.
6. **Ear-check (last, only if 1–5 pass):** a seeded 10 of the `CONFIRMED` evaluation files, every one heard right to
   about ±1 s, on 3d's clips (6 s before, 2 s after, the line's text). With 10 of 10 right, the error rate on
   confirmed files is below about 26% (one-sided 95%). This is the only bound on errors under about 2 s.

## The dry run, and a better detector (3e-L, 2026-11-24)

### Attempt 1: `tiny`'s labels, failed

Step 4 ran once on the design set (kit `confirm.py`).
- **Threshold:** 72 half-A decoys from 31 films, largest score 2.767, threshold 3.458.
- **Coverage:** of the 26 eligible design files outside half B's films, **2 (7.7%) were `CONFIRMED`**. The others
  failed, a file often on several conditions: the score 24, the peak 19, the halves 16, cues on speech 11, the
  opening cue 7.
- **Robustness:** dropping 20% of the cues kept 1 of the 2. Jitter and the second-half move passed.

The evaluation does not go ahead. Half B was not scored, and the 80-file evaluation list is unspent.

Nearly every file fails on the score itself, even against a bar this low. A check on design data (3d's frozen B
fits of the 31 half-A files, report `20261124T072259Z`) points to a weak signal, not to files playing at a wrong
offset:
- their best offsets are spread over the whole ±120 s: 10 within 2 s of 0, 16 within 10 s;
- their margins are 0 to 7.0, mostly 2 to 3.5, the range of decoys;
- a file truly off would show one clear peak elsewhere, and almost none does.

This is evidence, not proof: some files may also be off. 3e changes only the labeller, as step 4 allows ("the method
may be changed and the dry run repeated"). No candidate's labels on Dances with Wolves or any Lord of the Rings
edition are looked at before the freeze.

### The candidates (fixed now; at most two more attempts)

Each produces p(en) for the same speech stretches the maps hold (Silero VAD, 1 s or more), from the stretch's first
30 s, as the kit does now. **Only p(en) changes.** Each stretch keeps its `tiny` no-speech probability from the
existing map, so the no-speech filter (`max_no_speech` 0.6) is the same for both candidates. The SpeechBrain model
gives no such value. The labels are `LABEL_DEFAULTS` as before. A stretch with p(en) of 0.7 or more is English, 0.15
or less is foreign, and anything between is neutral. Nothing is tuned per model.

- **L1: `faster-whisper` `base`**, the language probe only. It is already fetched on the server, pinned and
  hash-checked. 3d estimated 2–3 times `tiny`'s map time.
- **L2: SpeechBrain `lang-id-voxlingua107-ecapa`**, a dedicated spoken-language model.
  - Licence Apache-2.0. Checkpoints about 85 MB. Pinned at commit `0253049ae131d6a4be1c4f0d8b0ff483a0f8c8e9`.
  - Input 16 kHz mono. Its card reports a 6.7% error on the VoxLingua107 dev set.
  - p(en) is its posterior for English. Lakota is not among its 107 languages, which matters only if it calls
    Lakota English. No design data holds known foreign speech (the Dances with Wolves film is held out), so that
    risk is carried by gate 4 (sidecar 2 must confirm) and the ear-check, not by the label check.
  - It needs `torch`, `torchaudio` and `speechbrain` wheels, pinned and hash-checked in a lock of their own. The
    owner fetches them once.

### How each candidate is tried (design data only)

1. **Maps for half A and the bias films only.** The owner re-labels the half-A films' maps and 3d's bias films'
   maps (full-track English films with no forced file, already mapped) in nightly batches. This reads the films
   again over SMB, since the maps hold no audio. Half B's and the evaluation set's films are not re-labelled until a
   candidate is chosen. Cost and peak RSS are recorded per film.
2. **Label check, on the bias films:** the share of the speech under their full English tracks that is labelled
   foreign must be at most 5% (`tiny`: 0.7%). The share of speech labelled neutral under half A's forced cues at 0
   is reported. A candidate over 5% is rejected without a dry run.
3. **Dry run.** Step 4 is repeated on the candidate's labels, with the same rules. Everything else is pinned from
   attempt 1's `tiny` run (`dryrun-output.txt`), not rebuilt from whichever films have candidate maps:
   - the half-A and half-B film lists;
   - the decoy specs (which forced file goes on which film's map, and the shuffle seeds);
   - the 80-id evaluation list.

   Only the timelines' labels come from the candidate's maps. The threshold is recomputed from half A.
4. **Product fit.** The candidate must run offline in a container with no network, under 1.5 GB peak RSS. Its
   projected cost for every forced-file edition (the design rate × the forced films' runtime) must be at most
   168 CPU-hours.

**Choice.** A candidate is eligible only if it passes the label check, the dry run and the product-fit check. An
eligible candidate is chosen. If both are eligible, the higher design coverage wins, and the
cheaper wins a tie. If neither passes, **3e ends**, unproven, with the evaluation set unspent. Attempts are counted
(1 is `tiny`), and their numbers go into this section before step 5. After the choice, half B's films and the
evaluation set are mapped with the chosen labeller only. Steps 5 to 7 then run as written.

## Result: 3e ends, unproven (2026-11-26)

### Attempt 2: `base`, stopped early

`base` relabelled 21 of the 54 media files to relabel (31 half-A and 23 bias files; it reached 21 half-A files and no
bias file), taking 59,085 CPU seconds with peak
RSS under 700 MB and no failures. On those files it labels the speech under the forced cues as `tiny` does: foreign
32.4% against 32.6%, English 32.7% against 32.4%. It was stopped on the owner's word (2026-11-25) and not dry-run.
L2 (SpeechBrain) was not tried, because the labeller was not the cause.

### The frame rate is not the cause either

A design check on half A (planner, 2026-11-25) tried each file at 7 frame-rate ratios and found 6 files that fit far
better at a ratio other than 1: they drift, as a file timed for a 25 fps release does on a 23.976 fps film, or the
reverse. Since v0.11.5 the server retimes every OpenSubtitles file by its stated rate
(`subtitle-frame-rate-plan.md`). Hitman and Jack Ryan were heard in sync after it.

The planner then ran the confirmation (kit `confirm.py`, `tiny` maps, half A only) on the cues as the server now
plays them: each file's original cues × its `retimed_by` from the server, plus 4 further tries at 23.976/25,
25/23.976, 24/25 and 25/24. **2 of the 26 eligible files pass every rule and the 3.458 threshold**, the same 2 as
attempt 1 (2012 and John Wick).

The rule that fails is the 300 ms peak rule. Hitman, heard in sync since the retime, peaks at −730 ms. Captain
America (−820 ms) and Inferno (−1,360 ms), whose retime the fit supports but no one has heard, peak further out.
Jack Ryan, also heard in sync, is the only one of these within 300 ms. The maps' speech stretches are too coarse to place a file to 300 ms. No labeller or rate change alters that, and the plan allows no
change to the rules once a dry run has been seen.

### What is left

- **3e ends here, unproven.** Attempt 3 is not run: on design data it cannot reach the coverage bar. Half B was
  never scored, and the 80-file evaluation list is unspent.
- **What the maps can see** is a gross drift: a retimed Hitman's share of foreign speech under its cues goes from 0%
  to 49%. A coarse check of that kind (is this file at the wrong rate?) is a separate question with a separate
  plan (`subtitle-drift-check-plan.md`), proved on its own data. It uses half B, never scored by 3e, and turns to the
  evaluation list only if half B has too few files.
- 2 half-A files still drift after the retime, because their stated rate looks wrong: Bridge of Spies (sidecar 60)
  and The Brutalist (sidecar 166; the film has an intermission, so its cause is not proven). The owner sets no rate
  (owner, 2026-11-26: "3e-R only, no control"), so they wait for that check.

## Build (not done: 3e ended unproven; kept for the record)

This reuses 3d's build shape: a speech map stored per edition, made only for editions with a forced file, with a
trigger when an edition gains its first one. A film added after Plex is gone is mapped like any other. The pure
`SoundtrackConfirm` takes a forced file's cues, its candidate, the map and the frozen threshold, and gives
`CONFIRMED` or not. The trust question, asked with 3e-M's numbers: may a `CONFIRMED` file count as proof of a
forced-file choice (to about ±1 s)? It would never set an offset. The PR split is written after 3e-M.

## Decisions taken in this plan

1. **One try per file, at the offset it plays with.** Each extra candidate gives a decoy another try. A file that
   plays out of sync stays unconfirmed, which costs coverage, never correctness.
2. **Precision is stated as about ±1 s, by ear.** No decoy tests errors under 2 s honestly: a shifted copy of a
   passing file fails the local-peak rule by arithmetic.
3. **The chain is left for later**, when enough files have full references to prove it on its own sample.
4. **Thresholds held out by film, from the design set only**, so the decoy gate can fail and no evaluation file
   sets the bar.
5. **A dry run on the design set before the last unseen set is spent.** Half B is scored once, after the method
   is frozen, so the decoy gate stays held out.
6. **No Plex input or answer**, so the method still works after Plex is gone.

## Open questions for the owner

0. **L2 in the product.** If the SpeechBrain model is chosen, CineFilm's speech job would need `torch` in an image,
   which adds several hundred MB. That is asked again with the numbers before any build. It is not needed to
   measure.

1. **The coverage bar.** The plan sets 50% of eligible files. Say if it should be higher.
2. ~~Where the two heard answers came from.~~ Answered 2026-11-24 (step 0): Dances with Wolves was heard in
   CineFilm. The Return of the King has no known source and is dropped.
