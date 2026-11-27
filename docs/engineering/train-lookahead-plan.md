# Train look-ahead: bank for the bad stretches before the train reaches them

Owner, 2026-11-27: "identify if we're travelling that route (perhaps automatically) and then proactively ensure the
buffer is big enough to cover the bad stretches with the highest quality we can … Home and hotels don't need this
extra location sensitivity, we're not moving when we're there." Then: "Yes, plan it".

Builds on the stuck-rung fix (#357, merged): without it a lost fragment pinned Auto to 240p.

**Scope.** Four review rounds of a fuller design kept finding faults in one place: sums that pick the best rung from
the link's measured speed before each stretch, and how such a cap moves the step gate and the re-bank. This plan
is therefore the first version only: **bank deep enough before each known stretch, at no rung whose film the
browser cannot hold.** The link's own rules choose the rung below that. Whether a cleverer rung choice is worth
building is read from the first journey's record (PR 5), and planned then.

## What is true today

**The player** (`frontend/src/player/`):

- `hlsConfig` (`hlsjsEngine.js`): `maxBufferLength` 120 s, `maxMaxBufferLength` 480 s, `maxBufferSize` 100 MB,
  `backBufferLength` 30 s. hls.js 1.7.4 holds `min(max(8 × maxBufferSize / maxBitrate, maxBufferLength),
  maxMaxBufferLength)` seconds (`base-stream-controller.ts:1480-1491`), so `maxBufferLength` overrides the byte
  budget; the settings are read from `hls.config` live. A lower depth only stops fetching; nothing is flushed.
- A refused append is first met by evicting back buffer and retrying (`buffer-controller.ts:1019-1042`); then
  `BUFFER_FULL_ERROR` (never fatal), on which hls.js writes `maxMaxBufferLength` down and flushes from the next
  fragment (`base-stream-controller.ts:1516-1532`, `:2312-2345`).
- The cap `applied` is the lower of the step gate's `gateCap` and the champagne ceiling. The gate ticks on
  `FRAG_LOADED` while `fetched >= applied` (`hlsjsEngine.js:516`); every `LEVEL_SWITCHING` calls
  `gate.switched(level)`, which lowers the gate when the level is below it (`:439`, `stepGate.js:18-23`); an
  arrival at the cap is `data.level === applied` (`:432`). `champagneCeiling` is passed `applied` (`:311-313`).
  The floor hold lets go when `applied` is below its 360p level (`:240`). `gate.start(level)` sets the cap and
  restarts its healthy minute (`stepGate.js:13-17`).
- The re-bank (`rebank.js`) runs when at least 470 s are banked and flushes from playhead + 300 s to the end.
- The engine returns only `{ destroy }` (`:571-583`).
- A minute row is `[minute, median kbit/s, rung on screen, ms moved, ms stalled, s ahead, rung fetched]`; the
  median is 0 when no fragment completed in the minute (`health.js:179-186`): a dead link, a paused minute, or the
  bank having reached the film's end.

**The server:** the ladder (`Ladder.java`, `Rung.java`): 240p (the floor rung above 240 lines) 250 kbit/s video +
64 audio; 360p 600 + 96; 480p 1,000 + 128; 720p 2,500 + 128. Segments are muxed MPEG-TS; hls.js splits them into
an audio and a video SourceBuffer. The encoder stops 3 minutes past the newest request (`LOOK_AHEAD`), which does
not limit a deep fetch. A session with no request for 10 minutes is deleted; the next request rebuilds it, waits
up to 6 s and may answer 503. Per imported journey (`journey/`, V37): the logger's rounds with position, course,
speed, fix age and the internet check (a failed round has no position), the viewings during it, the train's times.
A minute row's time is its viewing part's `started_at` plus n minutes. Records are capped at 2,000 and a deleted
one takes its journey link with it. The Mac has no ffmpeg, so the `dev` profile cannot transcode locally.

**The train's feed:** `https://www.ombord.info/api/jsonp/position/` (latitude, longitude, speed in m/s, course,
fix mode and age) and `user/` (the device's limit), JSONP with trailing commas in places; it does not resolve off a
train. On 26 Nov it answered over trusted `https` on every round; the first answer of each part carried
`Content-type: application/json` and `Access-Control-Allow-Origin: *`; the name resolved to a private address.
So a `<script>` load would be refused as JSON; the page reads it with a CORS `fetch`, which needs that header on
every answer (only the first was logged). Seen by curl only. Chrome gates requests
to private addresses behind local network access rules; how they treat a sandboxed frame on a site reached over
Tailscale is not established.

**`SECURITY.md` rule 7:** the operator lookup is the browser's one outside request (a sandboxed `srcdoc` frame,
no cookie, no referrer); "The browser never asks for a location"; journey positions are "in the database, in its
dumps … and in Admin's answers, and nowhere else".

## What the journeys showed (the owner's notes; not repeated here, the repository is public)

Three journeys, both directions. The long bad stretches fell in the same five places each time; the link elsewhere
was good, up to the device's 5 Mbit/s. Two of the five lasted longer than the eight-minute bank (the longest about
14 minutes, so a need of 18.5), and some gaps between them are about 11 minutes. At 4.5 Mbit/s an 11-minute gap
can bank that need at 480p (`4 + 11 × (4.5 / 1.13 − 1) ≥ 18.5`), so the link is not the limit; the browser's
buffers may be. If Chrome's audio buffer is the 12 MiB we recall, it holds about 13 minutes at 480p's audio rate,
17.5 at 360p's and 26 at 240p's: step 0 measures it.

## Decisions

1. **The detector is the train's feed.** Asked once when playback starts and on a network change, only away from
   home; every 30 s only after an answer. No answer: nothing changes. No mode, no profile, no browser prompt.
2. **The map is learned from the imported journeys, on the server,** and carries only what matching needs: per
   track, points (latitude, longitude, course, seconds from the track's start) and its stretches (from and to, as
   seconds along the track, and the need). No journey ids, dates, times or train ids. No real place in the
   repository.
3. **The position stays in the browser, in memory.** Neither the map nor a position goes to `localStorage` or is
   sent anywhere.
4. **Matching.** The nearest segment of a stored track within 300 m whose direction is within 45° of the train's
   course; a segment joins only points at most 60 s apart; a track can be read backwards; below 5 m/s the last
   match is kept and the heading test skipped; a fix older than 60 s is refused, a younger one moved on by speed ×
   age. The time to a stretch is the shortest any track took from that place to it. No match: nothing changes.
5. **Bad minutes.** A track point's minute is the linked viewing's minute row containing it (`started_at + n × 60
   ≤ sampled_at < started_at + (n + 1) × 60`); where rows of two parts contain it (a part's last window runs past
   its end), the later-started part's row is used. A playing minute (moved + stalled 45 s or more) that stalled (not
   in a part's first minute) is bad. Otherwise, for a minute that moved 45 s or more: **with a fragment** (a
   median above 0), bad when its median is under 500 kbit/s and good at or over 500; **with none** (median 0,
   which `health.js` writes for a minute with no throughput sample), bad only when the bank had not reached the
   film's end, else no verdict. A minute row holds no film position, so the film's end is
   found by rebuilding each minute's position backwards from the part's `endPositionMs`, less the later minutes'
   moved ms; the minute is at the film's end when that position plus its seconds ahead is at least the edition's
   runtime (as `Edition.recomputeRuntime` works it out) less 60 s. Only for a part with no seek, an
   `endPositionMs` and a known runtime; otherwise a no-fragment minute has no verdict, never bad by itself. A
   minute with no verdict (not playing, at the film's end, or not decidable), and a point with no minute, take the
   logger's internet check for the point's state: good if it answered within
   1 s, bad if over 1 s. A round with a position but no check (`probe_ms` null: a timeout, an error answer, a
   check not finished, or checks turned off are all stored alike) is bad when other rounds of the same journey
   carry checks, else unknown. A minute's own verdict (bad or good) stands; the check never overrides it. A dead spell the bank rode out still reads bad (no fragment).
6. **Stretches** are runs of bad points along a track, allowing one good or unknown point inside, merged across
   tracks that overlap along the line (within 300 m) to their widest extent. Duration: the longest any track
   took through the whole merged extent (a track starting or ending inside it does not count); with no track
   through the whole extent, its length at the slowest moving speed (5 m/s or more) any track was recorded at
   inside it; with no moving sample inside it, the stretch is left out of the map until a journey crosses it. **Need:** F =
   duration × 1.25 + 60 s of film ahead; the buffers must hold F plus the 30 s back buffer.
7. **The rule.** When a stretch is within 20 minutes of travel (decision 4's time):
   Rungs above 480p are never candidates while the rule holds; their fragments still in the buffer count with
   holds derived from 480p's (the audio is the same 128 kbit/s; the video hold scaled by 480p's video rate over
   theirs, the copy rung's from its own bitrate).
   Two sums use the holds. Step 0 gives each rung's hold h(r) for each buffer (seconds that buffer holds when all
   at rung r: measured where it bound, else the lower bound from the same run, which is cautious); a buffer takes
   part once any run measured it. Both sums read the engine's `banked` list after `pruneBanked()`. N = the larger
   of F and today's depth for the level.
   - **cap (the bank at the stretch's start):** t_s(r) is the film at each rung that will **still be buffered when
     the stretch begins**: fragments ending after playhead + time to the stretch − 30 s. A candidate rung c fits
     when, for each buffer, `Σ t_s(r) / h(r) + max(0, N + 30 − Σ t_s(r)) / h(c) ≤ 1`. So with the stretch a
     bank-length or more away, c must satisfy `(N + 30) / h(c) ≤ 1` alone. 240p if none. The cap is one more term
     in `applied`, nothing else. It is chosen again on every `FRAG_BUFFERED` while the rule holds, but **only
     falls** while the train approaches one stretch. When the stretch it was chosen for is passed and the rule
     carries on for the next, it is chosen afresh for that one; a higher answer is taken through the lift's path
     (decision 10), so the step gate still climbs one level at a time;
   - **depth (the buffer now), rising as room comes:** `maxBufferLength` and `maxMaxBufferLength` limit the film
     ahead only. t_n(r) is every fragment buffered now at each rung (back buffer and the whole bank ahead). The room
     at c is `min over buffers of (1 − Σ t_n(r) / h(r)) × h(c)`, rounded down to whole segments so hls.js only
     starts a fragment that fits. The depth is `max(today's depth, min(film ahead now + room, F))`; once any
     cut-back has happened in this playback (decision 8) it is `min(film ahead now + room, F)`, with no floor at
     today's depth, so a buffer that cannot hold today's depth is never asked to again. It is worked out again
     on every `FRAG_BUFFERED`, every 5 s of `timeupdate` and every `LEVEL_SWITCHING`, so as older film at a higher
     rung plays out and leaves the buffer, the depth rises towards F;
   - when the stretch is passed and no other is within 20 minutes, both are lifted at once (a lower depth only
     stops fetching). The depth goes back to today's, or, after a cut-back in this playback, to `min(today's depth,
     film ahead now + room)` with the shrunk holds, which then stand for the rest of the playback.
   - Inside a stretch the rule already holds for the next one if it is within 20 minutes.
   - Auto only: a rung pinned by hand keeps today's depth.
   - **The rule lapses** when there has been no fresh matched fix for 5 minutes (the feed stopped, a diversion, off
     the train) or the stretch's expected end has passed: its cap and depth are lifted as above.
8. **hls.js's cut-back shrinks the holds** (only in a playback where the rule has been in force: elsewhere
   hls.js's own handling is untouched). On a `BUFFER_FULL_ERROR` (its `sourceBufferName` names the buffer)
   the engine works out that buffer's used fraction `u = Σ t_n(r) / h(r)` over **every** fragment buffered now
   (back buffer and bank ahead, all levels, after `pruneBanked()`); if u < 1 every hold of that buffer is multiplied
   by u for the rest of the playback, the cap is chosen again and the depth worked out again. hls.js's own lowered
   `maxMaxBufferLength` is not kept as a ceiling: the engine sets the depth again from the room now, so it rises
   once room comes. An engine rebuilt by `useStream` starts without this memory and is
   handed the rule's input again; the cut-back simply repeats.
9. **No re-bank while the rule holds, or after it is lifted while its bank is still deeper than today's depth for
   the level plus one segment** (the re-bank flushes to the end of the bank). Today's re-bank is otherwise
   untouched: hls.js stops fetching at 480 s plus up to one segment, which is when it acts. A re-bank under way
   when the rule begins is undone (`undoRebank`), so its flush is refilled at once.
10. **The step gate is kept apart from the look-ahead's cap.** While that cap is below `gateCap`: `gate.switched`
    and `lastStepDownAt` only for a level below the look-ahead's cap; the fragment-trouble path (`:536`) and
    `applyCap`'s pull-down (`:326`) lower the gate only to a level below the look-ahead's cap, so one timeout
    inside a stretch does not drop `gateCap` to the look-ahead's cap; the gate's tick and the arrival test compare
    with `gateCap`, not `applied`; `champagneCeiling` is passed `min(gateCap, ceiling)`. When a cap that was below
    `gateCap` is lifted the engine calls `gate.start(min(gateCap, fetched + 1))`, so the cap is at most one level
    above the fetch and the gate starts a fresh minute. The look-ahead's own cap never rises while the rule holds
    (decision 7), so a lift is the only way up.

## Step 0: checks before any code (scratch work, never committed)

1. **Can the player's frame reach a private address?** First, which address space the site is in at home and on
   a train (Tailscale's 100.64/10, the home network): if Chrome counts the site as local already, a request from
   it to the train's private address is local to local and the access rule may not apply. Then, at home, in the
   owner's Chrome on the real site, a frame built like the operator lookup's is added from the console and, from its
   opaque origin, reaches the site's own Tailscale `https` address (a private address with a trusted certificate):
   a `fetch` with `mode: 'no-cors'` (resolved: reached; a TypeError: blocked; CineFilm sends no CORS headers, so a
   plain fetch would fail whatever Chrome allows). The same frame against a public `https` address is the control. Anything unclear is
   read in DevTools' Issues panel by the owner. This decides only if Chrome counts the site's address as local; if
   it counts it as public, the home test proves nothing about a public page reaching a private address, and the
   answer waits for the first train run with a scratch frame, 3a not built until then. It acts inside the owner's
   signed-in session, so it waits for the owner's yes. If Chrome blocks or asks, stop and put the choices to the owner.
2. **The buffers' hold per rung.** In the home replay rig (`scripts/replay/`, the real player built from the
   checkout, headless Chrome, no ffmpeg needed), with scratch edits never committed:
   - audio at each rung's rate: `make-template.mjs` has WebCodecs encode noise at 64, 96 and 128 kbit/s (48 kHz
     frames, since Chrome's buffer counts frames as well as bytes), and `ts.mjs` picks the frame by rung (today it
     repeats one silent frame); the picture's padding subtracts the bare segment's bytes, whose cache
     (`bareSegmentBytes`) must then be kept per rung;
   - one rung pinned through the stand-in's `film.preferences.quality`, a `flat:` trace, the depth raised far in
     the engine, a CDP `video.pause()` at 0 in `drive.mjs`;
   - a scratch listener in the engine keeping `BUFFER_APPENDED` ranges per type and printing a short summary per
     type (first start, last end, bytes) with `console.warn` at the first `BUFFER_FULL_ERROR`, with its
     `sourceBufferName` (the driver keeps warnings, cut to 300 characters), and each SourceBuffer's own `buffered`
     ranges at that moment, since Chrome may have evicted film itself; a run with no refusal in 20 minutes stops
     and says so.
   Runs for 240p, 360p and 480p, and for 360p and 480p at 64 kbit/s audio (question 2). Each gives the binding
   buffer's hold and prints the other buffer's seconds too, which are its lower bound (decision 7 uses them so).
3. **The encoder's speed:** the owner reads it in Admin's sessions list during a fetch of a 1080p film. Under 4×
   real time would slow the banking before a stretch; tell the owner.

Then the owner is told what the buffers allow, with question 2.

## Pull requests (each 1,500 changed lines or fewer, tests included)

1. **1a, points and states (backend):** a service building each journey's track points and their states
   (decisions 4's 60 s rule and 5). Unit tests with made-up coordinates far from any real line: each half of a
   minute, a playing minute with no fragment (bad), the film's end within 60 s with a good check (good) and with no check
   anywhere in the journey (unknown), a part with a seek, no `endPositionMs` or an unknown runtime with a slow
   check (bad), a point in the overlap of two parts' rows (the later part's), a mostly-paused minute (the check decides), a slow check, a
   missing check with and without checks elsewhere in the journey, a good minute whose round has a slow or missing
   check (stays good), a minute with 40 s stalled and 20 s moved outside a part's first minute (bad), 40 s stalled and 0 moved (not
   playing: the check decides), a stall in a part's first minute ignored, a
   two-part viewing, a journey whose viewing is gone, no positions.
2. **1b, stretches and the endpoint (backend):** decision 6 from a pure class, and `GET /api/journeys/link-map`
   (the session's cookie, `Cache-Control: no-store`; an `excludeJourney` parameter for the replay). `SECURITY.md`
   rule 7's journey paragraph rewritten (question 1 answered first). Unit tests at each boundary; an API test.
3. **2a, reading and matching (frontend, pure):** `frontend/src/player/trainFeed.js` (JSONP, trailing commas,
   kinds and ranges, fix age) and `trackMatch.js` (decision 4). Boundary tests. (**2b, the rule's sums**,
   `lookAhead.js`, decisions 7 and 8 with step 0's holds as a table, belongs to the 3b series below and waits for
   its review.)
4. **3a, the feed frame,** in the shape step 0.1 allowed: a CORS `fetch`, whose header the browser enforces (an
   answer without it rejects with a TypeError, the frame posts null, the scheduler counts it as no answer; page
   script cannot read that header, so nothing inspects it). Tests of the rejected fetch. Rule 7's browser
   paragraph rewritten.

**2b, 3b-1 and 3b-2 are reviewed again before they are built,** with step 0's holds in hand: decisions 7 to 10 were
reviewed eleven times and each round found a smaller fault in how the cap and depth meet hls.js and the step
gate, and real holds may simplify them.

5. **3b-1, the engine's cap:** the look-ahead's cap as an input, decision 10. Tests: the cap applied and lifted;
   after a lift the cap is one level above the fetch; the floor hold letting go under a 240p cap; a switch up to
   the look-ahead's cap not lowering the gate; a fragment timeout under the look-ahead's cap leaving `gateCap`
   where it was; the champagne ceiling unmoved; a pinned rung untouched; the cap chosen afresh, and higher, for
   the next stretch climbing one level at a time.
   **3b-2, the engine's depth:** decisions 7's depth, 8 and 9. Tests: the depth set (never above the larger of F and today's depth, never below today's until a cut-back, and after one never asking for more than the room; whole segments of
   room), rewritten on
   a switch and lifted; a full 480p bank with a stretch 20 minutes away and c = 240p filling to F with no refused
   append, the depth rising as the 480p film plays out; a
   cut-back shrinking the holds and the cap; no re-bank while the rule holds or its deep bank remains; a 484 s bank
   with no rule still re-banks; a re-bank under way undone when the rule begins; the rule lapsing; a full 240p bank
   with the stretch 20 minutes away choosing the rung by `(N + 30) / h(c)` alone; a short stretch (F under today's depth) keeping today's depth; the
   cap not rising while the rule holds; a cut-back counted over every buffered fragment; a lift after a cut-back
   asking no more than the room.
6. **3c, the wiring:** the map's client in `frontend/src/api`, the scheduler (decision 1's cadence, away from home)
   that runs feed, match and rule and hands the engine its input (again to every engine `useStream` rebuilds),
   through `useStream`; hand checks in `dev-and-testing.md` with made-up places. Runs the Shell suite
   (`guard.test.mjs` parses `resources.js`).
7. **5a, the stuck-rung counters** (never built: `stuck-rung-plan.md`, "Not in this PR"): re-banks started, done
   and undone, and marks forgotten, in the record, `HealthRules` accepting them. **5b, the record says what the
   look-ahead did:** minutes matched, each stretch's need, the bank and rung at its start, the cap set, and the
   look-ahead's switches down apart from the link's. Both before the release, so the first journey records them.
8. **4a, the replay's stand-ins:** the audio padding of step 0.2 made real, with tests; the stand-in answers the
   feed's address (host rules and a certificate, made with `/usr/bin/openssl` and allowed only in the rig's
   throwaway profile) and the map (the real server's answer with the replayed journey excluded, saved by the
   owner); `SECURITY.md` rule 6's paragraph on the rig rewritten (today only `localhost` resolves there).
   **4b, the replay rides a journey:** positions in time with the record, session expiry and its restart
   modelled, the manual. The map, the positions and the runs' results that follow a journey are read from and
   written to a folder outside the checkout only (under `~/train-feed-logger/`); `scripts/replay/out/` keeps
   position-free results only, and rule 6's rig paragraph says so. Runs of the 23 Nov and 26 Nov journeys with and without the look-ahead. Both run the Shell suite.

1a, 2a and 3a touch different files and may be built side by side once step 0 has passed; 1b's tests also cover
a zero-speed sample and a stretch with no moving sample. A release after 5b
and the replay's runs; the first real test is the journey after that.

## What never goes in the repository

Real positions, places, times or train ids; a saved map; exports of minute rows; the replay's inputs and results;
step 0's results tied to a place; the date of a journey to come. Every fixture's coordinates are made up. (The rig
already holds one real record, `scripts/replay/record/train-2026-11-12.json`, from #185: 4a and 4b do not copy
that pattern.)

## Not in this plan

- Choosing the rung from the link's measured speed before a stretch (the fuller design): planned, if the record
  shows it is needed, after the first journey with this one.
- Choosing a train from the timetable or live running data; other trains' systems; a bank beyond the browser's
  buffers (the Apple TV app); the journey view.

## Questions for the owner

1. **Rule 7.** The browser would read the train's own position (kept in the page, never sent or stored) and the
   server would send the player a map built from your journeys (outside Admin, kept in memory only). Suggested
   wording: "On a train, the player reads the train's own Wi-Fi system for its position, from a sandboxed frame
   like the operator lookup's; the position is used in the page only and never sent or stored. The player also
   receives a map of past journeys' link quality, kept in memory only. The browser never asks the device for a
   location."
2. **Audio on the middle rungs** (only if step 0 shows the audio buffer is the limit). 64 kbit/s audio on 360p and
   480p, as on 240p, would let them hold as long as 240p does, at some loss of sound on those rungs: for every film,
   or only in sessions started while the feed answers (a server change either way). Or keep the sound and accept a
   lower rung before the longest stretches.
3. **Depth before quality.** The rule never lets a rung be fetched whose film cannot cover the stretch. Agreed?
