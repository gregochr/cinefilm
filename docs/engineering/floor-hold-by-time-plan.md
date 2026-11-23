# Floor hold by time — plan

Owner, 2026-11-23: "First lets try to get rid of the 240p", then "I'd prefer 240p and zero buffering though …
That's better than 480p and even occasional buffering." So the order is fixed: **no stall first, then no 240p.**
240p stays as the last resort; it goes only where the bank can afford 360p.

## What the 23 Nov train run showed

Headless run, The Rock, 87 min, Darlington to past Grantham (`~/cinefilm-viewer/runs/20261123-062648`, not in
git). 0 stalls. 240p played for 15 min 22 s (184 of 1,045 five-second samples), always with 250 s or more in hand
(median 450 s). The health record's minutes (minute, kbps, rung playing, moved, stalled, ahead, rung fetched)
show where 240p was *fetched*:

| Film minutes | Fetched | Measured kbit/s (minute medians) | In hand | Why the hold did not stop it |
|---|---|---|---|---|
| 4 | 240p | 4,143 | 317 s | The hold had never armed: the bank had not yet reached 420 s |
| 40–46 | 240p | 642, 915, 2,001, 1,196, 775, 480, 1,870 | 227–335 s | Released at 227 s (< 240 s) |

The second case is the rules working against each other: fetching 720p caps the bank at about 256 s (the 100 MB
budget, `hlsjsEngine.js`), so on minutes 32–38 the bank sat at 242–257 s, right on the 240 s release line. When the
link slowed, the hold was already off.

## The hold's cost (why it must let go in time)

While the floor is on, hls.js 1.7.4 cannot emergency-abort a slow 360p fragment down to 240p:
`_abandonRulesCheck` returns once the loading level is at or below `minAutoLevel` (`abr-controller.ts:264-273`), and
its timer starts only on FRAG_LOADING (`:166`), so lowering the floor mid-fragment does not rescue that fragment.
The worst one fragment can cost is `maxLoadTimeMs`, 120 s (`hlsjsEngine.js` `fragLoadPolicy`), after which it
times out and `troubled` drops the hold. So the hold must always let go with **more than 120 s in hand**.

## The change

`floorHold.js` decides by **how long the bank would last at 360p**, as well as by the seconds in hand.

- `lastsS(kbps) = aheadS / (1 − kbps / rate360)` when `kbps < rate360`, otherwise unbounded. `rate360` is the 360p
  level's `bitrate` from hls.js: the variant's BANDWIDTH, the **peak** (`Rung.peakBandwidth()`: 600k × 1.2 + 96k
  audio = 816 kbit/s). Tests take it from that formula, not a round number.
- **Samples:** the engine keeps its own list of the last 9 fragment rates, each timed from `loading.start` (so the
  time to first byte counts; `fragmentKbps` times from `loading.first` and overstates a high-latency link). The
  list is cleared when `troubled` is set and when the network generation changes, as `useStream.js` treats its
  samples.
- **In flight:** while a fragment loads, its rate so far (`loaded × 8 / (now − loading.start)`, 0 before the first
  byte) counts for release, re-checked every 2 s on a timer, not only on hls.js events. A link that goes quiet then
  releases the hold within seconds instead of keeping the last good samples.
- **Arm** when `aheadS ≥ 240` **and** `lastsS(lowerMiddle of the 9) ≥ 900`.
- **Release** when any of: `aheadS < 180` (120 s for one stuck fragment, plus a minute); `lastsS(min of the last 3
  samples and the in-flight rate) < 600`; fragment trouble (as now). 180 s, not today's 240 s, because 240 s is
  what released the hold at minute 40 on 23 Nov.
- Everything else stays: only a ladder with a 240p bottom is held, only once hls.js fetches at 360p or above, the
  floor set through `minAutoBitrate`.

Check against the run at 816 kbit/s, with the minute medians (fragment-level variation is not in the record, so
this is a sketch, not proof): minutes 32–39 arm (240 s or more in hand, rates over 2,600, unbounded); minute 40
(227 s, 642) lasts about 1,060 s and stays above 180 s; minute 44 (335 s, 775) about 6,700 s; minute 45 (327 s,
480) about 790 s. So the hold would have stayed on through minutes 40–46 unless single fragments ran well under
the minute's median (one at 300 kbit/s with 227 s in hand gives about 360 s and releases, as it should). Minute 4
(317 s, 4,143): arms. The real test is the headless run on the 26 Nov journey.

## Files and tests (one PR, well under 1,500 lines)

- `frontend/src/player/floorHold.js`: the pure rule, `update({ aheadS, armKbps, releaseKbps, rateKbps })` with the
  constants exported (`ARM_LASTS_S` 900, `RELEASE_LASTS_S` 600, `ARM_MIN_AHEAD_S` 240, `RELEASE_MIN_AHEAD_S` 180).
- `frontend/src/player/hlsjsEngine.js`: the sample list, the in-flight rate and its 2 s timer (cleared on destroy),
  the inputs to `update`.
- `floorHold.test.js`: each boundary; no samples; `kbps` equal to the rate; NaN inputs; a made-up sequence shaped
  like minutes 32–48 that stays armed; stale fast samples at `aheadS` 181 and 179.
- `hlsjsEngine.test.js`: the floor raised and dropped through `minAutoBitrate`; no new samples while `aheadS` drains
  from 300 s (released by the in-flight rate or by 180 s); a trickling 360p fragment in flight releases within one
  timer tick; samples cleared on trouble and on a new network generation; the timer stopped on destroy. All at
  816k. The existing hold tests updated, not deleted.
- `docs/engineering/transcoding-plan.md` line ~171: the rule's words.
- `changelog.d/` entry.

## Not in this change

The 720p byte budget pulling the bank down to 256 s; refetching banked low-rung film at a higher rung (the
owner's idea, a later iteration); a 480p floor.
