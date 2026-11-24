# Forced subtitles: confirm a file's own timing from the soundtrack (3e) — plan

Status: plan, 2026-11-24, written at the owner's request ("Yes, plan it") after 3d ended unproven
(`soundtrack-timing-plan.md`, "Result"). Revised the same day after two rounds of adversarial review. Measure first
(3e-M, planner, no PR); build only if every gate passes.

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
2. **Where the answers came from** (open question 2). Dances with Wolves' "sidecar 48 is 14 s late" and The Return
   of the King's owner-verified offset each remain an answer only if the owner set it by watching in CineFilm, not
   in Plex.
3. **The Return of the King.** 3d fitted it 3 s from its owner offset. If that offset stands, the owner hears CineFilm
   clips of three lines at the owner offset and at 3d's fit. Whichever the owner hears right becomes the answer. If
   neither is heard right, or the offset came from Plex, The Return of the King is dropped as an answer. Either way,
   the plan records why before step 4.
4. **Dry run on the design set.** The threshold rule below is applied to the design set's decoys, and the kit
   reports how many of the design set's own eligible forced files would be `CONFIRMED`. If it is below 50%, or below
   10 files, 3e stops here and the last unseen set is not spent. This changes no rule. It only decides whether the
   evaluation is worth running.
5. The evaluation list is written into this plan (docs PR), and only then mapped.

### Decoys (design set only)

Each decoy gets exactly one try, the same confirmation at its candidate, so it has the same chances as a real file.

- **Cross-film:** each design-set forced file on the speech maps of 3 seeded other design-set films that have a
  forced file, so there is foreign speech to land on by chance.
- **Shuffled:** each design-set forced file with its cues moved to seeded random times within its own first-to-last
  cue span. Durations are kept, but no cue is anchored to its own speech, so no run is left intact by construction.
- 3d's English-only draws are reported but don't count: B is low on them at almost any offset.

**Threshold, held out by film.** The design set's films are split by a seeded draw into halves A and B. A decoy
belongs to a half only if every film it involves (the forced file's film, and for a cross-film pair the map's film)
is in that half; mixed pairs are dropped and counted. **The threshold is 1.25 × the largest confirmation score among
half A's decoys.** It is computed once and frozen, and that frozen threshold is used for every gate and for the
build. Half B needs at least 50 decoys from at least 10 films, or 3e is unproven.

### Gates, in order (all declared before any score; any failure ends 3e)

1. **Never confident and wrong:** zero `CONFIRMED` among half B's decoys, and zero heard wrong in the ear-check.
2. **Robustness** on at least 10 `CONFIRMED` evaluation files:
   - dropping 20% of the cues, or jittering each cue by ±150 ms, keeps at least 90% `CONFIRMED`;
   - moving the second half of the cues by 5 s leaves at least 95% not confirmed.

   Fewer than 10 `CONFIRMED` files means unproven.
3. **Useful coverage:** at least **50%** of the evaluation set's eligible files are `CONFIRMED`. Eligible means 8 or
   more cues in the forced file itself, counted before any fit, with an English-original film. The bar is lower than
   3d's 70% because confirming a file's own timing can only cover files that already play right. The owner may raise
   it (question 1).
4. **The absolute answers**, judged once on the frozen threshold, each at a stated offset in raw cue time:
   - Dances with Wolves: sidecar 2 at 0 is `CONFIRMED`. Sidecar 48 at 0, 14 s from the speech, is **not** confirmed.
   - The Two Towers: at its `EMBEDDED` fit, `CONFIRMED`. At that fit + 14 s, not confirmed.
   - The Return of the King, if an answer stands from step 0: at the answer, `CONFIRMED`. At the other of the two
     heard offsets, not confirmed.

   The split Lord of the Rings editions are handled as in 3d's kit.
5. **Cost:** within 3d's budget (168 CPU-hours to map every forced-file edition). The confirmation adds only seconds
   per file.
6. **Ear-check (last, only if 1–5 pass):** a seeded 10 of the `CONFIRMED` evaluation files, every one heard right to
   about ±1 s, on 3d's clips (6 s before, 2 s after, the line's text). With 10 of 10 right, the error rate on
   confirmed files is below about 26% (one-sided 95%). This is the only bound on errors under about 2 s.

## Build (only if 3e-M passes; each PR at most 1,500 changed lines)

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
5. **A dry run on the design set before the last unseen set is spent.**
6. **No Plex input or answer**, so the method still works after Plex is gone.

## Open questions for the owner

1. **The coverage bar.** The plan sets 50% of eligible files. Say if it should be higher.
2. **Where the two heard answers came from.** Were Dances with Wolves' "sidecar 48 is 14 s late" and The Return of
   the King's offset set while watching in CineFilm, or in Plex?
