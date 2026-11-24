# Forced subtitles: confirm a file's own timing from the soundtrack (3e) — plan

Status: plan, 2026-11-24, written at the owner's request ("Yes, plan it") after 3d ended unproven
(`soundtrack-timing-plan.md`, "Result"). Revised the same day after an adversarial review. Measure first (3e-M,
planner, no PR); build only if every gate passes.

## Why

3d searched ±120 s on a 100 ms grid for each forced file's best offset, and asked whether that best offset stood
out. It often found the right offset (Dances with Wolves' two sidecars exactly 14 s apart; The Two Towers within
30 ms), but it was never sure. About 2,400 offsets give every wrong pairing about 2,400 chances to look good, so
chance pairings reached a margin of 7.60 and no real file reached the threshold of 9.50.

3e stops searching. It asks one question per file: **does the forced file, as it plays now, sit on foreign speech?**
That is one try per file, and one per decoy. A file that is right as it plays is `CONFIRMED`. A file that isn't stays
unconfirmed and is shown as today, and the owner can pick another. No offset is found or applied. This proves file
choices, which is most of the value: the forced files that play correctly gain a proof they lack today.

**Not in 3e: the chain through a full English file.** Composing 3c's forced-to-full fit with a whole-track fit of
the full file to the speech would give a second candidate offset. At the 2026-11-20 export only 5 of about 148
forced files had a full OpenSubtitles reference. 3c fetches at most 2 a day, and many films have no reference that
fits their cut, so a sample could hold about 2 such files and the chain could not be proven. It waits until enough
references exist for a sample of its own.

**No Plex, ever (owner, 2026-11-24).** Plex is going away, so films added later will have nothing from Plex. No step
of 3e, measured or built, uses Plex's subtitles, markers, timings or play history, as input or as a known answer.
Every known answer comes from the film itself (its own English subtitle track), from a shift made on purpose, or
from the owner's ear on CineFilm clips.

## The confirmation (score B at the file's own timing)

The labels, the grid and the score are 3d's (`tiny`, `LABEL_DEFAULTS`, B = (foreign − English span) / span of all
cues). Offsets below are raw: the cue times as stored, with no bias applied.

- **The local peak.** B is refined on a 10 ms grid over 0 ± 2 s. Call its peak p.
- **The confirmation score.** The z-score of B at p against B at every offset in ±120 s more than 2 s from 0.
- A file is **`CONFIRMED`** when all of these hold:
  - the confirmation score is over the threshold;
  - |p| ≤ 300 ms, so the file is right as it plays and not merely near a better offset (a file whose true
    offset is 0.7–2 s away peaks there, not at 0);
  - at least 8 cues land on speech at p;
  - each half of the cues, refined on its own over 0 ± 2 s, peaks within 300 ms of p;
  - at least one cue that opens a run (no cue in the 3 s before it) has speech labelled foreign under it at p, so
    every confirmation can be heard.
- Anything else is **not confirmed**. The reported offset is p minus 3d's bias (100 ms). It is reported only and
  never applied.

## Measure first (3e-M)

The kit and container are 3d's (`~/cinefilm-labels/soundtrack/` on dockermacmini), with no network. The owner runs
each command. The kit gains a confirm mode in `evaluate.py`.

### Data, and what is spent

- **Design set:** every media file already mapped, which includes 3d's 60 and its absolute answers. Its 3d results
  have been seen. It may be used freely to build the method and to explain misses, and never enters a gate.
- **Evaluation set: every remaining forced media file.** That means every media file with a forced file, minus
  every media file with a speech map on the server at the time of the draw, minus every Fellowship of the Ring
  edition and the absolute answers' films. All of them are used, not a sample. About 80 files are expected, and the
  step-0 PR writes in the exact list. At about 20 a night that is about 4 nightly batches. Between batches only
  counts and cost are shown, and the set is evaluated **once**. An eligible file that can't be mapped counts as not
  confirmed and is never replaced. **This is the library's last unseen set:** if 3e fails, the next attempt waits
  for films added later.
- **Step 0, before any design work:**
  1. The owner copies `~/cinefilm-labels/soundtrack/out/` and the server's `maps/` listing to the Mac.
  2. The evaluation list is written into this plan (docs PR).
  3. **The Return of the King.** 3d fitted it 3 s from its owner-verified offset. The owner is asked where that
     offset was set. It may remain an answer only if it was set by watching in CineFilm, not in Plex. Then the owner
     hears CineFilm clips of three lines at the stored offset and at 3d's fit. Whichever the owner hears right
     becomes the answer. If neither is heard right, or the offset came from Plex, The Return of the King is dropped
     as an answer, and this plan says why before any 3e score is computed.

### Decoys

Each decoy gets exactly one try, the same confirmation at its own 0, so it has the same chances as a real file.

- **Cross-film:** the forced files of 40 seeded media files, each on the speech map of a seeded other film **that
  itself has a forced file**, so there is foreign speech to land on by chance.
- **Shuffled:** each design-set forced file with its cue order shuffled. Durations and gaps are kept, and the first
  cue's time is kept, so the same film's foreign speech is under it at the wrong places.
- **Near misses:** each design-set forced file whose own `CONFIRMED` test passes is shifted by ±0.7, ±1, ±1.5 and
  ±2 s. Two copies of one file more than 0.5 s apart can't both be right, so a shifted copy that confirms is
  confident and wrong.
- 3d's English-only draws are reported but don't count: B is low on them at almost any offset.

**Threshold, held out by film.** The decoy films are split by a seeded draw into two halves, A and B. The threshold
is 1.25 × the largest confirmation score of half A's decoys, and every decoy-film gate is judged on half B. The
split is by film, so a film's decoys and its map are on one side only. Half B needs at least 50 decoys from at least
10 films, or 3e is unproven.

### Gates, in order (all declared before any score; any failure ends 3e)

1. **Never confident and wrong:**
   - zero `CONFIRMED` among half B's decoys;
   - zero `CONFIRMED` near-miss copies, on the design set and on the evaluation set (each `CONFIRMED` evaluation
     file is shifted the same way);
   - zero wrong in the ear-check.
2. **Robustness** on at least 10 `CONFIRMED` evaluation files:
   - dropping 20% of the cues, or jittering each cue by ±150 ms, keeps at least 90% `CONFIRMED`;
   - moving the second half of the cues by 5 s leaves at least 95% not confirmed.

   Fewer than 10 `CONFIRMED` files means unproven.
3. **Useful coverage:** at least **50%** of the evaluation set's eligible files are `CONFIRMED`. Eligible means 8 or
   more cues in the forced file itself, counted before any fit, with an English-original film. The bar is lower than
   3d's 70% because confirming a file's own timing can only cover files that already play right. The owner may raise
   it (question 1).
4. **The absolute answers**, judged once on the frozen rules:
   - Dances with Wolves: sidecar 2 `CONFIRMED`. Sidecar 48, 14 s late, **not** confirmed.
   - The Two Towers: confirmed if its `EMBEDDED` fit is within 300 ms of 0. Otherwise it must be not confirmed.
   - The Return of the King: the same rule against the answer from step 0, if one stands.
   The split Lord of the Rings editions are handled as in 3d's kit.
5. **Cost:** within 3d's budget (168 CPU-hours to map every forced-file edition). The confirmation adds only
   seconds per file.
6. **Ear-check (last, only if 1–5 pass):** a seeded 10 of the `CONFIRMED` evaluation files, every one heard right to
   about ±0.5 s, on 3d's clips (6 s before, 2 s after, the line's text). With 10 of 10 right, the error rate on
   confirmed files is below about 26% (one-sided 95%). Together with gate 1's zero confident-and-wrong over every
   decoy and near miss, that is the evidence the trust question (below) will quote.

## Build (only if 3e-M passes; each PR at most 1,500 changed lines)

This reuses 3d's build shape: a speech map stored per edition, made only for editions with a forced file, with a
trigger when an edition gains its first one. A film added after Plex is gone is mapped like any other. The pure
`SoundtrackConfirm` takes a forced file's cues, the map and the frozen threshold, and gives `CONFIRMED` or not. The
trust question, asked with 3e-M's numbers: may a `CONFIRMED` file count as proof of a forced-file choice? It would
never set an offset. The PR split is written after 3e-M.

## Decisions taken in this plan

1. **One try per file, at its own timing.** Each extra candidate gives a decoy another try. A file that plays out of
   sync stays unconfirmed, which costs coverage, never correctness.
2. **The chain is left for later**, when enough files have full references to prove it on its own sample.
3. **Thresholds held out by film**, so the decoy gate can fail.
4. **Near misses are decoys**, since a near miss is the error a one-try check is most likely to make.
5. **No Plex input or answer**, so the method still works after Plex is gone.

## Open questions for the owner

1. **The coverage bar.** The plan sets 50% of eligible files. Say if it should be higher.
2. **The Return of the King's offset.** Was it set while watching in CineFilm, or in Plex (step 0)?
