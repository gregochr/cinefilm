# Step up the bank — plan

Owner, 2026-11-23: "240 is the absolute floor. 360 is acceptable. 480 would be ideal. 720 would be a champagne and
cigar moment", and the bank should climb "iteratively … from 240 to 360 … then 480 and then 720". Their standing
order: no stall first, quality second ("I'd prefer 240p and zero buffering"). Follows #302 (the 360p floor held by
time, `floor-hold-by-time-plan.md`). Two PRs, each well under 1,500 lines; A first, B only after A's run.

**The principle** (owner, 2026-11-23: "we're always optimising the bank"): an idle link is wasted. While the link
has anything to give, it goes into the bank: first depth (up to 480 s), then quality, one rung at a time, and later
the line ahead (train position) decides when to spend it. Depth always comes before quality, and a bank that might
stall always falls back to the lowest rung.

## Evidence (23 Nov train run, The Rock, `~/cinefilm-viewer/runs/20261123-062648`)

- 0 stalls in 2 h 16 min. 240p 1,656 s, 360p 3,109 s, 480p 2,973 s, 720p 452 s.
- Six times Auto stepped up to 720p with a deep bank, at the link's best (minute medians 4.2–4.5 Mbit/s; LNER caps
  a device at 5 Mbit/s). hls.js stays idle while the bank is over its target for the level being fetched
  (`stream-controller.ts:318-323`; target = 8 × 100 MB / peak bitrate, 120–480 s, `base-stream-controller.ts:1480`),
  which is 256 s at 720p. So nothing was fetched for up to 225 s while the bank fell to 256 s (1,250 s of idle link
  in all), then 720p came at about playback speed, then the player stepped down and refilled mostly at 240p or
  360p. Every 240p spell began on the first fetches after such an idle.
- 240p was fetched with 3.2 Mbit/s and a full bank (08:31): once low, Auto climbs one rung a minute (`stepGate.js`)
  and the banked low-rung film plays out for up to 8 minutes.

Peak rates (`Rung.peakBandwidth()`): 240p 364k, 360p 816k, 480p 1.328M, 720p 3.128M, 1080p transcode about 6.1M.
hls.js's target: 480 s up to 480p (480p's 480 s is about 80 MB), 256 s at 720p, about 130 s at 1080p. So 480p is the
highest rung with no cost in depth.

## Floors and ceilings: one writer

Today `reevaluateHold` writes `minAutoBitrate` and `cap()` writes `autoLevelCapping`. After these PRs there are two
floors (#302's hold, B's re-bank) and two ceilings (the step gate, A's champagne rule). One function each:
`minAutoBitrate = max(holdFloor, rebankFloor)`, and the cap = min(step gate, champagne ceiling). Nothing else writes
either, and both are re-evaluated on `FRAG_LOADED`, on the hold timer, and in `cap()`, whatever the ladder.

## PR A — 720p is the champagne rung

Above 480p the budget cannot hold 480 s, so a depth test cannot gate it (hls.js would not fetch until the bank was
under 256 s, the test would fail, and the cap would flip). The test is **headroom**: the lower middle of the last 9
fragment rates **timed from the first byte** (`fragmentKbps`, so an on-demand encoder's wait, `SessionManager`
`SegmentNotReadyException`, does not count against the link; fragments that needed a retry are left out) against
the rung's **average** bitrate (the variant's `AVERAGE-BANDWIDTH`, hls.js `level.averageBitrate`; if
`PlaylistWriter` does not write it, this PR adds it). The peak is wrong here: a copy rung's peak is average × 1.5 +
128k (`Rung.java`), so a 15 Mbit/s original would need 68 Mbit/s.

- step up to a rung above 480p only at **3 ×** its average or more, and only when the bank is full for the rung
  stepped *from*: hls.js's own target, `min(max(8 × maxBufferSize / level.maxBitrate, 120), 480)` minus 30 s
  (`base-stream-controller.ts:1480-1491`, with hls.js's `maxBitrate`, not the declared peak);
- stay on it only at **2 ×** or more (hysteresis); never with fragment trouble since the last load (`troubled`);
- the ceiling is the **highest** rung above 480p whose test passes, else the 480p level (or the ladder's top, if
  lower). Manual quality is untouched.

The start: `gate.start(data.firstLevel)` (`hlsjsEngine.js:257`) is clamped to the ceiling too, since the playlist
lists the session's start rung first and a remembered fast speed could start at 720p with an empty bank.

Effect: on 23 Nov 4.2–4.5 Mbit/s is under 3 × 720p's average (about 2.6 Mbit/s, so 7.9), so none of the six 720p
climbs happen and the bank stays near 480 s at 480p. At home, a 1080p transcode (average about 5.1 Mbit/s) needs
15.4 Mbit/s to step up, and a 12–15 Mbit/s copy rung needs 36–45 Mbit/s, so a 55 Mbit/s home link climbs to the top
as now. A dip (a neighbour's microwave on 2.4 GHz) lowers the ceiling only to the highest rung that still passes,
and only once most of the 9 samples have dropped; lowering `autoLevelCapping` flushes nothing
(`level-controller.ts:755-767`), and the floor is never above 360p, so hls.js's emergency down-switch still works
at 1080p.

Tests: 3 × and 2 × at their boundaries; trouble; retried fragments ignored; first-byte timing; the start clamp; the
ceiling falling to the highest passing rung, not straight to 480p; the ceiling on a ladder with no 240p bottom and
one with no 480p rung; Manual unaffected; a home-speed run reaching a 1080p transcode and a 15 Mbit/s copy rung.
The pane spike logs the rates of a step up to a transcoded rung, to see the encoder's effect.

## PR B — replace part of the bank one rung up (only if A's run shows it is worth it)

With A, new fetches already climb a rung a minute while the link allows (the step gate), so the bank turns over
to the better rung as it plays; what B adds is seeing it about three minutes sooner. B never takes the bank below
300 s, and it goes no higher than 480p.

**When (all must hold):** the bank at least 470 s; nothing in flight (after `FRAG_BUFFERED`, `inFlight` null);
every fragment from playhead + 300 s onward at one rung N below 480p, read from the engine's own list of
`{start, end, level}` kept from `FRAG_BUFFERED` and pruned against `video.buffered` on `BUFFER_FLUSHED` (hls.js's
fragment tracker is not public, `hls.ts:222`); the step gate's cap already at N + 1 or above; headroom 3 × N + 1's
peak; no trouble, step down or re-bank in the last 120 s.

**What:** set `rebankFloor` to N + 1's bitrate, then trigger `BUFFER_FLUSHING` from the start of the first fragment
at or after playhead + 300 s to infinity. The 300 s in front of the playhead is never touched: one stuck fragment
(120 s, `maxLoadTimeMs`) leaves 180 s, #302's release line. At 3 × headroom the 180 s comes back in about a minute.

**Undo:** `rebankFloor` goes to 0 first, and a 10-minute cool-down starts, on: the bank under 270 s, trouble, #302's
hold releasing, a seek, a restart or `startLoad`, a `waiting` stall, a new network generation, destroy. The raised
floor stops hls.js's emergency down-switch (`abr-controller.ts:264-273`), so it is never left on at low depth.

**First, a spike in the browser pane** (real hls.js, `player-harness-real-hlsjs` memory): a `BUFFER_FLUSHING` from
playhead + 300 s makes hls.js fetch the gap again at the raised floor's rung, with no stall or seek. hls.js's own
`nextLevel` is not usable (a manual level, and it keeps about one fragment, `scheduleTrackSwitch`). If the spike
fails, or A's run shows little 240p/360p left with a full bank, B is dropped.

Tests: each condition at its boundary; mixed rungs past +300 s; a fragment in flight blocks it; the flush start on
a fragment boundary; the floor set before the flush; every undo path; the cool-downs; never above 480p; no re-bank
under Manual.

## Not in either PR

Train position look-ahead (needs the third journey, 26 Nov, and a live read of the train's feed). Raising the
100 MB budget. Each PR needs a release before the run that tests it.
