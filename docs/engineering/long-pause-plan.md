# Sessions that survive a pause — plan (item 7 of the network resilience review)

Written 2026-11-11 and revised the same day after an adversarial review (its findings are folded in; the larger ones
are listed at the end). Item 7 of `network-resilience-review.md`, pulled forward from its phase 3: it needs no owner
decision and no measurement. The review grouped it with the deeper buffer (item 6) only because a deeper buffer makes
the loss larger. One pull request, built by Sonnet under the usual gates. Backend only.

## The problem (re-read on `main` at `9227b64`)

- The sweep deletes a session after 10 minutes without a request (`SessionManager.maintain`, `SessionManager.java:248-253`,
  `cinefilm.transcode.expiry`), and while `hls/` is over its 5 GB cap it deletes sessions that have been idle for 2
  minutes (`evictIdle`, `:266-280`). Either way the session is gone from memory and every later request for it is
  404 `No such session` (`require`, `:301-308`).
- A paused film with a full buffer sends no request: hls.js has its 120 s banked and asks for nothing until the
  playhead moves. Nor does a laptop with its lid closed. On a train that is the stop at a station, or a nap between
  cities.
- On resume hls.js asks for the next segment, gets the 404, and `hlsjsEngine.js:124-128` calls `onSessionLost`.
  `useStream.js:121-132` then starts again from nothing: the engine is destroyed (the `<video>` empties, so the
  buffer is thrown away), the 1.5 s probe runs, a new session is made, hls.js loads its playlists, and the film
  waits for its first segment at the weakest moment. On the iPhone a segment 404 is a fatal `error`; the native
  engine's check fetches the master playlist, gets 404 (`nativeEngine.js:100-101`) and does the same.
- The health record counts it: one `recoveries` and one `reloadCount` for every pause longer than ten minutes.

## The change

Keep what is needed to rebuild a session that went quiet, and rebuild it on its next request, **under the same id**,
with the encoder restarted at the segment asked for. That is the spec `SessionService` validated against the
database when the session was made (the files, the audio stream in each, the cut list, the rungs, the start rung,
the pinned quality, the picture size), its files' size and modification time, and a little bookkeeping (start time,
last request, playhead, last rung). The spec is already in memory while the session lives; keeping it costs tens of
KB at most (a three-hour film's cut list is about 2,700 numbers). Because the id, the cut list and the rungs are the
same, every URL and playlist the player already holds stays valid, so the player never sees a 404 and keeps its
buffer. A session that was ended or replaced stays gone.

## Which endings can be picked up again

| Ending | Where | Afterwards | A later request for its id |
|---|---|---|---|
| No request for 10 minutes | `maintain` | **remembered** | rebuilt |
| Idle for 2 minutes while `hls/` is over its cap | `evictIdle` | **remembered** | rebuilt |
| The viewer left: `DELETE /api/sessions/{id}` (leaving the player, closing the tab, a quality or audio change, a recovery) | `end` | final; also forgets it if it was remembered | 404 |
| Admin's **Stop** (`DELETE /api/admin/transcoding/sessions/{id}`, the same `end`) | `end` | final | 404 |
| Replaced: a new session for the same edition (newest wins) | `create` | final; a remembered session of that edition is forgotten too | 404 |
| Pushed out at the cap of 3 by a new session, or by a rebuild | `create`, the rebuild | final | 404 |
| Unavailable (its encoder failed twice) when it went quiet | `maintain` | not remembered | 404 |
| Its file could not be read when the session was made | `create` | never remembered | 404 |
| Rebuilt, but its first encoder start failed | the segment request | final | 404 |
| Its file changed (size or modification time) or is gone when it is asked for | the rebuild | forgotten | 404 |
| Its file cannot be read just now (any other read error) | the rebuild | **still remembered** | 503 `Retry-After: 1` |
| Asked for 24 hours or more after its last request, or pushed out of the 8 remembered | the rebuild, `remember` | forgotten | 404 |
| The server stopped or restarted | `shutdown` (memory only; start-up already deletes every leftover `hls/` folder) | final | 404 |

A replaced session is final because the newest session for an edition wins. A pushed-out session is final for a
different reason: if it were remembered, a fourth player would make the sessions take turns pushing each other out
with no end, every turn starting an encoder. Final, the pushed-out player gets a 404 and makes a new session, which
its own recovery budget already bounds (`useStream.js:24`: more than 3 recoveries in a minute and it stops).

Only one session per edition can be live or remembered at a time: a new one forgets a remembered one of the same
edition under the same lock in which it replaces a live one, and a remembered one exists only because no newer one
was made. So a rebuild never meets a live session of its own edition.

## What each request returns

| Request | Live (as today) | Remembered, under a day, files unchanged | Remembered, a file unreadable just now | Final, forgotten, too old, changed |
|---|---|---|---|---|
| `GET /api/sessions/{id}/master.m3u8` | 200 | 200, the same text as before; no encoder started | 503 `Retry-After: 1` | 404 `No such session` |
| `GET .../{rung}/index.m3u8` | 200 | 200, the same text; no encoder started | 503 `Retry-After: 1` | 404 `No such session` |
| `GET .../{rung}/seg-{n}.ts` | 200, or 503 `Retry-After: 1` after the wait | the encoder starts at `n`, then as a live session | 503 `Retry-After: 1` | 404 `No such session` |
| `DELETE /api/sessions/{id}` | 204, ends it | 204, forgets it | 204, forgets it | 204 (idempotent) |

The 503 for an unreadable file has the body `{"error":"The film's file cannot be read right now"}`. A bad rung is
still 404 `No such rung` on the media playlist route and `No such segment` on the segment route, and a segment past
the end `No such segment`, as today.

## The rebuild, step by step

`view` (which serves both playlists) and `segment` run as today. Only when the id is not live does the rebuild run,
and then the request is tried once more:

1. **Under the lock, as today:** the live session is looked up. Found, the request goes on in that same locked
   section, so nothing can expire it between the look-up and its use. Not found, the lock is released and the
   rebuild starts. (One way: `require` throws a private subclass of `NotFoundException` that only it throws, with
   the same message; `view` and `segment` catch only that, try to rebuild, and if the rebuild made the session live
   run their body once more. A body fails at `require` before it has changed anything.)
2. **Under the lock:** if the id is not remembered, the original 404 stands. If its last request was 24 hours ago or
   more, forget it and 404.
3. **Outside the lock:** read the size and modification time of every file of the spec (`FileStamps`, the check the
   keyframe index uses to notice a changed file). This reads the media share, which can be slow or hang, so it never
   holds the lock every session shares.
4. **Under the lock again:** if the id became live meanwhile (another request rebuilt it), use it. If it is no longer
   remembered (ended, replaced), 404. If its last request is now 24 hours ago or more (a slow read crossed the day),
   a file is gone (`NoSuchFileException`) or any stamp differs, forget it and 404. If a file could not be read for
   another reason, keep it remembered and answer 503 `Retry-After: 1`: the share
   may be back in a second, and both players ask again on a 503. Otherwise: forget it, push out the least recently
   used session for good while 3 are live (as `create` does), delete the session's folder (in case the delete at
   expiry failed half way: `NioSegmentFiles` logs and carries on), and put back a **new** live session object under
   the same id: the same spec, folder (`hls/<id>`), start time (for Admin) and last rung; the playhead and the
   behind-deletion mark both at the remembered playhead; the time of this request as its last request; no crashes,
   not unavailable, nothing finished; and a run whose status is explicitly `STOPPED` with no process. Log one line
   at INFO with the id and how long it was quiet.

The placeholder run must be `STOPPED`: a new `Run` starts `RUNNING` (`TranscodeSession.java:34`), which would make
`ensureReaching` wait for an encoder that does not exist and `kill` throw on its missing process; and `FINISHED`
would let the sweep's `chainDue` start the next part of a split film. Every existing path treats a `STOPPED` run as
"no encoder": `stop`, `destroy`, `shutdown` and `info` skip it, `chainDue` refuses it, and `ensureReaching` restarts.

The rebuild itself never starts an encoder. A playlist request leaves it stopped. A segment request goes on through
the existing path: the segment is not on disk and the run is not running, so `ensureReaching` starts the encoder at
that segment exactly as a seek does. If that first start fails (`FfmpegTranscoder.start` throws when ffmpeg cannot
start or the rung folders cannot be made, for instance on a full cache disk), the rebuilt session is ended for good
and the request answers 404, so the player recovers as it does today instead of showing "Reconnecting…" against a
session that can never encode. A live session whose restart fails keeps today's behaviour (unavailable, 503).

A rebuilt session is a live session in every respect: it counts towards the 3, its encoder stops after 2 idle
minutes, it is deleted 10 minutes after its last request and remembered again, look-ahead and behind-playhead
deletion apply, and its crash budget starts afresh (one restart, then unavailable), since it can only be rebuilt
after 10 quiet minutes.

**The wait counts from arrival.** Today a segment request waits up to 6 s after its locked work. The rebuild adds a
read of the share before that, and hls.js allows 10 s to the first byte (`hlsjsEngine.js:36`). So `segment` notes
the time it was called and gives the waiter only what is left of the 6 s (never less than nothing). With the fake
clock standing still, existing tests still see a 6 s wait.

## The caps (`SECURITY.md` rule 4)

- **The session limit.** A rebuilt session counts towards the 3; at the cap it pushes out the least recently used
  session for good, exactly as a new session does, even when only a playlist request rebuilt it. There are never
  more than 3 sessions or 3 encoders. Pushed-out and replaced sessions are not remembered, so rebuilds cannot chase
  each other.
- **What is remembered.** Only the spec, the file stamps and the bookkeeping above, in memory: no files, no
  process. At most **8**, the eldest dropped first; one asked for 24 hours or more after its last request is never
  rebuilt and is dropped then. Nothing is written to disk or the database, and a restart forgets everything.
- **Nothing from the request names a file.** The request's id is pattern-checked by `SessionService` as today and
  only looked up in memory. The files are those of the spec, which came from the database and the library root when
  the session was made; a rebuild up to a day later uses those paths without asking the database again, and only
  while each file still has the size and modification time it had then. The folder is the one the server chose for
  that id.
- **No encoders without limit.** A rebuild starts no encoder; the segment request after it starts one through the
  same path (and the same cap) as a seek. A remembered spec is used once: it is a live session again and returns to
  memory only after another 10 quiet minutes.
- **A malformed request** for a remembered id (a rung the session lacks, a segment past the end) rebuilds the
  session before it is rejected with 404. It starts no encoder; at the cap it pushes out the least recently used
  session, which any signed-in client can already do by asking for a new session. Accepted rather than checking the
  request against the spec twice.

New wording of rule 4's sentence on caps, and what follows it:

> ... and resources are capped (3 sessions, idle stop, look-ahead cap, `hls/` cap, bounded threads, encoders killed
> on shutdown). A session that went quiet (no request for 10 minutes, or idle while `hls/` is over its cap) is kept in
> memory only as what rebuilding it needs: the spec the database gave when it was made, its files' size and
> modification time, and its start time, last request, playhead and last rung. At most 8 are kept, and none is
> rebuilt 24 hours or more after its last request. Its next request rebuilds it under the same id only while every
> file still has that size and modification time (the database is not asked again). A rebuilt session counts
> towards the 3, pushes out the least recently used session for good at the cap (even when a playlist request
> rebuilt it), and starts no encoder until a segment is asked for. A session that was ended, replaced, pushed out at
> the cap, unavailable or lost to a restart is never rebuilt.

## What the player sees while the encoder restarts

- **The first request after the pause** is normally a segment (VOD playlists are not reloaded). The server rebuilds
  the session, starts the encoder at that segment and waits for it, 6 s at most from the request's arrival. A
  segment is complete once the encoder has started the next one, so the wait is the encoder's start-up (the process,
  opening the file on the share, seeking to the cut) plus one segment's worth of encoding. With the spike's speeds
  (the whole transcoded ladder at about 3.5× real time on VAAPI, copy plus two rungs at 8–9×) a 4 s segment takes
  about 0.5–1.2 s and a 12 s one 1.5–3.5 s (estimate). Usually that is inside the 6 s, so the answer is 200;
  otherwise 503 `Retry-After: 1`. (workhorse's disks do not sleep: `operations.md`.)
- **hls.js** retries a 503 on a segment with its fragment policy (`hlsjsEngine.js:16-24` and `:36`: 8 retries, 1, 2,
  4, then 8 s apart, about 47 s in all, each attempt waiting up to 6 s on the server), and the engine tells the
  player nothing: a non-fatal error with a status is ignored (`hlsjsEngine.js:121-132`). The retry budget is the sum
  of the errors on every rung, and a rung's count is reset when one of its segments buffers
  (`error-controller.ts:318-321`, `base-stream-controller.ts:912-919` in hls.js 1.7.4), so after a pause the retries
  are usually all there. Meanwhile the film plays from its buffer (up to 120 s). A film paused with a full buffer
  carries on without a stall; one paused with little buffered (just after a seek, with the lid shut before it
  filled) stalls for those seconds. Only an encoder that produced nothing for about a minute and a half would use
  up the retries; hls.js would then try another rung, and with none left the player shows "Reconnecting…" (a
  reconnect, not a recovery), as for any other failure.
- **Safari on the Mac** plays through hls.js (it has MediaSource, `streamSource.js`), so it behaves as Chrome.
- **The iPhone's native player** asks for the next segment and gets the same 200 or 503 `Retry-After: 1`; the
  transcoding plan chose 503 for the encoder's waits because Safari treats a 404 as fatal. If AVPlayer gives up on a
  503 anyway, the native engine's own check fetches the master playlist, which is now 200 because the session is live
  again, and reloads the source at the same position: a reload, but no recovery and no new session. The native
  engine also reloads on its own when the film is played after a long pause with nothing buffered (below, "Left for
  afterwards"). Unverified on the device.
- **The playlists the player holds stay valid.** The rebuilt session serves the same master and media playlists
  byte for byte (they are written from the spec: the same cut list, so the same segment numbering, `#EXTINF` values
  and cut points; the same rungs with the same ids, sizes and `BANDWIDTH`; the same start rung and pinned quality).
  Segment URLs are relative (`seg-<n>.ts` under `<id>/<rung>/`), so they resolve to the same id.

## Files in `hls/`, and the segments the player already has

- At expiry the session's folder is deleted, as today, so a remembered session holds nothing on disk; the `hls/`
  cap and Admin's `hlsBytes` are unchanged by remembering.
- The rebuild deletes the folder again and the restarted encoder writes `hls/<id>/<rung>/seg-<n>.ts` from the
  requested segment on, as after a seek. The rebuilt session's files count towards the 5 GB cap like any live
  session's.
- The segments the player already buffered are never asked for again (unless it seeks back, which restarts the
  encoder there as any seek does). They join the new ones exactly as segments do today after every idle stop or
  look-ahead kill followed by a restart: a restart at segment `n` reproduces the from-zero run's segment `n` (the
  same cuts, forced keyframes, timestamps on the film's timeline; the spike results in `transcoding-plan.md`, and
  `TranscodeRealToolsTest` already proves a later start matches a from-zero run). No discontinuity is added. The
  restarted audio begins up to about 70 ms before the cut, which players trim, as after any seek.

## The health record

With the session rebuilt, the player gets 200 or 503, never the 404 that is the only way `recoveries` is counted
(`onSessionLost` → `recover()`), and no source change happens, so `reloadCount` does not move either
(`playback-health-plan.md`, "The record"). A resume after a long pause therefore counts **no recovery and no reload**
on hls.js. It can still count a stall (if the buffer runs dry) and, if the share answered very slowly, a timeout
(hls.js gives up waiting for a first byte after 10 s and asks again). How that is checked:

- **Backend, new tests:** no request for a quiet session within the day answers 404 (manager tests on the fake
  clock, and the HTTP test below).
- **Frontend, existing tests, unchanged:** `hlsjsEngine.test.js` "does nothing for a non-fatal error such as a 503
  hls.js is already retrying" (no `onSessionLost`, so no recovery and no reload) and "asks for a new session on a 404,
  once, without reconnecting"; `nativeEngine.test.js` "keeps waiting on a server error rather than reloading into it";
  `PlayerHealth.test.jsx` "counts a session rebuilt after it expired" (only the 404 counts a recovery).
- **By hand:** Admin's Playback health shows no recovery and no reload for the viewing (below).

## The client: no change

Nothing in `frontend/` changes. The player's 404 path stays for what still ends in a 404 (a session older than a
day, a replaced one, a restart). Phase 2 (`poor-link-plan.md`, being built in its own session) rewrites the client's
recovery path in `useStream.js`, `createSession.js` and `hlsjsEngine.js`; this pull request touches none of them, and
the two compose: phase 2 keeps a 503 retried and a 404 a lost session, and its retry of a status-0 failure helps the
first requests after a laptop wakes, while Wi-Fi and Tailscale reconnect.

### Left for afterwards (after phase 2 merges)

- **The native engine's stuck check after a long pause.** `nativeEngine.js` moves `lastMoveAt` only on `timeupdate`
  (`:58-61`), so a pause counts as time without progress. Played again after a long pause with nothing buffered
  (if iOS dropped its buffer), the first 1 s poll finds it "stuck" (`:111-116`) and reloads at once, before AVPlayer
  has asked for anything: a counted reload on the iPhone whatever the server does. The fix is to reset `lastMoveAt` on
  `play`. It is a client change in a file phase 2 also edits (its `online` wake-up), so it waits for phase 2.

## Code

Backend, package `gregochr.cinefilm.transcode`:

- **`SessionManager`**
  - Constants `MAX_REMEMBERED = 8` and `REMEMBER_FOR = Duration.ofHours(24)`, beside `MAX_SESSIONS`.
  - A new dependency, `FileStamps` (Spring injects `NioFileStamps`, the only bean); both constructors take it.
  - `create(spec)`: before taking the lock, read every part's stamp (an `IOException` for any part leaves the
    session without stamps: it plays as today but is never remembered); under the lock, forget every remembered
    session of the same edition, then as today.
  - `view(id)` and `segment(id, rung, n)`: the rebuild above when the id is not live; `segment` notes its arrival and
    hands the waiter what is left of `WAIT_MAX`; a rebuilt session's first failed start ends it and answers 404.
  - `end(id)` also forgets a remembered id. `shutdown()` also forgets everything remembered.
  - `maintain` (the 10-minute expiry) and `evictIdle` call a new `expire(session)`: `destroy` then `remember`.
    `create`'s replacement and cap eviction, the rebuild's cap eviction, the failed first start and `end` keep plain
    `destroy` (final).
  - `remember(session)`: skips an unavailable session and one without stamps; keeps the spec, the stamps, the start
    time, the last request, the playhead and the last rung, in insertion order; drops the eldest beyond 8.
  - Javadoc: the class, `view`, `segment`, and `sessions()` (live sessions in the order they were made or last
    rebuilt, no longer strictly oldest first).
- **`TranscodeSession`:** the stamps read at creation; a way to build a rebuilt session as in step 4; whether it is
  waiting for its first run since a rebuild; the `STOPPED` Javadoc gains "rebuilt, no encoder started yet".
- **`SessionSettings`:** the Javadoc of `expiry` (no longer "later requests get 404").
- **`application.properties`:** the comment above the session settings (no longer "then 404").
- **Unchanged:** `SessionService`, `HlsSessionController`, `TranscodingAdminController`, `TranscodeConfig`, every
  property's value, the API shapes, Admin's rows, everything in `frontend/`.

**Considered and not done:** carrying the keyframe index row's stamps through `KeyframeIndexService.Index` into the
spec instead of reading them again in `create`. It would tie the stamps to the cut list exactly, but it changes the
index's record, `SessionService` and tests in the `film` package that another session (phase 4, browsing) is likely
to edit. The second read costs one round trip to the share per session start, and the two reads are milliseconds
apart.

## Tests

`test-standards.md` in full: exact values, boundaries at one millisecond, exact error messages, no `any()` in
`verify`, no sleeps, the injected clock only. **Every new branch must be covered by the local suite**: the per-class
floor is 95% lines and 90% branches, the bundle floor is 99.8% branches against 99.87% measured, and the real-tools
test does not run on the Mac.

- **`SessionFakes`:** a `FakeStamps` (a fixed stamp for every path unless the test sets another; paths that throw
  `NoSuchFileException`, and paths that throw a plain `IOException`; an optional hook run once while reading).
- **`SessionManagerPauseTest`** (new; the fakes and the film of `SessionManagerTest`, 100 segments of 4 s):
  - an idle-expired session is rebuilt under the same id: the request for segment 40 starts one new run at 40 in
    `hls/<id>`; Admin lists the same id with its original start time and last rung; served once the waiter's script
    writes segments 40 and 41; 503 (`SegmentNotReadyException`) when it writes nothing;
  - the rebuilt session's `SessionView` equals the original (rungs, start rung, pinned, cut list, size);
  - a playlist request rebuilds without starting an encoder (no new plan; Admin's speed null, 0 ahead); the next
    segment request starts it; `end`, `shutdown`, a sweep and a push-out at the cap of a session rebuilt this way
    throw nothing and stop no encoder;
  - the rebuild counts as a request: the next sweep keeps it; 10 minutes after the rebuild it expires again and is
    rebuilt again;
  - the day: rebuilt at 24 h − 1 ms after the last request; 404 at exactly 24 h, with no encoder started; 404 and
    forgotten when the read itself crosses the day (24 h − 1 ms before it, 24 h after);
  - the eight: with 8 remembered the eldest is still rebuilt; with 9 the eldest is 404 and the second and the ninth
    are rebuilt;
  - a session evicted while idle to bring `hls/` under its cap is rebuilt;
  - final, each answering 404 `No such session` to `view` and `segment` with no encoder started: ended while live;
    ended while remembered (and `end` stays idempotent); replaced while live; a new session for the same edition while
    one is remembered (a remembered one of another edition is kept); pushed out by a fourth session; pushed out by a
    rebuild at the cap (and the rebuilt one is live); unavailable when it expired; after `shutdown`;
  - a rebuild forgets what it used: a rebuilt session later pushed out by three new ones, and separately one later
    replaced, answers 404;
  - files: a size change, a modification-time change and a missing file each give 404 and forget the session
    (proved by restoring the stamp and asking again: still 404); a file unreadable at creation means never
    remembered; a plain `IOException` at the rebuild answers 503 with "The film's file cannot be read right now", keeps
    it remembered, and the next request, once the file reads, rebuilds it;
  - a rebuild starts from an empty folder: segment files left in the folder after expiry (a delete that failed) are
    not served, and the encoder restarts at the requested segment;
  - the first start after a rebuild fails (`FakeTranscoder.failure`): 404, the session is gone, and a later request is
    404 too (not remembered); a live session whose restart fails is still unavailable (503), as today;
  - the crash budget starts afresh: a session that crashed once, expired and was rebuilt restarts after one crash and
    is unavailable after a second;
  - the wait counts from arrival: with the stamp hook moving the fake clock 2 s, the waiter is given 4 s; moving it
    7 s, it is given nothing and the answer is 503;
  - a split film (in `SessionManagerPartsTest` or here, with its fixtures): rebuilt by a playlist request with the
    remembered playhead within 3 minutes of the join, the sweep starts nothing; rebuilt by a segment request at 75 it
    restarts in the second part (that file, film-wide numbering); a change to the second part's file alone means 404;
  - races, with the stamp hook running the racing call **on another thread** and waiting for it with a bounded
    `CompletableFuture...get(5, SECONDS)` (it returns at once when the lock is free and times out, failing the test,
    if the read were done under the lock; not a sleep): another request rebuilding the same id during the read leaves
    one live session and one new run; an `end` during the read leaves 404; a `view` of another live session during
    `create`'s stamp read completes;
  - a rebuilt session counts towards the cap: with two others live, a fourth new session pushes out the least
    recently used.
  - Forgetting at 24 hours is visible only through the eight: if PIT keeps that mutant alive, either test it (an
    entry remembered later but last asked for earlier, so it is not the eldest) or list it as equivalent.
- **`HlsSessionPauseTest`** (new; the shape of `HlsSegmentPathSafetyTest`: standalone MockMvc over the real
  `HlsSessionController` and `TranscodingAdminController`, the real `SessionService` and `SessionManager` on the
  fakes, `ApiExceptionHandler`, real segment bytes in a `@TempDir`): after 10 quiet minutes the master and media
  playlists answer 200 with the same text, content type and `Cache-Control: no-store` as before; a segment answers
  503 with `Retry-After: 1` and `{"error":"The segment is not ready yet"}` while the restarted encoder works, then
  200 with its bytes; a file unreadable just now gives 503 `Retry-After: 1` with its own message on all three routes.
  After the viewer's `DELETE`, after Admin's Stop, after a newer session for the edition, a day later, and after the
  file changed, all three routes answer 404 `{"error":"No such session"}`. (The 401 and missing-header cases are the
  existing slice tests'; no route is added.)
- **`TranscodeRealToolsTest`** (`real-tools`, runs in CI where ffmpeg is installed; skips on the Mac): a session that
  expired from idleness carries on under the same id: segments 0 and 1 fetched, the manager's clock moved 10 minutes
  (a clock made from the real one plus a shift the test sets) and swept, then segments 2 to the last of both rungs are
  served under the same id, decode, and start at the same PTS as a from-zero reference run.
- **Existing tests that change:** the four places that construct `SessionManager` pass a `FileStamps`
  (`SessionManagerTest`, `SessionManagerPartsTest`, `HlsSegmentPathSafetyTest`: the fake; `TranscodeRealToolsTest`:
  `NioFileStamps`). `SessionManagerTest.theSessionIsDeletedAfterTenMinutesAndLaterRequestsGet404` keeps its
  boundary and the folder deletion, and its 404 moves to the final endings above, because a later request now
  rebuilds.
- **Mutation:** PIT once, at the end, on `SessionManager` and `TranscodeSession` only, never while a gate runs; no
  survivor in the new code without a reason.

## Gates

- `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`
  by exit code; JaCoCo 95/90 per class and the bundle floor; SpotBugs; Checkstyle.
- No frontend file changes, so the frontend gate is CI's; nothing here touches the integration package or a
  migration. CI is the only proof of the real-tools test (ffmpeg) and of diff coverage.
- `codex review --uncommitted` in Sonnet's worktree before the commit, until a run finds nothing new
  (`dev-and-testing.md`, "Local Codex review before the PR").
- `changelog.d/20261111-sessions-survive-pause.md` (`### Fixed`).

## Documents changed in the same pull request

This plan; `SECURITY.md` rule 4 (above); `transcoding-plan.md` "Sessions" (expiry); `foundation-plan.md`, the
session routes' 404; `network-resilience-review.md` (item 7 built, its phase list); `dev-and-testing.md`
"Poor-network check (by hand)" gains the long pause.

## Checked by hand by the owner

In Chrome on the Mac, a film on Auto:

1. Play for a minute or two so the buffer fills (DevTools, Network: the segment requests stop once about two
   minutes are banked).
2. Pause for more than ten minutes, or close the lid for longer. Admin's Transcoding panel drops the session after
   ten minutes.
3. Play. The film carries on at once from its buffer: no "Reconnecting…", no spinner. In DevTools the next segment
   request takes a few seconds (perhaps one or two 503s) and then segments flow under the **same** session id, with
   no new `POST …/sessions` and no new `master.m3u8`. Admin's Transcoding panel shows the same session again.
4. Admin, Playback health: that viewing shows no recovery and no reload.
5. On the iPhone: pause for more than ten minutes, then play. It should carry on with no recovery; a reload there
   is the native engine's own check (see "Left for afterwards").

## Not verifiable here

The encoder's real restart on dockermacmini (VAAPI, the SMB share: the time to the first segment), Safari on the Mac,
the iPhone's native player (how AVPlayer treats a 503 that lasts a few seconds, and whether iOS keeps the buffer
across a long pause or a locked phone), and a real closed lid (the page asleep, the network and Tailscale coming
back). CI proves the real ffmpeg restart under the same id on the software encoder.

## Risks

- A file replaced by one of exactly the same size and modification time is not noticed; the keyframe index has the
  same blind spot (`KeyframeIndexService.cached`).
- A share that hangs makes the rebuild's read hang with it. The lock is not held, so other sessions carry on, but
  that request answers late; hls.js gives up on it after 10 s without a first byte and asks again (a `timeouts`
  count in the health record, not a recovery).
- A request racing the rebuild sees either the rebuilt session or a 404, never a half-built one: the rebuild is
  decided and done in one step under the lock, and the read outside it is checked again against what is remembered
  then.

## What the adversarial review changed

- The placeholder run was underspecified: a new `Run` starts `RUNNING`, and `FINISHED` would chain a split film's
  next part. It is now explicitly `STOPPED`, with tests for every path that meets it.
- A read error no longer forgets a good session: only a missing file or a different stamp does; any other error is a
  503 and the session stays remembered.
- The race tests ran the racing call on the same thread, where the reentrant lock lets it through either way; they
  now use another thread with a bounded wait, so a read done under the lock fails them.
- No gap between finding a live session and using it, and the step after the read checks "still remembered", not
  "the same entry".
- The 6 s wait counts from the request's arrival, so a slow read does not push the answer past hls.js's 10 s.
- A rebuilt session whose first start fails ends with a 404 instead of an endless "Reconnecting…".
- The health claim is "no recovery and no reload", not "nothing but a stall".
- Tests added: a rebuild forgets what it used, forgetting proved by asking again, a fresh crash budget.
- After the build, the local Codex review: the age is checked again after the read, so a slow read of the share
  cannot rebuild a session whose last request is a day old.
- Fixed: the media route's "No such rung", "deleted 10 minutes after its last request", the share's disks never
  sleep, the reason a pushed-out session is final, the hls.js line numbers, the wording of `SECURITY.md`, two comments
  the plan had called unchanged, and the native engine's stuck check recorded as the one client follow-up.
