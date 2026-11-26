# Forced subtitles: find the files at the wrong speed from the soundtrack — plan

Status: plan, 2026-11-26, written at the owner's request ("Yes, do that") after 3e ended unproven
(`soundtrack-confirm-plan.md`, "Result: 3e ends"). Measure first (planner and owner, no PR). Build only if every
gate passes.

## Why

Since v0.11.5 the server retimes each OpenSubtitles file by the frame rate it states
(`subtitle-frame-rate-plan.md`). Where that rate is wrong, the file still drifts: it starts near sync and ends
minutes out. The owner sets no rate (owner, 2026-11-25, and again 2026-11-26: "3e-R only, no control"). So the
only way such a file is found is from the film itself.

3e tried to confirm a file to 300 ms from the speech maps and could not. This asks a much coarser question that the
maps do answer: **does the file fit the speech far better about 4% faster or slower than it plays now?** A file 4%
off is minutes out by the end of a film, so the gap is large where it exists.

It covers forced files only: their cues lie on foreign speech, which the maps label. Full subtitles are not
covered.

## The measure (frozen now)

For a forced file with 8 or more cues, its cues as the server plays them (original cues × `retimed_by`, or × 1),
its offset c and its film's `tiny` speech map (3d's kit, `soundfit.py`):

- For k in {1, 23.976/25, 25/23.976}, a cue at t is placed at k·t + c. The k ≠ 1 tries are the one-step slip
  between a 25 fps and a 23.976 fps release, either way.
- **f(k)** is the share of the cues' time over speech labelled foreign, at the offset in c ± 2 s (100 ms steps) where
  foreign minus English speech under the cues is highest.
- k̂ is the k ≠ 1 with the larger f. **The file is flagged as at the wrong speed by k̂** when f(k̂) ≥ 0.20 and
  f(k̂) − f(1) ≥ 0.12. Otherwise it is not flagged.

Nothing else is tuned. The labels are the kit's `LABEL_DEFAULTS`.

## What the design data shows (half A, planner, 2026-11-26)

The two numbers were set on this data, so it proves nothing on its own. 26 half-A files have 8 or more cues
(`pin-tiny.json`; scratch script `d1.py` on the kit).

- **The 9 files the server has since retimed, on their cues from before the retime.** The measure flags 5, each in
  the direction the server applied: Hitman (f 0.00 to 0.49), Jack Ryan (0.03 to 0.41), Inferno (0.08 to 0.46),
  Captain America (0.01 to 0.23) and Severance (0.08 to 0.28). It misses 1917 (0.03 to 0.09). A Brilliant Young
  Mind, Batman v Superman and A Most Wanted Man show little or no foreign speech under their cues at any k (f at
  most 0.05), so they cannot be flagged either way.
- **The 9 retimed files as played:** none is flagged.
- **The 17 files not retimed (they play as they always have):** 2 are flagged, Bridge of Spies (0.03 to 0.25) and
  The Brutalist (0.16 to 0.31). They are the two files that the 25 Nov check found drifting and the server did not
  retime. Among the other 15, the largest gain at k̂ is 0.02.

## Proof (all before any build; any failure ends this plan)

### Step 1: ear checks (owner)

On design files, to learn whether a flag means what it says:

1. **Bridge of Spies**, late: "Right breast." plays at 2:01:41. If the file is 4% slow, the line is spoken about
   5 minutes earlier, near 1:56:50.
2. **The Brutalist**, late: "I love you." plays at 2:53:05 and 2:53:23. If the file is 4% slow, they are spoken
   near 2:46:10. The film has an intermission, so a cut the file was not made for is the other possible cause.
3. **A Most Wanted Man**, late (not flagged; the 3e-R review saw a weaker whole-file fit after its retime): "I'm
   stuck in traffic." plays at 1:45:02.

Each answer is recorded here. Files 1 and 2 pass only if the line is heard near its 4% position (1:56:50,
2:46:10). If either plays right as it is, or is wrong but not near that position (another cause, such as a
different cut), the flag is wrong on design data and this plan ends. File 3 is reported, not judged.

### Step 2: the held-out set (half B and the evaluation list)

Half B was never scored by 3e or by the 25 Nov check. 34 of its forced files have maps on the server already (the
kit's `run2/maps`). Half A gave only 2 flags as played in 26 files, so half B alone is likely to give too few for
G2. The held-out set is therefore **half B and the forced films of 3e's 80-file evaluation list** (unspent; 3e has
ended), fixed now, before any score. The owner maps the list's films (3d's kit, nice 19) and runs one read-only
query for every held-out file's `retimed_by` and stated rate. Then, once, on the held-out files with 8 or more cues:

- **G1, coverage of known slips.** The set is every held-out file the server retimed (8 or more cues). On its cues
  from before the retime, the measure must flag at least 50% of the set, and every flag must be in the direction the
  server applied. (Half A: 5 of 9, 56%, all in the server's direction. The other 4 lie on little foreign speech.)
  This is a coverage bar, not a precision bar: a file the measure cannot see is a miss, never a pass.
- **G2, no false flags.** Every held-out file flagged as played is ear-checked by the owner at its last cues, as in
  step 1. A flag is right only if the line is heard near its 4% position in k̂'s direction. At most 1 in 10 flags
  may be wrong (with fewer than 10 flags, none): heard to play right, out for another reason, or out the other way.
  A flag on a file the server retimed is checked the same way. This is the risk that matters: a wrong flag would put
  a file minutes out.
- **G1 trusts the server's retime.** Its stated rate is right for most files but not all, so a G1 miss or hit may
  rest on a wrong rate. G1 measures coverage only; whether a flag can be acted on is judged by ear in G2. No file is
  dropped from G1's set after the scores are seen.
- **G3, noise.** Half B's own decoys (cross-film and shuffled cues, built by the kit's `build_decoys` and never
  scored) are measured with their cues as the source file plays them, on the decoy's map. At most 5% may be
  flagged. (Half A's 72 decoys: 62 have 8 or more cues, and none is flagged; the largest f at k̂ is 0.19. Scratch
  script `g4a.py`.)
- **Enough files.** G1 needs at least 5 files in its set, G2 at least 3 flags (step 1's two files don't count:
  they are design data). If the held-out set gives fewer, the plan ends unproven.

The numbers and the files heard are recorded here.

## Build (only if step 2 passes; each PR at most 1,500 changed lines)

Outlined only; the split is written after the proof:

- the `tiny` speech map stored per edition with a forced download (3d's job shape, nice 19);
- the measure as a pure class, tested on the design numbers above;
- a flag sets the download's rate (`owner_timed_fps`, kept for an automatic rate in `subtitle-frame-rate-plan.md`)
  and the existing retime applies it; the Admin says what was done.

**The trust question, asked with the numbers before any build:** may a flag retime a file with no one listening?
A wrong flag puts a right file minutes out.

## Decisions taken in this plan

1. **Three tries, as played.** The server's stated-rate retime is right for most files, so the measure looks only
   for the one-step slip left over. The smaller 24 / 23.976 slip (0.1%) is not tried: the maps cannot see it.
2. **Forced files only.** Their cues lie on foreign speech, which is what the maps label.
3. **Heard, not assumed.** A flag as played is a claim that a file plays wrongly today, so G2 is judged by ear.
4. **No Plex input or answer** (`no-plex-baselines`).

## Open questions for the owner

1. Asked with step 2's numbers: may a flag retime a file automatically, or only be shown in the Admin?
