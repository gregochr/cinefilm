# Stuck on a rung — plan

Owner, on the first train run after `step-up-bank-plan.md` was released: "its said 240p throughout" and "i thought
our buffering would give us a better quality". The standing order is unchanged: **no stall first, quality
second.** The run kept the first half of that order and broke the second. One PR, well under 1,500 lines.

## What the run showed

Auto on v0.11.5, 97 minutes watched, from the viewing's record (Admin, Playback health; a minute row is minute,
kbit/s, rung on screen, moved, stalled, seconds ahead, rung fetched).

- **No stall:** 3 stalls, 0.28 s in all. The bank carried two dead spells of about ten minutes (minute medians 0
  to 550 kbit/s) and ran down to 90 s in the second.
- **240p on screen for 94.1 of the 96.9 minutes** (360p 148 s, 480p 19 s). The rung fetched was 240p in 97 of the
  98 minute rows: 360p was fetched in minute 1, the link fell away in minutes 2 and 3 (121 kbit/s), and nothing
  above 240p was fetched again.
- **The link was often good.** 57 of the 98 minute medians were 994 kbit/s or more, which is what hls.js asks of
  its estimate before a step up to 360p from a deep bank (360p's average, 696 kbit/s, over 0.7); 21 were 2,448 or
  more (the re-bank's three times 360p's peak). The bank was 470 s or more in 35 minutes, and for eleven minutes
  in a row (33–43) it sat full at 1.3 to 4.5 Mbit/s.
- **Film was thrown away for nothing.** Four times the bank fell by far more than the 60 s a minute of playback
  takes, in a good minute, and landed at about the 300 s the re-bank keeps:

| Minute | Seconds ahead, minute before → this minute | Median kbit/s |
|---|---|---|
| 14 | 450 → 333 | 2,382 |
| 20 | 402 → 302 | 3,040 |
| 29 | 483 → 286 | 1,945 |
| 68 | 482 → 306 | 1,661 |

  No 360p was on screen after any of them. The last came just before the second dead spell: without it the bank
  would have bottomed near 270 s, not 90 s.
- 13 fragment timeouts; no reload, reconnect or recovery.

## The cause

Four facts in hls.js 1.7.4 (`frontend/node_modules/hls.js/src`), one on the server, and two rules of ours.

1. **A failed fragment marks its level.** `controller/error-controller.ts:307-352`
   (`getFragRetryOrSwitchAction`): a fragment timeout, an error with no answer or a 503 raises
   `level.fragmentError`, and while retries remain that is all: `loadError` and `loadErrorTime` stay 0. The
   retries are shared: the count is the sum of `fragmentError` over every level, against our 8, and it sets the
   wait before the retry too.
2. **A marked level is not chosen.** `controller/abr-controller.ts:983-996` (`findBestLevel`): a level other
   than the one last loaded is eligible only with `loadError === 0 && fragmentError === 0`, or when
   `isPenaltyExpired`, which is true only with `loadError > 0` (`utils/error-helper.ts:98-110`). So our
   `errorPenaltyExpireMs: 60000` (`hlsConfig`, since #98) never frees a level marked by `fragmentError` alone.
   That mark goes when a fragment **of that level** is buffered (`controller/base-stream-controller.ts:911-921`),
   in the bitrate test, or in `stopLoad()` (`controller/level-controller.ts:82-89`), which hls.js itself calls on
   every fatal error (`controller/error-controller.ts:502-504`): a reconnect clears every mark, and this run had
   none. One other way round it: when the level being fetched runs out of retries, the error controller may
   switch to a marked level, because it looks at `loadError` only (`controller/error-controller.ts:397-408`).
3. **The floor does not force a level.** `controller/abr-controller.ts:654-750` (`getNextABRAutoLevel`): when no
   level between `minAutoLevel` and `maxAutoLevel` is eligible, hls.js returns `minAutoLevel` only if that is a
   step **down**; otherwise it keeps `loadLevel`, below the floor.
4. **hls.js names its level all the time.** In Auto, while it is idle, the stream controller sets its load level
   to hls.js's choice on every 100 ms tick, before it looks at the bank
   (`controller/stream-controller.ts:262, 298, 321`). So the engine's `fetched` (LEVEL_SWITCHING) is hls.js's
   answer now, bank full or not. During a load, a parse or the wait before a retry it is still the level of that
   fragment.
5. **The server does not keep what a flush throws away.** One encoder run makes every rung, and the server
   deletes every rung's segments more than two minutes behind the newest request (`SessionManager`: `BEHIND`,
   `follow`, `deleteBehind`). With a full bank the newest request is about 480 s ahead of the picture, so the
   film before about + 360 s is already gone, and the rest goes when the encoder restarts at the flush point
   (`startRun`). The first request after a flush is held for up to 6 s (`WAIT_MAX`) while that restart makes the
   segment. When the old run is still going and counts the segment as within its range (`ensureReaching`), the
   request waits instead for the upkeep to stop that run, is answered 503, and the restart comes with hls.js's
   retry. So a re-bank begins with a wait and often a 503, and with more than 10 s to the first byte
   (`maxTimeToFirstByteMs`) it begins with fragment trouble.
6. **Our cap stays one rung above the fetch** (#126, `hlsjsEngine.js`, the FRAG_LOADED handler: the step gate
   ticks only while `fetched >= applied`). With 360p marked the cap sits at 360p, hls.js keeps choosing 240p, and
   480p is never offered.
7. **Our re-bank trusts the floor** (#310, `rebankStep`): it raises `minAutoBitrate` to the next rung and
   flushes the bank from playhead + 300 s whatever hls.js is fetching. By facts 3 and 4, if hls.js is still on
   the old rung it has already refused the new one (a mark, or its own estimate), the floor changes nothing, and
   the film comes back at the old rung. The bank refills, the re-bank is called done, and two minutes later it
   may do it again. It can only do good in the moment after hls.js has stepped up by itself.

So one failed 360p fragment left Auto on 240p for the rest of the film, and the re-bank then spent the bank four
times to refetch 240p.

**When the mark was made is read, not recorded** (the record does not say which level a timeout was on, and a
503 is not counted at all). Minute 2 is the reading that fits: from minute 5 the bank was full of 240p alone (the
flush in minute 14 needs every fragment past + 300 s to be one rung) through minutes at 2.4 to 4.4 Mbit/s, which
an unmarked 360p would not have sat out. A second way to the same mark exists and may have renewed it later: a
re-bank's first fragment (fact 5) that takes more than the 10 s first-byte limit is fragment trouble, which undoes
the re-bank and marks the new rung.

### Proved in the browser pane (hls.js 1.7.4 light build, real Chromium, the mux test stream)

Level 0 fetched, then the cap raised to level 1 (461 kbit/s) with a 5 Mbit/s estimate, and the first level 1
fragment made to time out once in the loader:

- the error is `fragLoadTimeOut`, not fatal, action `RetryRequest`; level 1 now has `fragmentError` 1 and
  `loadError` 0, and hls.js fetches level 0 again at once;
- 20 s and 70 s later (`errorPenaltyExpireMs: 60000`) `nextAutoLevel` is still 0, with the cap at 1 and the
  estimate at 5,000 kbit/s;
- with `minAutoBitrate` raised to level 1's bitrate, `minAutoLevel` is 1 and `nextAutoLevel` is still 0;
- with a `loadError` from 61 s ago added, `nextAutoLevel` is 1 (the expiry works for that mark only);
- with `fragmentError` set back to 0, `nextAutoLevel` is 1 and hls.js fetches level 1 within a second.

The pane has no server of ours behind it: fact 5 and everything that follows from it is read from the code only.

## The change

### A. A mark is forgotten after a healthy minute

New `frontend/src/player/rungMarks.js`, pure: `createRungMarks({ now, expireMs = HEALTHY_MS })` (the step gate's
60 s) keeps, per level index, the `fragmentError` count it last saw and when it first saw that count.

- `sweep(levels, fetched)`: for every level that is not `fetched` (the level hls.js is fetching: its own rules
  apply there, and the shared retry count is not to be disturbed while it is failing): no mark, forget the
  level; a mark not seen before, or a different count, note it with the time; the same count for `expireMs` or
  more, set `level.fragmentError = 0` and forget it. `loadError` and `loadErrorTime` are never written: hls.js's
  own expiry covers them. Returns the indices it cleared.
- `trouble()`: every noted mark's time becomes now, so the minute is a minute **without trouble**.

In `hlsjsEngine.js`, Auto only: `sweep(hls.levels, fetched)` at the top of the FRAG_LOADED handler, before
`applyCap()` and the gate's tick; `trouble()` wherever `gate.trouble()` is called (a `waiting` stall, a fragment
timeout or an error with no answer). The minute therefore starts at the first fragment loaded after hls.js has
left the failed level, restarts on trouble, and ends on a loaded fragment: a rung that fails every time is tried
about once a minute, never in a loop, and each try costs at most one first-byte or no-progress limit. hls.js
still decides: clearing the mark only lets its own estimate choose the rung again, under our cap, and by fact 4 it
does so within a tick.

A 503 on the first fragment of a step up marks the rung like any other failure, and hls.js chooses its level again
only after the wait before the retry (fact 4). If the re-bank's conditions hold in that wait (B), the re-bank
starts and its floor keeps hls.js on the new rung, whose fragment then loads and clears the mark. If they do not,
hls.js goes back to the old rung and the new one is tried again a minute later.

### B. The re-bank waits for hls.js to step up first

`rebankDecision` takes `fetched` and adds one condition: **hls.js is already fetching the new rung**
(`fetched >= next`). By facts 3 and 4, without it the film comes back at the old rung every time. It holds on the
FRAG_BUFFERED right after hls.js steps up, when nothing of the new rung is banked yet (hls.js's own handler runs
first and names the new level before ours runs), and for about one fragment after that: the moment the re-bank
was built for. The floor is raised as now, and with hls.js on the new rung it does its work: nothing in hls.js
goes below it (automatic choice keeps `loadLevel`, the emergency abort stops at `minAutoLevel`, an error switch
takes only levels at or above it), so a 503 on the first fragment is retried on the new rung.

The condition is needed, not enough. The first fragment still has to arrive (fact 5): if it runs into the 10 s
first-byte limit, the trouble rule undoes the re-bank as it does today, the film comes back at the old rung and the
new rung is marked until A forgets it. How often that happens on a train cannot be read from the code.

Everything else about the re-bank stays: its headroom, its quiet time, the ten-minute cool-down that trouble
starts, and the refusal of a bank with two rungs past + 300 s. So when hls.js steps up with the bank under 470 s
or the headroom short, or with the bank just under hls.js's own target so that it loads the next fragment in the
same tick, there is no re-bank and the lower rung plays out for up to eight minutes, as now.

## Tests (written first)

- `rungMarks.test.js`: not cleared at `expireMs − 1`, cleared at `expireMs` (the field is 0 and the index is
  returned); the fetched level is never cleared however old, and its minute starts when hls.js leaves it; a count
  that grows restarts the minute; `trouble()` restarts every minute; a level that hls.js cleared itself is
  forgotten, so a later mark gets a whole minute; two marked levels with different ages; `loadError` untouched.
- `hlsjsEngine.test.js`: in Auto a level above the fetch with `fragmentError: 1` is cleared by the fragment that
  loads 60 s after the first one that saw it, not by one at 59,999 ms; a fragment timeout, an error with no
  answer and a `waiting` stall in between each restart the minute; a 503 on another level does not.
- `rebank.test.js`: `fetched` one below the new rung, and null: no re-bank; at the new rung and above it: the
  decision as today.
- `hlsjsEngine.rebank.test.js`, in the order hls.js sends its events: with hls.js on the old rung nothing is
  flushed and `minAutoBitrate` is not written; LEVEL_SWITCHING to the new rung and then the FRAG_BUFFERED of the
  old rung's last fragment: the floor is raised and the flush follows; the same with a FRAG_LOADING at the new
  rung between the two (the bank was under its target): no re-bank; a 503 on the first fragment of the new rung
  with the bank full: the re-bank starts on the next `timeupdate`, and with the headroom short it does not. The
  flush cases already there each gain the LEVEL_SWITCHING that hls.js would have sent. `test/fakeHls.js` gains
  levels that carry `fragmentError` and `loadError`; it still models no choice of its own.
- `hlsLibrary.test.js`: the installed `hls.js/package.json` is 1.7.4. It fails on an upgrade, so the facts above
  are read again in the new source before the version moves (and A goes if hls.js comes to expire the mark).

Before the push the planner repeats the pane check against the built engine and the real library: one failed
fragment on the rung above the fetch, a fast link, the rung fetched again about a minute later, and no flush while
hls.js is on the old rung. `dev-and-testing.md` gains one hand check: with Auto fetching 360p, block the 360p
segment address in DevTools for one fragment and unblock it; Auto drops to 240p and is fetching 360p again within
about a minute and a half. Neither reaches the server's part (fact 5): the first real test of a re-bank is a run
with the headless viewer, whose segment list shows every refetch, its rung and how long its first fragment took.

## Not in this PR

- **The record does not say what the rules did.** Re-banks started, done and undone, and marks forgotten, had to
  be read off the bank's figures. Counting them in the report needs the server's `HealthRules` to accept them: its
  own small PR, worth having before the next run.
- **The server's wait for a segment it has deleted** (fact 5, `ensureReaching`): a restart at once when the
  segment is behind a running encoder and its file is gone would spare every re-bank, and every seek back of
  more than two minutes, a 503 and a second wait. A small PR on the server.
- Telling hls.js that `errorPenaltyExpireMs` does not cover a retried fragment: outward-facing, the owner's call.
- The cap one rung above the fetch (#126), a re-bank of a bank with two rungs, and the position look-ahead.

Each PR needs a release before the run that tests it.
