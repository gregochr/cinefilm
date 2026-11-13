# Riding out a train's bad spells — plan

Written 2026-11-12. Phase 3 of `network-resilience-review.md` (items 5 and 6), set from the first train viewing in the
playback-health record and reviewed adversarially against the code and the hls.js 1.7.4 source before any code.
Four small pull requests: **A** a 240p floor for every film; **B** the player follows what is being *fetched*, and
the record shows the bank; **C** a buffer of up to eight minutes; **D** the floor held back while the buffer is deep.
A and B are independent. C needs B. D needs all three.

## What the first train record showed

*The Rock*, Chrome on the Mac, LNER's Wi-Fi (Icomera), 2026-11-12, one sitting in two records. 17:23: 32 minutes
watched, 4 stalls, 48 s. 17:56 (`playbackId` `323111c2-c961-49d9-8665-6fd88ae56f3a`): 105.5 minutes watched,
**155 stalls, 33.5 minutes**, the longest 3m 12s; no failed request, 12 timeouts, no reconnect, no recovery; 71% of
the film at 360p and 29% at 480p; 39 steps down and 38 up.

- **The stalls sit in six spells of 3 to 12 minutes** (18:01–18:04, 18:08–18:20, 18:23–18:34, 18:48–18:58,
  19:17–19:22, 19:36–19:48): 33.1 of the 33.5 minutes. Inside a spell the film waited about 60% of the time and
  stopped about three times a minute.
- **In a spell the link delivered about 0.3 Mbit/s**, 38% of the 0.75 Mbit/s that 360p needs on the wire. (In a
  minute that stalled the buffer was empty, so what played is what arrived: 19.7 minutes of 360p in 52 minutes.)
- **Between spells the link ran at 2 to 4.5 Mbit/s for 4 to 19 minutes** with no stall, the 2-minute buffer sat
  full, and the link was idle most of the time.
- **The server kept up.** The stalls line up with the minutes whose own transfer speed was low.
- The card's "typically 2.3 Mbit/s" describes the good stretches: most segments are fetched there, and a sample
  times a segment from its first byte, so the wait before it is left out. In a stalled minute the film really
  arrived at about half the sampled speed.
- A 2m 42s stop at 19:48 was recorded as a pause, not a stall, as intended.

## Owner decisions (2026-11-12)

1. **A 240p floor for every film: yes.** (The review's open decision 1.)
2. **Bank about eight minutes on the slow rungs, and keep what is banked when the link recovers: yes.** (Its open
   decision 2.) The buffer lives in the browser tab's memory; it is gone when the player is closed.

## What a replay of that viewing estimates

The viewing was replayed segment by segment under other rules, in a throwaway script. The link is one figure per
minute: what arrived in a stalled minute, and the sampled speed less a wait before each segment in a clean one. The
player is 4 s segments, hls.js's speed estimate (two moving averages, the lower of them) and its two factors (0.95
to keep a level, 0.7 to take a higher one, against the level's average bitrate), the step gate and the buffer's
depth.

| Rules | Stalled | Film watched at 240p |
|---|---|---|
| Today's rules, replayed (the record itself: 33.5 minutes) | 24 min | none |
| 360p floor, 8-minute buffer | 15 min | none |
| 240p floor, 2-minute buffer | 5 min | 62% |
| 240p floor, 8-minute buffer (A, B and C) | none | 56% |
| The same with the floor held while the buffer is deep (A to D) | none | 32% |

**Read it as an order, not as figures.** The replay under-predicts today's stalling by about 30%, it is one
journey, and it knows the link only minute by minute. What holds up when the model is changed (a lower floor rate,
other thresholds, the reviewer's own variants): the floor is the larger lever, because the bad spells are longer
than any buffer filled in the 3 or 4 good minutes between two of them; the deeper buffer covers the spells that
follow a long good stretch; with both there is nothing left to stall on. What does not hold up as a figure: D's
share of 240p, and the choice of 420 and 240 s over their neighbours, which differ by less than the model's error.

Two things the replay made plain. Without D more than half the film is watched at 240p: everything fetched during a
spell is fetched at the floor and watched later. And the step gate must follow the level being fetched (B): if it
went on following the level being played, the 240p share rises to 61% without D and 42% with it.

## A. A 240p floor for every film (backend)

Today the lowest rung of a film above 360 lines is 360p: 600 kbit/s of video and 96 of audio, about 750 kbit/s on
the wire. 240p exists only for sources of 360 lines or fewer, at 400 kbit/s.

- `Ladder`: every source **above 240 lines** has a `240p` rung below its others, at **250 kbit/s** of video (the
  `sourceHeight > 360` exception goes). A film of 240 lines or fewer that cannot be copied keeps its single rung at
  its own height and at 400 kbit/s, as now; so does one of 241 lines, which is encoded at 240. Heights stay unique
  within a film.
- `Rung`: a rung of 250 kbit/s or less carries AAC stereo at **64 kbit/s**; 360p (and the 400 kbit/s 240p) stay at
  96 and the rest at 128. The floor is therefore 314 kbit/s on average (`AVERAGE-BANDWIDTH`), 364 at its peak
  (`BANDWIDTH`) and about 340 on the wire: a little under half of 360p.
- **It is not enough alone.** A spell averaged 0.3 Mbit/s, so at the floor the film still falls a little behind in
  the worst minutes; the bank (C) covers the difference. A lower rate reaches further and looks worse; the record
  decides whether 250 is right.
- Nothing else changes in code, but these follow and are pinned by tests:
  - One more scale and encode in every Auto session (a pinned quality still encodes only its rung).
  - `StartRung` falls to the lowest rung, so a measured speed under 1,020 kbit/s (360p's peak × 1.25) starts at
    240p. So does a probe that measured nothing, and a split film with no speed measured in the last ten minutes:
    **a known cost at home**, where such a film now starts one rung lower and needs four one-minute steps to reach
    the top, not three.
  - The Quality menu and Film detail list 240p for every film: they show the film's ladder. Preferences, the health
    record, the slow-connection card and the indicator already handle the id.
  - The iPhone's native player reads the same master playlist, so it gets the floor too.
- **Not checkable here, for the owner after the release:** the i965 encoder's picture at 250 kbit/s; the encoder's
  speed with one more rung (Admin, Transcoding; the whole transcoded ladder measured about 3.5× in
  `transcoding-plan.md`); AAC at 64 kbit/s; and whether real 240p segments keep to the declared peak, since the
  rungs set `-b:v` only. `TranscodeRealToolsTest` (CI) is the only test that encodes the rung.

## B. Follow what is being fetched, and record the bank (frontend, one backend rule)

**The step gate.** hls.js reports a change of level twice: when it starts *fetching* another level, and later when
the playhead reaches it (`LEVEL_SWITCHED`, which is what the engine listens to today). The gate's cap therefore
follows a step down only when the lower level is *played*: up to two minutes late today, and up to eight with C,
during which it goes on raising the cap, and after which it holds a recovered link at the low level.

- The engine feeds `gate.switched` from the level being fetched (`LEVEL_SWITCHING`, or the level of each loaded
  segment if the source shows that event is not raised for hls.js's own choices: the builder reads the 1.7.4 source
  first and says which). Toasts, the indicator and the record's played rung stay on `LEVEL_SWITCHED`: they describe
  what is on screen.
- A new handler tells the caller the rung being fetched, for the record.

**The record.** Whether the bank fills, how deep it is before a spell, and what the player was fetching at the time
must show in the next journey's record.

- `health.beat(positionMs, aheadS)`: the player passes the seconds buffered ahead of the playhead
  (`bufferAhead(video)`). The figure is taken on every call that carries a number, before the "position has not
  moved" return, and a minute keeps its last one, in whole seconds.
- `health.fetching(rungId)`: the rung hls.js is fetching. The iPhone's player and direct play never call it.
- `minutes` rows gain two figures: `[minute, median kbit/s, rung played, ms moved, ms stalled, seconds ahead, rung
  fetched]`. Seconds ahead is 0 for a minute with no heartbeat (the film did not move in it: read it with the
  moved and stalled figures). The fetched rung uses the played rung's codes and is 0 when unknown; it is the one
  being fetched when time was last counted or a speed was last sampled in that minute.
- `HealthRules` accepts a row of five or seven figures, each within the bounds every figure has, both rung codes at
  most 6. So a tab still running the older player is not refused and stored records stay readable. A new tab
  against an older server (a rollback) is refused and that viewing's record is lost. 360 seven-figure rows fit the
  16,000-character store.
- The end-of-minute figure is enough: the bank falls at most 60 s in a minute, and the fetched rung says when the
  floor was in use.

## C. Bank up to eight minutes (frontend; after B)

hls.js keeps `min(max(8 × maxBufferSize / the level's BANDWIDTH, maxBufferLength), maxMaxBufferLength)` seconds
ahead (confirmed in the 1.7.4 source; it is the peak figure). `hlsConfig` becomes `maxBufferLength` 120
(unchanged), `maxMaxBufferLength` **480** and `maxBufferSize` **100 MB** (down from 250, which only mattered above
the 120 s floor), so depth follows bytes:

| Rung | Seconds ahead | About |
|---|---|---|
| 240p, 360p, 480p | 480 | 19, 42 and 68 MB |
| 720p | 256 | 84 MB |
| 1080p (transcoded) | 130 | 82 MB |
| Original | 120 for a film averaging 4.4 Mbit/s or more; deeper for a lighter one, up to 480 under about 1 Mbit/s | at most 100 MB at the peak rate |

- **The server already allows it** (`SessionManager`): the encoder is stopped when it is 3 minutes past the last
  segment *asked for*, not past the playhead, so it keeps ahead of the banked edge.
- **Accepted:** segments more than 2 minutes behind the last one asked for are deleted, which is now up to 6 minutes
  ahead of the playhead. A player that loses its buffer asks for them again and the encoder restarts there, which
  also discards what it had made beyond: a media-error recovery, a rebuilt session, a seek back of more than the
  30 s back buffer (which restarts the encoder today as well), or the browser evicting buffered film. The cost is a
  few seconds of encoder time and fetching again what the player no longer had.
- **A full buffer** (confirmed in the source): hls.js first evicts the back buffer and tries again, then lowers its
  own `maxMaxBufferLength` for that playback and carries on; it flushes ahead only when the playhead itself is not
  buffered. Chrome is reported to cap a tab's video buffer at about 150 MB; 100 MB at the peak rate is inside it.
  Unverified in Chrome, Safari and WebKit.
- **Cost:** a viewer who leaves has fetched up to eight minutes they did not watch, at most about 70 MB at 480p. The
  iPhone's player manages its own buffer, so only A helps there.

## D. Hold the floor back while the buffer is deep (frontend; after A, B and C)

hls.js steps down when its speed estimate falls, whatever is banked. With A to C it drops to 240p at the start of
every spell, with eight minutes in hand that could have paid for 360p. D spends the bank first.

- A pure module beside `stepGate.js`: armed when the buffer ahead reaches **420 s**, released when it falls under
  **240 s**.
- It applies in Auto only, and only to a ladder whose lowest level is `240p` with a level named `360p` above it. So
  a small film whose only other level is its copied original is never held (an original's bitrate is whatever the
  file has); one whose top is a 360p transcode at its own height is, at the same 600 kbit/s as any 360p. While
  armed, and while the step gate's cap is at or above 360p, the engine sets hls.js's `config.minAutoBitrate` to the
  360p level's bitrate; otherwise it is 0.
- Re-evaluated on `timeupdate`, `waiting` and `seeking`, when a segment has loaded, and whenever the cap changes:
  not only on a loaded segment, which never comes in a dead zone.
- What hls.js then does (confirmed in the source): the minimum is read on every choice; no automatic path picks
  below it, including the rule that abandons a slow segment and the switch after a failed one. It is a floor, not a
  pull: a player already at 240p when the hold arms stays there until the link can carry 360p.
- The step gate is unchanged.
- Whether 420 and 240 are right is judged from the next journey's record: the bank and the fetched rung in each
  minute.

## Not in this phase, and why

- **Step up only with a reserve banked** (review, item 6): the replay shows no gain over the 60 s rule once the
  floor and the deep buffer exist, because the low rungs bank quickly.
- **Rules that read the train tag** (a quality ceiling on a train, starting low on a remembered train network): A
  to D are driven by the link and do nothing at home, and the tag arrives seconds after the film starts. Decide from
  a journey recorded with A to D in place.
- **Waiting longer before resuming after a stall:** it changes how the player behaves; the owner's call.
- **Fetching banked film again at a better quality:** hls.js can only do it by throwing the whole buffer away.
- **Keeping segments on the server for longer behind the player**, and counting media-error recoveries in the
  record: worth doing if the record shows buffers being lost.
- **Download for offline:** the only answer to the minutes when nothing arrives (two in this viewing); a later slice.
- A per-minute strip on Admin's Playback health card, and the BBR trial on the host.

## How each is proved

- **A:** `LadderTest` for every class of source height (unique heights; the floor present above 240 lines and
  absent at or below; the 400 kbit/s single rung kept), `RungTest` (64, 96 and 128 kbit/s audio; average and peak),
  `StartRungTest` at each boundary, the master playlist's lines, the ffmpeg command for a five-rung ladder, the film
  detail's quality list, and `TranscodeRealToolsTest` extended to the 240p segments (CI only). Existing tests that
  name ladders change with it.
- **B:** the engine with the stand-in hls.js (`src/test/fakeHls.js`): the cap follows a fetched step down at once
  and ignores the later played one; the new handler; `health.js` beat by beat with exact figures (the last heartbeat
  of a minute wins, a minute without one is 0, a figure that is not a number is ignored, the fetched rung in a
  minute that only sampled a speed); the player passing both; `HealthRulesTest` for rows of four, five, six, seven
  and eight figures and a seventh above 6; the controller storing and returning a seven-figure row.
- **C:** the three `hlsConfig` values.
- **D:** the module at 419/420 and 240/239 s; the engine: sets and clears `minAutoBitrate`, clears it when the cap
  falls under 360p and when the buffer drains with no segment loading, nothing for a pinned quality or a two-rung
  ladder.
- Every PR: the gates in `CLAUDE.md`, one local Codex run on the whole branch before the first push
  (`dev-and-testing.md`), CI, Codex on the head commit. One mutation run per PR at the end, on the changed files.
- **By hand, the owner, after a release** (`dev-and-testing.md`, "Poor-network check"): 240p pinned looks
  acceptable; unthrottled at 360p pinned the scrubber shows about eight minutes buffered after a few minutes; then
  on "Train, bad" Auto stays above 240p until under four minutes are buffered, and plays on at 240p. From a cold
  start on "Train, bad" (300 kbit/s) some stalling is expected: the floor needs about 340.
- **The real proof is the next train journey's record.**

## What building changed (2026-11-13)

- **A** (#123): a source of 241 lines that cannot be copied is encoded at 240, so it keeps the single 400 kbit/s
  rung like a 240-line one. CI's real-ffmpeg test now encodes the floor in software and checks every 240p segment.
- **B** (#124): hls.js raises `LEVEL_SWITCHING` for its own choices as well as manual ones (read in
  `level-controller.ts`), so the gate uses that event and not each loaded segment's level. Codex found that the
  fetched rung outlived a fall back to direct play: the counter now forgets it when the engine becomes anything
  other than hls.js (and keeps it when hls.js is reported, which happens after hls.js may have named its first
  level). `foundation-plan.md` describes both row lengths.
- **C** (#125): the three numbers are named constants, and a test writes hls.js's formula out to pin the depth on
  each level. What a full buffer does was read again in the source: the back buffer is evicted first, then
  `maxMaxBufferLength` is lowered for that playback; what is ahead is flushed only when the playhead is not buffered.
- **D**: the hold is to a level *named* 360p, so a small film whose top is a 360p transcode at its own height is
  held too (the same 600 kbit/s), and one whose only other level is its original is not. hls.js compares the
  minimum with a level's `maxBitrate`, which is its declared bitrate unless a measured one is higher.

## What nobody has verified

The encoder at 240p (picture, speed, real bitrate, AAC at 64 kbit/s); the browsers' buffer limits and whether WebKit
evicts ahead of the playhead; Safari on the Mac (hls.js) and the iPhone; whether the train's Wi-Fi rations data; a
real train with any of this in place.
