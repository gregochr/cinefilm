# The bank outside the browser's buffer

Owner, 2026-11-27, choosing between a lower train audio rate and this: "Bank outside the buffer". It replaces
decisions 7 to 10 and PRs 2b, 3b-1 and 3b-2 of `train-lookahead-plan.md`; that plan's map, matching, feed frame,
records and replay stay as they are.

## Why

Step 0.2 of the look-ahead plan measured Chrome 158's media buffers in the home replay rig (the real player,
headless Chrome, AAC noise at each rung's real rate, one rung pinned, paused at 0). The **audio** SourceBuffer
refused first in every run:

- 240p with 64 kbit/s audio: 948 s; 360p with 96: 716 s; 480p with 128: 588 s;
- 360p and 480p with 64 kbit/s audio: 948 s (480p's video buffer took 121 MB without refusing).

All three fit one rule to half a percent: 12 MiB = the audio bytes appended + about 93 bytes per AAC frame (46.9
frames a second at 48 kHz). The longest known stretch needs 18.5 minutes ahead plus the 30 s back buffer, 1,140 s,
which no rung reaches. Today's depth (480 s at most) fits every rung.

## What is true today (facts this plan rests on)

**The server** (`backend/.../transcode/`):

- One ffmpeg run per session writes every rung the session encodes (all of them in Auto) (`SessionManager.java:30`,
  `FfmpegCommandBuilder.java:125-127`). Media playlists are complete VOD lists written up front
  (`PlaylistWriter.java:28-45`): segment `n` of a rung is `seg-n.ts` next to its playlist, and any `n` may be asked
  for before it is encoded. Segments are cut at about 4 s, up to 12 s across a copy gap (`CutList.java:17-20`).
  Playlist requests do not move the session.
- **One playhead per session, for all rungs**, moved only by segment requests (`follow`, `:415-421`): forward to
  any newer request, back only to one more than 2 minutes behind. A request beyond the encoder's frontier + 10
  segments, or below its start, stops the encoder and restarts it there, first deleting every rung's files from
  there to the part's end (`ensureReaching`, `:448-470`, `startRun` `:478-480`). Every 2 s, segments more than
  2 minutes behind the playhead are deleted (`deleteBehind`, `:538-548`). The encoder is stopped when its newest
  segment is more than 3 minutes past the playhead (`:504-507`).
- A request inside reach waits up to 6 s, then 503 with `Retry-After: 1`; a 503 without `Retry-After` when the
  encoder failed twice (`HlsSessionController.java:75-78`); 404 for a rung or segment the session does not have,
  410 for a session Admin stopped. Activity is any playlist or segment GET: 2 minutes without one stops the encoder,
  10 deletes the files (`maintain`, `:353-369`; `TranscodeConfig.java:45`); the next request revives the session and
  restarts at that segment (`revive`, `:590-644`).

**So the server allows one fetcher per session walking forward.** Any media request behind the bank's front makes
the server restart behind and delete ahead. While the bank is on, media requests go through it.

**hls.js 1.7.4** (`frontend/node_modules/hls.js/src`):

- The fragment loader (`fLoader`) is constructed per load with the config; its `stats` must exist at construction,
  because hls.js assigns `frag.stats = loader.stats` before `load` (`loader/fragment-loader.ts:84-102`). On success
  it reads `response.data` and `context.resetIV` (`:106-107`): `onSuccess` gets the `context` it was given. After
  any callback hls.js calls `destroy()` (`:345-353`), also from inside `load` (`:352`). hls.js keeps no media payload
  after use (only the init segment's, `base-stream-controller.ts:815`); a flushed or refused fragment is fetched again through the loader.
- The payload is transferred to the demux worker (`demux/transmuxer-interface.ts:262-273`): a cache hands out a copy.
- The bandwidth sample is `bwEstimator.sample(parsing.end − loading.start − min(first − start, ttfbEstimate),
  loaded)` (`abr-controller.ts:528-541`); with `loading.start` and `first` set back by the real fetch's own
  durations the sample repeats the real download. `loaded === total` (`:267`) and `loading.first > 0` (`base-stream-controller.ts:2591`) are needed.
- **A manual level** (`hls.loadLevel = L`) makes every next fragment load at L without flushing (`hls.ts:790-803`,
  `level-controller.ts:676-677`); ABR is not consulted. `-1` returns to Auto. An error drops a manual level back to
  Auto unless `preserveManualLevelOnError` is set (`error-controller.ts:374`, `config.ts:542`). A switch to a level
  whose playlist hls.js has not loaded loads it first (`level-controller.ts:491-502`).

**The engine** (`frontend/src/player/hlsjsEngine.js`): `fLoader` is `createNoProgressLoader(Hls.DefaultConfig.loader)`
(`:131-135`), which ends a transfer silent for 12 s (`NO_PROGRESS_MS`); `pLoader` is not set. `applyCap`
writes `autoLevelCapping` only when the level differs from the cached `applied` (`:328-330`); `writeFloor` writes
`minAutoBitrate` only when it differs from `floorSet` (`:170-175`); `gate.start` returns the cap, which `cap()`
applies (`stepGate.js:15-18`); `LEVEL_SWITCHING` (`:439`) and the trouble path (`:536`) lower `gateCap`. An HTTP
answer to a fragment is not trouble (`:520-531`); a timeout is. `FRAG_LOADED` feeds `onThroughput`, `samples` and
`headroom` (`:491-517`); the re-bank flushes from `rebankStep` (`:264-304`). A 404 makes `useStream` rebuild the
engine (`useStream.js:341`).

## Decisions

1. **The bank holds whole segments in film order in page memory** (never IndexedDB, never sent anywhere), at most
   one copy of each sequence number, each at the rung it was fetched at, with its bytes and the timings of its last
   fetch attempt (start, first byte, end), plus the media playlist text of every rung up to 480p. Limit 600 MB; at
   the limit its front stops until film plays out.
2. **Armed, then on.** The look-ahead gives the engine a need F (film ahead, seconds), the time to the stretch and
   whether it has begun. **Armed** (a stretch within 30 minutes: the 20 of the rule plus hls.js's 480 s and the 30 s
   back buffer): the bank fetches the media playlist of every rung up to 480p (one plain `fetch` each; playlist
   requests do not move the session) and keeps a copy of every media fragment hls.js loads through the inner
   loader, with its timings, taken before hls.js's `onSuccess` (the payload is then transferred to the worker).
   **On**: once a stretch is within 20 minutes, every playlist up to 480p is held, film ahead is below F, hls.js has no media request in flight, and
   the bank holds every fragment from playhead − 30 s to the end of hls.js's buffered range, so a recovery or refetch
   is answered. Home, hotels and a train with no stretch near are never armed: today's path is untouched.
3. **hls.js is on a manual level while the bank is on**, with `preserveManualLevelOnError: true`: `hls.loadLevel`
   is the rung the bank holds hls.js's next fragment at (or, beyond what is held, the front's rung). The next fragment
   is the first one at or after the end of the `video.buffered` range that holds the fragment just loaded once it is
   appended (ranges merge after a seek back). The engine writes the level on the `FRAG_LOADED` of each answered
   fragment (before hls.js chooses the next, `base-stream-controller.ts:607-615`); on `seeking` (the end of the
   buffered range holding the target, or the target if it is not buffered); and before its own
   `recoverMediaError()` (`hlsjsEngine.js:564`; the rung held at the playhead). The step gate, floor hold, champagne
   ceiling, re-bank and the gate-lowering at `:439` and `:536` are suspended (a re-bank under way is undone first).
   **Release:** `hls.loadLevel = -1`, `floorSet` and `applied` invalidated, `writeFloor()`, then
   `cap(gate.start(fetched))` (the rung the bank last fetched, which the link has just carried; still clamped by the
   champagne ceiling in `applyCap`), so the climb from there is one level a minute.
4. **One fetcher.** The bank loader gives every media request of hls.js to the bank, which answers from what it
   holds, waits on its own fetch of it, or fetches it **next** (hls.js's requests first). A request **behind the front**
   (held film lies after it, as after a seek back) starts a wait: the front waits until hls.js asks for held film,
   or a seek or clear; quiet spells in between do not count. A request **at the front's next position** is simply
   the front's next fetch, and the front carries on past it towards F. The front starts at the end of
   hls.js's buffered range holding the playhead and, whenever hls.js asks past it, moves past that. It fetches one
   segment at a time until film ahead reaches F, and tops up as the film plays.
   - **A fetch hls.js waits on** keeps today's shape: hls.js's own first-byte and load limits count from hls.js's
     request (past them the loader calls `onTimeout`, the bank's fetch carries on, and hls.js's retry is answered
     from it); when the no-progress rule ends the fetch it waits on, it gets `onTimeout` at once; a server answer
     (503, 404, 410) to that fetch, or a 503 wait the fetch is already in, is handed to hls.js as that answer at once,
     so the trouble path and the record see what they see today.
   - **The front's own fetches**: `fetch` with the session's cookie, `cache: 'no-store'`; a first-byte limit of 10 s
     and 12 s with no progress, no total limit (a slow 12 s segment still arrives); 503 with `Retry-After` waits it
     (backoff to 5 s) and asks again; a network error or timeout waits 2 s and asks again; a 503 without
     `Retry-After` pauses the front, 404 or 410 stops it (decision 8). Each completed fetch reports its kbit/s for the health record.
   - **A request for a fragment held at another rung** (a refetch or a recovery reaching back across a change of
     rung): the engine sets `hls.loadLevel` to that rung and the request fails as a retryable error with code 503
     and text `bank-rung`; the manual level makes the retry load at the held rung (`stream-controller.ts:262, 298`).
     Never sent to the server. hls.js raises the wrong rung's `fragmentError`, which counts towards every later
     retry's delay and budget (`error-controller.ts:310-318`), so the engine's ERROR handler takes that one back
     for a `bank-rung` error.
   - **Stopping the front** aborts its fetch in flight and every pending retry timer.
5. **The loaders.** `fLoader` becomes a bank loader wrapping today's, built in its constructor so `stats` is the
   inner loader's object; `pLoader` wraps hls.js's default. While on: a media fragment goes to the bank; an answer
   fills the stats from the held timings — `loading.start = now − (end − start)`, `first = loading.start + (first −
   start)`, `end = now`, `loaded = total = bytes` — and calls `onSuccess({ url, data: copy }, stats, context, null)`
   from a microtask unless `abort` or `destroy` came first; a held media playlist is answered the same way. Anything
   else goes to the inner loader. `abort` calls `onAbort` as the default does; `destroy` is safe inside `load`.
6. **Samples.** A fragment answered by the bank is marked on its stats; the engine takes no `onThroughput`,
   `samples` or `headroom` from it (the front's fetches report instead). hls.js's estimator gets the replayed timings.
7. **Choosing the rung.** The front starts at hls.js's rung, or 480p if hls.js is above it. Once 20 s of fetching is measured, and then once a
   minute while film ahead is below F, the front is not at the limit and the stretch has not begun, the bank
   projects film ahead at the stretch's start for each rung c up to 480p: `film ahead now + time to stretch × (1 /
   (w + d × rate(c) / rate(R)) − 1)`, where over the last 60 s of the front wanting film, `w` is the waiting per film
   second fetched (first bytes, 503 waits, retry waits) and `d` the transfer time per film second (first byte to
   end), at the rung R it fetched. The front's rung becomes the highest c whose projection reaches F; if none
   does, the c with the highest projection. A change applies from the front only: nothing held is dropped, and the
   manual level follows by decision 3. When a stretch is passed and the next is within 20 minutes, the same choice
   is made for that one.
8. **Pausing and stopping.** A fatal network error, or a plain 503 to the front, **pauses** the front; it resumes
   on a server answer to any request or after `RECONNECT_MS`, never on a fragment the bank answered. A **410** to
   the front stops the bank and calls `onStopped` at once (Admin Stop, as today); a **404** calls `onSessionLost` at
   once (the rebuild starts a new engine and bank). A media error never touches the front. The bank turns **off** when the stretch is passed and no other is within 20
   minutes, or the look-ahead lapses (`train-lookahead-plan.md` decision 7's lapse rule): the front stops, held film
   is still answered until hls.js asks beyond it, then the release of decision 3 and the bank is cleared. A seek
   forward beyond what is held clears it at once (release, then armed again if a stretch is near).
9. **Keeping and dropping.** A segment is dropped once the playhead is 30 s past its end, so a refused append
   fetched again is answered. A seek forward inside what is held drops what ends more than 30 s before the target; a seek back keeps what
   is held ahead and hls.js's requests fill the gap through the bank (decision 4).
10. **hls.js's own depth stays as today** (120 to 480 s): every rung's measured hold is above 480 s plus the back
    buffer, so the browser's buffers never refuse; the deep part is the bank's.

## Pull requests (each aimed at 1,200 changed lines, tests included)

Code estimates are scaled by 1.7 for this repo's history; tests run 2.4 to 3.6 times code. Step 0.3 (the encoder's
speed, read by the owner from `GET /api/admin/transcoding`, which returns `speed`) comes first: in Auto the encoder
writes every rung, and the bank fills no faster than it.

1. **B1, the store** (`segmentBank.js`, pure; about 250 lines of code): decisions 1 and 9, the playlists, the 600
   MB limit, the coverage test of decision 2. Tests: one copy per sequence number at its rung; dropping 30 s behind;
   seeks forward inside (keeping what ends within 30 s before the target), back and beyond; the limit stopping and releasing the front; covered and not covered.
2. **B2a, the front** (`bankFront.js`, `fetch` and the clock passed in; about 250): the front's fetches of decision
   4. Tests: the playlists fetched when armed; the start and the move past a request beyond it; one request at a
   time; one kbit/s report per completed fetch and none for a failed one; decision 7's 60 s window (`w` and `d` per
   film second) kept at the rung fetched and counting nothing while the front does not want film; `setRung` applying
   from the next fetch only, and a start above 480p fetching at 480p; 503 with `Retry-After` and the backoff, a
   503 without it pausing (no request until `RECONNECT_MS` or a server answer), network error, the first-byte and no-progress limits, a 25 s transfer completing;
   abort of the fetch in flight and of pending retries, and no request after it stops.
3. **B2b, requests hls.js waits on** (`bankRequests.js`; about 250): decision 4's first and third bullets and
   decision 8. Tests: hls.js's request before the front's next; the front waiting through a gap, including one
   longer than hls.js's buffer goal with hls.js quiet mid-gap (no front request until hls.js reaches held film);
   hls.js asking at the front's own position, the front then fetching on to F while hls.js is quiet; the waiting
   request's limits counted from hls.js's request with the fetch carrying on; `onTimeout` when the no-progress rule
   ends the fetch; a 503, 404 or 410, or a 503 wait already under way, handed over at once; a wrong-rung request
   failed as a `bank-rung` 503; a plain 503 or fatal error pausing, resumed by a server answer or `RECONNECT_MS`
   and never by an answered fragment; 410 calling `onStopped` and 404 `onSessionLost` at once.
4. **B3, choosing the rung** (`bankRung.js`, pure; about 120): decision 7. Tests: the projection at, just under and
   just over F; wait-dominated, transfer-dominated and a window with one 503; no choice inside a stretch, at F, at
   the limit or before 20 s; none reaching F keeping the highest projection; a 240p start on a fast link choosing
   480p; the next stretch chosen afresh.
5. **B4, the loaders** (`bankLoader.js`; about 200): decision 5 and the armed copies of decision 2. Unit tests with
   the real callback arguments (`context.resetIV` read): stats on an answer (exact values), a fresh copy per answer,
   the armed copy taken before the inner `onSuccess` and intact after the payload is transferred, abort and destroy
   before the microtask, destroy inside `load`, the init segment and master playlist passed through, a held media
   playlist answered.
6. **B5a, the manual level** (`hlsjsEngine.js`; about 200): decision 3. Unit tests: the level written on
   `FRAG_LOADED` from the merged buffered range, on `seeking` inside and outside the buffer, and before
   `recoverMediaError`; a `bank-rung` request setting `hls.loadLevel` to the held rung before its error is raised;
   rules and gate-lowering suspended; a re-bank under way undone first (`rebankFloor` back to
   0, the floor written before the manual level); a `FRAG_LOAD_TIMEOUT` while on counted as trouble with no
   gate lowering; a `bank-rung` error's `fragmentError` taken back, and a following real 503 retried after 1 s;
   release writing the floor and cap again (R equal to the old floor, `applied` above R) and, with a kept 240p gate
   and 480p fetched, a cap of 480p.
7. **B5b, wiring** (`hlsjsEngine.js`; about 200): decisions 2, 6, 7's timing, 8 and 10, the loaders installed, the
   bank's input as a new engine method beside `destroy`. Unit tests: `bankRung` called first after 20 s of fetching,
   then once a minute, never inside a stretch, at F or at the limit, and again for the next stretch, its answer
   handed to the front; arming and turn-on (not before coverage, nor while a
   playlist is missing, nor while a fragment is loading through the inner loader, then on once it lands; not at or
   above F, nor with the stretch more than 20 minutes away); a fatal `NETWORK_ERROR` pausing the front, resumed
   by a server answer or `RECONNECT_MS`; marked fragments taking no samples and the front's reports reaching
   `handlers.onThroughput`; 410 and 404 from the front reaching `onStopped` and `onSessionLost`; turning off
   (front stopped, held film answered, release on the first request beyond it, bank cleared); a seek forward beyond
   what is held releasing at once and arming again with a stretch near; a media error leaving the front running; a
   pinned rung never armed. **A pane check with the real hls.js 1.7.4** (a built engine bundle against a public test stream, the bank
   armed then on, the link cut by the test's own fetch): every media request after turn-on answered by the bank,
   none reaching the inner loader, across a run of fragments, a change of rung down and up, a seek inside the
   buffer, a seek back across a change of rung whose fill merges into buffered film, a seek forward into film held at
   another rung, a refetch of a fragment held at the old rung (one wrong-rung request, the rung's `fragmentError`
   back to 0), eight fragment errors not going fatal under `preserveManualLevelOnError`, a fatal `MEDIA_ERROR`
   answered from the bank, and a release.

Then the look-ahead plan's 2b becomes the rule's input only (a stretch within 30 minutes: F, the time to it, whether
it has begun, when it is passed), wired by 3c. 5b records, per playback, media requests that reached the server
while the bank was on and the bank's rung changes. The replay stand-in (4a) gains the server's one-playhead rules
(`follow`, `ensureReaching`'s restart, `deleteBehind`), so a fight between fetchers shows in 4b's runs of the 23 and
26 Nov journeys as restarts, which 4b's reading counts and fails on; those runs come before a release.

## Not in this plan

- A bank that survives a reload (IndexedDB), or a bank at home: the Apple TV download-ahead idea stays parked.
- Lower audio rates (the owner's other option).
- Any server change: the one-fetcher rule fits the server as it is.
