# Stop making a poor link worse — plan (phase 2 of the network resilience review)

Written 2026-11-11. Phase 2 of `network-resilience-review.md`: its items 1, 2, 3, 4 and 8, plus the status-0 retry
and the `online` retry from its "Small" list. One pull request, built by Sonnet under the usual gates. Phase 1 (the
playback-health record, `playback-health-plan.md`) is in: PR #88 and #91. This phase does not depend on its
measurements; it fixes places where the player treats a poor link worse than it should.

**Not in scope:** items 5 and 6 (the 240p floor, a deeper buffer) are phase 3 and waited for the owner's open
decisions 1 and 2 (made 2026-11-12: `train-link-plan.md`); item 7 (sessions that survive a pause) shipped separately in #95. Open decision 3 (keep the 1.5 s probe for the first play of a browser
session) is answered the recommended way by item 8: the probe stays, but only when there is nothing to reuse.

## What the code does today (re-read after #91)

- `createSession.js:24-32` returns null for **any** failed request (a split film's 503 aside), and `useStream.js:161-163`
  then direct-plays (`failToDirect`). The same `run()` serves a **recovery** after an expired session
  (`recover()`, `useStream.js:121-132`), so one dropped request at the weakest moment means direct play for the rest
  of the film. The request has no timeout.
- A failed `import('hls.js')` returns null (`streamSource.js:42-46`) and `useStream.js:216-221` direct-plays.
- `hlsjsEngine.js:17-24` sets `maxLoadTimeMs` to twice the first-byte limit: **20 s** for a segment.
- `probe.js:48` returns null when nothing was measured; `useStream.js:153` then sends no `probeKbps` and
  `StartRung.java:25-27` answers the **top** rung. Split films never probe (`useStream.js:145`), so they always do.
- Every Auto start probes (`useStream.js:144-149`): first play, each recovery, each audio change, each next film.
- `server.compression.mime-types=application/json` only (`application.properties:41-45`).

## Facts this plan rests on (read in hls.js 1.7.4, the version shipped, `node_modules/hls.js/src`)

- **Segments have no loader-level retries** (`fragment-loader.ts`: `getLoaderConfigWithoutReties`). Each attempt is a
  new loader, built from `config.fLoader` when one is set (`new FragmentILoader(config)`), else the default XHR
  loader; its `stats` object becomes `frag.stats`. Whatever the outcome (`onSuccess`, `onError`, `onTimeout`,
  `onAbort`), `resetLoader` clears `frag.loader` and destroys the loader, but `frag.stats` keeps that attempt's
  object through hls.js's retry wait. The stream and error controllers decide retries.
- **A loader's `onTimeout` is the escalation path:** it becomes a `fragLoadTimeOut` error, which the error controller
  retries with `fragLoadPolicy.default.timeoutRetry` (backoff), the ABR controller samples for its bandwidth estimate
  (`abr-controller.ts`, `onError`), and which after the retries switches rung (Auto) or goes fatal.
- **Playlists:** the master playlist retries inside its loader; a media playlist raises one `ERROR` per attempt and
  its controller retries. `loadSource` and `startLoad` both begin with `stopLoad()`, which stops every network
  controller, the playlist loader first (`hls.ts`): it destroys a playlist request in flight, and its retry timer,
  without a callback (`playlist-loader.ts`, `destroyInternalLoaders`). So calling either again restarts that request
  from nothing (found in the second review; the first draft of this plan read the "ignore … ongoing request" check,
  which never gets the chance to run).
- **Timing** (`xhr-loader.ts`): a timer at `maxTimeToFirstByteMs` (10 s), re-armed at HEADERS_RECEIVED to
  `maxLoadTimeMs` from the request's start; `stats.loading.first` is set then (and also when a request fails).
  **`stats.loaded` is updated on every XHR progress event**, so a loader can see live bytes. The default loader is
  XHR (`progressive: false`).
- **`startLoad()` begins with `stopLoad()`**, which aborts the segment in flight. On every fatal error the error
  controller stops loading (`error-controller.ts`, `onErrorOut`); it is registered last in hls.js's constructor, so
  it has run before any listener the engine adds.
- An abort (hls.js's own ABR abandon, or `stopLoad()`) raises an `ERROR` with details `aborted`, type
  `networkError`, no response (`handleFragLoadError`), which the error controller ignores and today's recorder counts
  as a `netErrors`.
- hls.js's abandon never runs for a segment of the **lowest** rung, with Auto off (`autoLevelEnabled` false), or while
  the video is paused or has no data yet. On the rung a poor link ends up on, a stalled segment today simply waits
  for the 20 s ceiling.
- **Status 0 while `navigator.onLine` is true** is retried only when no other rung qualifies (the lowest rung under the
  step gate, `base-stream-controller.ts`); otherwise the error controller penalises the rung (`loadError++`) and
  switches, and its search for a candidate can wrap upward to the cap. With `errorPenaltyExpireMs: 0` (the default) a
  penalised rung is left out until a fragment of it buffers or `stopLoad()` (`error-helper.ts`, `isPenaltyExpired`):
  one blip can cost a rung for the rest of the film.
- **`RetryConfig.shouldRetry(retryConfig, retryCount, isTimeout, loaderResponse, retry)`** is a supported hook, asked
  by the error controller (and, inside the loader, for the master playlist) with hls.js's own answer as `retry`.
- **Offline** (`navigator.onLine === false`): hls.js waits ("Waiting for connection") **without a fatal error**, marks
  that `ERROR` with `reason: 'offline'` (segments and playlists alike, before the engine's listener runs) and resumes
  by itself on its next tick once the browser says it is online. Today the engine therefore shows no
  "Reconnecting…" while the browser is offline.

**Browsers and a failed `import()`.** The HTML spec now says a module fetch that failed on the network is fetched
again on the next `import()` (whatwg/html#10327). Firefox 155 does this; Chrome is reported to from 156 (not
confirmed in its release notes); Safari still caches the failure (WebKit bug 319492, open), so there a retried
`import('hls.js')` fails at once, without a request, until the page is reloaded. Hence the chunk retry asks the
server before it waits.

## 1. A transient failure retries; only a refusal direct-plays

### The session request (`createSession.js`, new `retry.js`, `useStream.js`)

`createSession(create, options)` keeps asking until it has a session or a definite answer. `create(signal)` now
receives an `AbortSignal` and builds the request body **on each attempt** (so an audio track chosen while it retries
is the one asked for; `Player.jsx` restarts only an HLS stream, and the mode stays `loading` meanwhile).

What the server can answer (checked in review): 201; 400; 401 (signed out; also after a restart, which forgets
sign-ins); 403 only without the `X-CineFilm` header; 404; 409; 503 only for a split film still being prepared; 500 for
an unhandled exception. Caddy and `tailscale serve` answer 502 when the app is down or restarting.

| The attempt ends with | Meaning | What happens |
|---|---|---|
| 201 | a session | returned |
| 409, 400, 403, 404, 410 or any other 4xx not listed below | an answer asking again cannot change | give up at once, as today: direct play (a film in one piece) or the "can't be played" card (split) |
| 500 | the app's own error | ask once more; a second 500 gives up as above (a 500 is a bug, and direct play works at once today) |
| 503 for a **split** film | still preparing its later parts | as today: again after 1 s, at most 15 tries, PREPARING, then the card |
| no answer: the fetch rejects, the server cannot be reached (below), or the body cannot be read | the link | ask again without limit, under "Reconnecting…" |
| 502, 504, a 503 for a film in one piece, 408, 429 | a proxy whose app is down or restarting (the app never answers these for a film in one piece) | ask again without limit, under "Reconnecting…": direct play would fail the same way, behind the same proxy, and a failed direct play leaves a spinner that never clears (the `<video>` has no error handling) |
| 401 | signed out; the sign-in card is up | wait, with no notice and nothing counted, until the viewer signs in, then ask again at once |

- **Backoff:** 1, 2, 4, 8 s, then every 8 s (hls.js's own `retryDelayMs` 1000 / `maxRetryDelayMs` 8000).
- **Woken early:** for the whole call (not only while waiting) the window's `online` event and `SIGNED_IN_EVENT`
  (`api/client.js`) are heard; one that arrives during an attempt ends the next wait at once. Cleanup of the stream
  effect ends a wait at once, with no further attempt.
- **A request that seems lost is checked, not timed out.** A film in one piece that has not been indexed is indexed
  inside the request (`SessionService`), which can take minutes on a healthy link; a fixed timeout would show
  "Reconnecting…", count timeouts and pile up attempts on the file's lock, one of which could replace the live
  session. Instead, 5 s after an attempt began and every 15 s after that (`FIRST_CHECK_MS`, `SESSION_CHECK_MS`)
  while it is still pending, the client asks
  `GET /api/session` with its own 5 s limit (`REACH_TIMEOUT_MS`; that path never raises the sign-in card, and it is
  the cheap endpoint #93's `latency.js` already times, which only ever reads true round trips). An answer from the
  app (any status but 502, 503 and 504, which are a proxy's) means the server is there and working on it: the
  attempt is left alone, PREPARING stays. No answer, or a proxy's 502, 503 or 504, means the server cannot be
  reached: the attempt's own controller is aborted, reported once as a timeout, and retried as above. A check stops,
  and its result is ignored, as soon as its attempt settles or the run is cancelled, so a late "no answer" can never
  abort a request that already succeeded (a second create would replace the session).
- The stream effect's cleanup does **not** abort an in-flight request: a session the server makes after the viewer
  left is still deleted when its answer arrives, as today.
- **After cancellation nothing calls back.** `createSession` checks `isCancelled()` straight after each attempt ends
  and returns null without any callback, and `useStream` ignores `onRetrying` and `onNoAnswer` once its run is
  cancelled; the next run's `clearReconnecting()` resets the flag. Otherwise a cancelled call ending up to 15 s
  later could switch off the next run's notice, and StrictMode's doubled effect in development would record two
  reconnects for one.
- **"Reconnecting…"** shows from the first failure that is asked again under it until the call ends (a session, a
  refusal, cancellation), and goes off when an answer shows the server is there (a split film's 503, a 401). It also
  shows when the probe found the server unreachable (§3), before the request is sent. `useStream` holds it with one
  switch per run that acts only on a change (as `health.reconnecting` already does): the probe's "on" and the
  request's are one episode, `createSession` may say `onRetrying(false)` on a 401, a split film's 503 and at its end
  whether or not it said `true`, and `useStream` turns it off when `createSession` returns. It is the existing notice
  (`ReconnectingNotice`, `role="status"`, polite): no new copy. Before the first frame it takes the place of
  PREPARING, as it takes the place of BUFFERING today.
- `createSession` stays pure: `wait`, `isCancelled`, `reachable`, `onRetrying(on)`, `onNoAnswer({ timeout })` (for the
  record, below) and the limits are injected. It still returns the session or null; `useStream` tells "refused"
  from "cancelled" by its own `cancelled` flag, as today.
- **`retry.js`** (new, pure apart from timers and listeners): `backoffMs(attempt)`; a per-call waiter that hears
  `online` and `SIGNED_IN_EVENT` for its lifetime and offers `wait(ms, { signal })` (resolves after `ms`, at once if
  a wake-up came since the last wait began, or when `signal` is or becomes aborted) and `untilSignedIn({ signal })`,
  then removes its listeners and clears its timers when disposed; and `reachable(check, { timeoutMs })`, true when
  `check(signal)` gets an answer from the app within `timeoutMs`: it resolves, or rejects with an `ApiError` whose
  status is not 502, 503 or 504 (`request()` throws an `ApiError` for every status that is not OK, a proxy's too).
- **Recovery** goes through the same loop, so an expired session rebuilt on a weak link retries instead of falling
  back. `MAX_RECOVERIES` (more than 3 expired sessions in a minute) still abandons, unchanged.

### The hls.js chunk (`streamSource.js`)

When `loadHls()` fails, `startStream` asks whether the server answers at all (`reachable`, as above):

- **It answers:** the link is up, so the failure will not go away by waiting (Safari's remembered failure, or a
  chunk that a deploy has replaced). Try `loadHls()` once more at once (Chrome and Firefox fetch it again); if that
  fails too, `startStream` returns null and the film direct-plays as today (`fellBack`).
- **No answer:** "Reconnecting…" (`onRetrying`), wait with the same backoff and wake-ups, check `isCancelled()`, and
  try again, without a cap: direct play would fail the same way.

The chunk is fetched once per hls.js version and then cached for a year, so this path is rare. A browser with no HLS
at all (no Media Source and no native HLS, or `Hls.isSupported()` false) still direct-plays at once.

## 2. A segment gets 120 s, and one that stalls times out (`hlsjsEngine.js`, new `noProgressLoader.js`)

- `fragLoadPolicy`: `maxTimeToFirstByteMs` 10,000 (unchanged), `maxLoadTimeMs` **120,000** (hls.js's default; was
  20,000). Playlists and the manifest keep 10,000 / 20,000: they are small (about 5 KB once compressed).
- **No-progress limit, inside the segment loader.** `createNoProgressLoader(Base, { noProgressMs, watchMs, now })`
  (new `src/player/noProgressLoader.js`) returns a loader class that wraps one `Base` loader; hls.js builds one per
  attempt. `startHlsJs` sets `fLoader` to it with `Base = Hls.DefaultConfig.loader` (the XHR loader hls.js would use
  anyway); `startHlsJs` has `Hls` and `hlsConfig` does not, and existing tests call `hlsConfig` alone. Each of these
  is required by how hls.js 1.7.4 drives a fragment loader (second review), and each is tested:
  - The inner loader is built **in the constructor**: hls.js reads and writes `loader.stats` before it calls `load`
    (`fragment-loader.ts`, "Assign frag stats").
  - `stats` is the inner loader's very object (hls.js assigns it to `frag.stats`); `load`, `abort`, `destroy`,
    `context` and the optional `getCacheAge` and `getResponseHeader` pass through.
  - `onProgress` is passed to the inner loader **only when hls.js gave one**. With `progressive` off it gives none,
    and the XHR loader calls one on success when it is there, before `onSuccess`.
  - Each callback it passes on (`onSuccess`, `onError`, `onTimeout`) is **its last action, with `stats` read before
    the call**: hls.js destroys the loader inside that callback (`resetLoader`), which empties the inner loader's
    `stats`. `destroy` is safe during that callback and when called twice.
- **When it fires.** A check every `watchMs` (1,000). The first check that finds the response begun
  (`stats.loading.first > 0`, set when the headers arrive) counts as a change, as does each check that finds
  `stats.loaded` moved; a check that finds `noProgressMs` (**12,000**) gone since the last change ends the attempt
  exactly as the XHR loader's own timeout does: it aborts the inner request without reporting an abort (the inner
  loader's `onAbort` is swallowed), then calls `callbacks.onTimeout(stats, context, null)` once. Headers that take
  9 s therefore still get 12 s of silence, not 3.
- Its timer starts with `load` and stops on success, error, timeout (its own or the inner loader's), abort and
  `destroy`, so it watches exactly one attempt and never a request that has already failed. Before the first byte
  the inner loader's own 10 s limit applies; a slow but live transfer (a byte within 12 s) is left alone until the
  120 s ceiling.
- hls.js then handles it as any `fragLoadTimeOut`: its backoff, a bandwidth sample, a rung switch after its retries,
  and a fatal error, which the engine's reconnect loop answers. Nothing is fired from outside hls.js, the first
  segment of a film needs no special case, and the health record counts it as a timeout through the existing rule.
- An `ERROR` for a segment that timed out (`fragLoadTimeOut`, either limit) counts as trouble for the step gate
  (`gate.trouble()`), so Auto does not step up straight after it.

## 3 and 8. The start rung: reuse, probe, or start low (`useStream.js`, `throughput.js`, `probe.js`)

For **Auto** only (a pinned quality sends no `probeKbps`, as today):

1. A speed measured in the last **10 minutes** (`REMEMBER_MS` = 600,000; reusable when its age is from 0 to 599,999
   ms) and since the network last changed is reused: this player's own measurements, else the speed remembered in
   `sessionStorage` from another film. No probe.
2. Otherwise a film in one piece probes. A probe that ran and measured nothing sends **`probeKbps: 0`**, which
   `StartRung.pick` already answers with the lowest rung (no backend change).
3. Otherwise (a split film, which has no single file to probe) `probeKbps: 0`: it starts low and Auto steps up one
   rung a minute. At home with nothing measured in the last 10 minutes that is the cost (three minutes to Original);
   the owner chose it ("use the remembered speed or start low").

- **The probe's 1.5 s now starts at the first byte** (found in review). Today the box starts with the request, so
  a slow first byte eats it; the library is workhorse's SMB share, whose disk may be asleep, and at home that would
  now mean "measured nothing", the lowest rung and minutes of stepping up. `measureThroughput` waits up to
  `FIRST_BYTE_MS` (10,000, hls.js's own first-byte limit for a segment) for the first byte, then counts for
  `PROBE_MS` (1,500) as before. A link that hangs must not hide behind that wait: with no first byte at 5 s
  (`PROBE_CHECK_MS`) the probe asks the injected `checkReachable()` (`useStream` passes the session request's check,
  with its 5 s limit). No answer ends the probe with nothing measured and calls the injected `onUnreachable()`, and
  `useStream` turns "Reconnecting…" on at once (§1), so on a link that hangs the notice shows at most 10 s after
  opening, and the session request then runs under it. An answer (the server is there, its disk waking) lets the
  probe wait on to 10 s. A check that answers after the first byte, or after the probe has ended, changes nothing.
  No first byte in 10 s, a server that cannot be reached, a failed request, an empty body or nothing after the first
  byte is "measured nothing". With no `checkReachable` the probe simply waits the 10 s. The probe costs its first
  byte's wait more than today; with reuse, most starts skip it.
- **The figure used is cautious:** the start rung (sent as a whole number) and the remembered speed use the **lower
  middle** of the samples, a new function beside `median()`, not the mean of the middle two that `median()` gives
  for an even count (`throughput.js:17`; `median()` itself stays as it is for the indicator): a probe of 100,000 and
  one segment at 500 would otherwise remember 50,250 and start at the top.
- **What is remembered:** `sessionStorage['cinefilm.speed'] = {"kbps": <whole number>, "at": <Date.now()>}`, written
  after each **measurement** (a probe that measured something, each segment) from this player's samples. A reused
  figure is never written back and never added to the samples, so its age stays honest. Reading tolerates a missing,
  malformed, non-positive or future-dated entry (null) and a storage that throws; `useStream` keeps its samples in a
  ref mirrored to state (no side effects inside a state updater: StrictMode calls those twice), so reuse inside a
  film works when storage does not.
- **A network change forgets it.** On the window's `offline` and `online` events (a laptop moving from home Wi-Fi to
  a phone's hotspot drops one network before joining the next) the remembered speed is removed and a module-level
  network counter goes up. `useStream` keeps the counter's value beside its samples and discards them when it has
  moved, so a home figure never starts a hotspot at the top (hls.js will not abandon a segment before the first
  frame). `navigator.connection`'s `change` is not used: Chrome fires it whenever its estimate of the link moves,
  which on a train is constantly, and each would throw away the speed that item 8 exists to keep. The listeners are
  added when the module loads (nothing else happens there), and the module exports a reset for tests.
- **The indicator and the slow-connection card** (found in review): the card is the handover's state "Remote on a
  slow connection" (§5, decision 11), but today it opens for any Auto rung at 480p or below (`Player.jsx`, the
  `useSlowCard` call), and with nothing measured the pill calls anything below the top rung Remote
  (`indicator.js:12`). A cautious low start at home would then claim "You're on a slow connection". So the card
  opens only for a slow rung **and** a known speed below 20 Mbps: `indicatorModel` gains `measuredSlow`
  (`slow && kbps !== null && kbps < HOME_KBPS`) and `Player.jsx` passes it to `useSlowCard` in place of `slow` (one
  word; the call keeps #93's `!questionDue`, which makes it take turns with the "Are you on a train?" card). The
  `kbps` that `useStream` returns is the median of the samples, else a speed reused for this start, else null, so
  the iPhone (which measures no segments) and a reused home speed read correctly at once. A cautious low start at
  home with nothing known still reads "Remote" for the few seconds before its first segment is measured.

## 6. Status 0 and the `online` event

- **`hlsjsEngine.js`:** every `errorRetry` gets `shouldRetry: retryNoAnswer`, exported and pure:
  `retry || (loaderResponse?.code === 0 && retryCount < retryConfig.maxNumRetry)`. A request that got no HTTP answer is
  retried with backoff like a 5xx, whatever `navigator.onLine` says, instead of costing a rung. Timeouts and HTTP
  answers keep hls.js's own decision.
- **`errorPenaltyExpireMs: 60000`:** when retries do run out and hls.js switches rung, the penalised rung may be chosen
  again after a minute (the same minute the step gate asks for) instead of never. One line; without it a long dead
  zone still costs a rung for the rest of the film.
- **One restart per fatal error** (found by the plan's reviews). Today, after a fatal network error, the engine
  restarts hls.js every 5 s until a segment arrives: `startLoad` once there are levels, else `loadSource`. Both
  begin with `stopLoad` (see the facts), so each restart aborts the request the one before made: once
  "Reconnecting…" is up, a segment that needs more than 5 s (a 4 s segment of 360p on "Train, bad" needs about 10),
  or a master playlist that does, can never arrive. Instead each fatal network error schedules **one** restart 5 s
  later, replacing one already pending (`startLoad` at the position below once there are levels, else `loadSource`).
  After it, hls.js retries with its own backoff and nothing more is scheduled until its next fatal error. A segment
  that loads ends "Reconnecting…" and cancels a pending restart. The engine no longer polls; it reads no
  `loadingEnabled`.
- **`online`:** while a restart is pending, the window's `online` event makes it at once. Otherwise it does nothing:
  hls.js's own backoffs, and a request in flight, are left alone. The listener is removed on `destroy()`.
- **Offline stays quiet (decided, against one review suggestion).** While the browser knows it is offline, hls.js
  waits without a fatal error and resumes by itself, so the engine shows no "Reconnecting…": `Player.jsx` shows the
  notice whenever `reconnecting` is set, even over a picture still playing from its two-minute buffer, and a notice
  over a moving film on every tunnel would break "fast and quiet". The film plays on from its buffer, BUFFERING shows
  only if that runs dry, and the record shows it as a stall. "Reconnecting…" stays for a server that cannot be
  reached while the browser believes it is online (a train's Wi-Fi with no uplink), which is what hls.js's fatal
  error means. The hand checks below say which is which.
- **Where a retry restarts** (found while planning; one line): today `hls.startLoad(video.currentTime)`. Before the
  first frame the video has no metadata and reads 0, so a reconnect on a resumed film fetches a segment at 0:00 that
  the player's resume seek (`Player.jsx`, `onLoadedMetadata`) then throws away: on a poor link, a wasted segment.
  Instead: at the playhead once the video has metadata (`video.readyState > 0`), else at the start position hls.js
  was given (`hlsConfig(startSeconds).startPosition`; -1 loads from 0).
- **`nativeEngine.js` (iPhone):** the same: `online` while reconnecting checks at once, ignoring the 5 s spacing (a
  check already in flight is left to finish). Not verifiable here: covered by unit tests only.

## 4. Compress text (`application.properties`)

`server.compression.mime-types=application/json,text/html,text/css,text/javascript,application/javascript,image/svg+xml,application/vnd.apple.mpegurl,text/vtt`
(min size stays 2 KB; segments, streams and artwork stay binary and untouched). Tomcat compresses these in Spring,
so the Caddy route and the `tailscale serve` fallback both benefit. Checked in review against Tomcat 11.0.27: types
match by prefix (a `;charset` suffix is fine), `.js` is served as `text/javascript`, the 2 KB minimum applies only
when a Content-Length is set, and a response with a strong ETag is never compressed (none of these have one). What
it changes in practice: each rung's media playlist (`index.m3u8`, tens of KB for a long film), the JavaScript and
CSS bundles, and subtitles. The master playlist, `index.html` and the SVG favicon are under 2 KB and stay plain;
`text/html` and `image/svg+xml` are listed for when they grow. The web manifest is left out: Boot 4.1.2 has no
mapping for `.webmanifest`, so it is served as `application/octet-stream` (and is 324 bytes).

**Test** (`config/CompressionTest`, `@SpringBootTest(webEnvironment = RANDOM_PORT)` like `SessionCookieTest`, because
compression happens in Tomcat's connector, which MockMvc bypasses; `java.net.http` sends no `Accept-Encoding` of its
own and does not decompress): with `Accept-Encoding: gzip`, a stylesheet and a script under `/assets/` (test
resources over 2 KB; `/assets/**` needs no sign-in) and a rung's playlist (signed in, `SessionService` a
`@MockitoBean` returning a playlist over 2 KB) come back with `Content-Encoding: gzip` and gunzip to the exact bytes;
the same stylesheet without `Accept-Encoding` comes back plain; a segment (`video/mp2t`, a temp file over 2 KB) is
not compressed.

**Security:** compression over TLS leaks something only where one response mixes a secret with input an attacker
controls (BREACH). None of these do: playlists and static files carry no secret, the app's CSRF defence is a header
rather than a token in a response, and JSON has been compressed since the start.

## The health record under the new behaviour

The recorder's code does not change except where the engine reports; its definitions do. `playback-health-plan.md`
is updated in the same PR:

- **`fellBack`:** direct play because streaming failed: the server refused the session (409, another 4xx, or a 500
  twice), the player's code would not load although the server answered, this browser has no HLS engine, or the
  stream was abandoned (a fatal error hls.js cannot recover, or more than 3 expired sessions in a minute). **A request
  that got no answer, or a proxy's 502, 503 or 504, is asked again, never a fall back.** Still not when Original was
  chosen for a film without a copy rung, nor when the worker is offline; never for a split film.
- **`reconnectCount`, `reconnectMs`:** "Reconnecting…" episodes and their time, now also while a session request or
  the player's code is asked again, or after the probe found the server unreachable (one episode with the request
  that follows); before the first frame this time lies inside `startupMs`, during a recovery inside the reload's
  time. Waiting for a sign-in is not a reconnect.
- **`recoveries`:** unchanged event; a rebuild the server refuses also shows as `fellBack`, one that cannot reach the
  server retries under "Reconnecting…" (and shows in `reconnectCount`).
- **`netErrors`, `timeouts`:** requests of the player's own that got no HTTP answer: hls.js's, one per `ERROR` whose
  details name a load that failed or ran out of time (they end `LoadError` or `LoadTimeOut`: each failed segment
  attempt, each failed media-playlist attempt, the master playlist once its loader's retries are spent), and each
  session request that got no answer. `timeouts` when the request ran out of time (details end `LoadTimeOut`, which
  includes a segment stopped by the 12 s no-progress limit, or a session request abandoned because the server could
  not be reached), `netErrors` otherwise. Not counted, because no request of ours can be seen failing: the player's
  code failing to load (Safari fails it again without a request), and the probe (as today). Neither: an answer
  with an HTTP status, a load aborted on purpose (details `aborted`: hls.js's own abandon of a slow download, or a
  `stopLoad`), and a playlist that arrived but could not be read (`manifestParsingError`, `levelParsingError`,
  `levelEmptyError`: a hotel's sign-in page answering 200 is an answer). Today the engine counts every network
  error with no status; this narrows it to what the field says.
- **`reloadCount`, `reloadMs`:** unchanged; neither the no-progress limit nor the engine's reconnect replaces the
  source.

## Existing tests that change, deliberately

| Test | Today | Becomes | Why |
|---|---|---|---|
| `createSession.test.js` "gives up at once on any failure for a film in one piece, 503 included" | null on 409, 404, 400, 503 | null on 409, 404, 400; a 503 for a film in one piece asked again | item 1 |
| `createSession.test.js` "does not retry a failure that is not an API error" | a `TypeError` gives up | a `TypeError` is asked again until it works | item 1 |
| `PlayerStreaming.test.jsx` "direct-plays when the session is refused with %s" | 409, 503, 404, 400 | 409, 404, 400 | a one-piece 503 is a proxy's |
| `PlayerStreaming.test.jsx` "leaves probeKbps out when the probe measured nothing" | no `probeKbps` | `probeKbps: 0` | item 3 |
| `PlayerStreaming.test.jsx` "a new audio track on a direct-played film only saves the choice" | reaches direct play with a 503 | with a 409 | the same |
| `PlayerStreaming.test.jsx` "a new audio track makes a new session with that audioStreamId at the current position" | body without `probeKbps` | `probeKbps: 0` | nothing was measured, so the restart probes and measures nothing |
| `PlayerStreaming.test.jsx` "creates a new session at the current position, not the page-load resume position" | body without `probeKbps` | `probeKbps: 0` | the same, for a recovery |
| `PlayerStreaming.test.jsx` "the slow-connection card (decision 11)": its `slowDown` helper | 480p with nothing measured opens the card | a slow segment (2,000 kbit/s) first, then 480p | the card needs a known speed below home |
| `PlayerEnvironment.test.jsx` "one card at a time": its `openAuto` helper | 480p with nothing measured (the probe mock answers null) opens the card | a slow segment (2,000 kbit/s) first, then 480p | the same |
| `PlayerParts.test.jsx` "resumes inside the second part … never probes" | body without `probeKbps` | `probeKbps: 0` | split films start low |
| `streamSource.test.js` (the chunk that fails to load) | null at the first failure | the server answers: one more try, then null; no answer: tried again | item 1 |
| `probe.test.js` the time-box tests ("stops reading once the time box has passed…", "aborts a stalled body at the time box…", "does not leave its timer running") | the box starts with the request | the box starts at the first byte, which may take 10 s | the sleeping disk |
| `hlsjsEngine.test.js` "shows Reconnecting once and retries at the playhead every 5 s until a fragment loads" | `startLoad(currentTime)` every 5 s | one `startLoad` 5 s after each fatal error, none in between; at the playhead once the video has metadata (the test gives the element a `readyState`) | the loop aborted its own retry; the restart position |
| `hlsjsEngine.test.js` "does not take a detail that merely contains TimeOut for a timeout" | counted as a network error | not counted | only load errors and timeouts count |
| `hlsjsEngine.test.js` "reports a network error with no details as not a timeout" | counted | not counted | the same |

Every other existing test must pass unchanged. A test whose meaning changes is renamed to say the new rule.

## New tests (at the hls.js event API, `src/test/fakeHls.js`)

The fake changes with them: `on()` keeps every handler for an event, as hls.js does (today a second listener
silently replaces the first); `startLoad` and `loadSource` stay spies; `DefaultConfig.loader` is a stub class;
`ErrorDetails` carries hls.js 1.7.4's real strings for the details the engine reads.

- **`retry.test.js`:** `backoffMs` 0..5 is 1000, 2000, 4000, 8000, 8000, 8000; the waiter's `wait` resolves at `ms`
  and not at `ms - 1`, at once on `online` or `SIGNED_IN_EVENT` (heard during the wait, and before it since the last
  wait began), at once when the signal aborts (and when it is already aborted); `untilSignedIn` resolves on
  `SIGNED_IN_EVENT` only; disposed, it leaves no timer or listener behind (a later `online` does nothing;
  `vi.getTimerCount()` 0); `reachable` is true for a resolved check and for an `ApiError` (401, 404, 500), false for
  an `ApiError` 502, 503 or 504, for a `TypeError` and for no answer within the limit (at the limit, not 1 ms
  before), and passes its signal to the check.
- **`createSession.test.js`:** each row of the table above with exact `wait` arguments; the body built anew for each
  attempt; `onRetrying(true)` once at the first failure asked again, `false` at the end and on a 401 or a split
  film's 503; a second 500 gives up, the first does not; a 502, 503 (one piece), 504, 408 and 429 asked again
  without limit (twenty in a row, then a session); a 401 waits for the sign-in with no `onRetrying(true)` and no
  `onNoAnswer`, then asks at once, including a sign-in that came during the attempt; the check: a pending attempt is
  checked at 5,000 ms and not at 4,999, then at 20,000 and not at 19,999; a server that answers leaves it alone;
  no answer, or a check answered 502, aborts the attempt's signal, reports `onNoAnswer({ timeout: true })` exactly
  once (not also as a network error) and asks again; a check still pending when its attempt succeeds changes
  nothing when it then fails (no abort, no `onNoAnswer`, one session); a split film's network error then 503
  (`onRetrying` true then false, then the preparing loop); cancelled during a wait, during an attempt or during a
  check: no further attempt and no callback at all.
- **`streamSource.test.js`:** the chunk fails and the server answers: one more try at once, then null (and a second
  try that works starts the engine); the chunk fails and the server does not answer, or answers 502: `onRetrying(true)`,
  tried again after 1 s then 2 s, `onRetrying(false)` and the engine started once it loads; twenty failures with no
  answer still retry; cancelled during a wait: null and hls.js never built.
- **`probe.test.js`:** a first byte at 9,999 ms is measured from there for 1,500 ms; none by 10,000 ms is null; the
  1,500 ms box counts from the first byte, not from the request; with `checkReachable`: no check when the first byte
  came at 4,999 ms, a check at 5,000 when it has not; unreachable ends the probe at once with null and
  `onUnreachable` called once; reachable waits on to 10,000; a check that answers "unreachable" after the first byte
  arrived changes nothing (no `onUnreachable`, the measurement stands); no timer left behind in each case.
- **`indicator.test.js`:** `measuredSlow` true for 480p at 19,999 kbit/s, false at 20,000, false for 480p with nothing
  measured, false for 720p at 2,000.
- **`noProgressLoader.test.js`** (a stub inner loader whose `stats` the test moves, fake timers): no timeout while a
  byte arrives every 11,999 ms for ten minutes; with the checks aligned, none at 11,999 ms of silence and one at
  12,000 (the inner loader aborted, its `onAbort` not passed on, `onTimeout` called once with the inner `stats` and
  the context); none before the response began, however long; headers at 8,500 ms and nothing after: the check at
  9,000 counts as a change, so no timeout at 20,000 and one at 21,000; the inner loader exists, and `stats` can be
  read and written, before `load`; `onProgress` reaches the inner loader only when one was given; a callback that
  destroys the loader (as hls.js does) gets the stats object it had, and nothing of the wrapper's runs after it;
  `destroy()` twice, and inside a callback, is safe; the inner loader's own success, error and timeout each pass
  through once and stop the timer (`vi.getTimerCount()` 0); `abort()` and `destroy()` stop it and reach the inner
  loader; `stats` is the inner loader's very object and `context` reads through; `getCacheAge` and
  `getResponseHeader` pass through when the inner loader has them.
- **`hlsjsEngine.test.js`:** `fragLoadPolicy.default.maxLoadTimeMs` 120,000 and the playlists' 20,000;
  `errorPenaltyExpireMs` 60,000; `fLoader` is the no-progress loader built on `Hls.DefaultConfig.loader`;
  `retryNoAnswer` (status 0 below the limit yes, at the limit no, 404 no, 503 keeps `retry`, a timeout keeps `retry`,
  no response keeps `retry`); a `fragLoadTimeOut` restarts the healthy minute; the restart: after a fatal error one
  `startLoad` at 5,000 ms (not 4,999) and none after it, however long, until the next fatal error; a second fatal
  error while one is pending moves it to 5 s after the second; with no levels yet the same with `loadSource`, once
  per fatal `manifestLoadError`; a fragment that loads cancels a pending restart; `online` while a restart is
  pending makes it at once (and not again at 5 s), with none pending does nothing, while healthy does nothing, after
  `destroy()` does nothing; a restart before the first frame goes to the start position (`startLoad(startSeconds)`,
  or -1 from the start of the film) and after it to `video.currentTime`; an `ERROR` with `reason: 'offline'` shows
  no "Reconnecting…"; health counting: `fragLoadError`, `levelLoadError` and `manifestLoadError` with no status
  count, `fragLoadTimeOut` and `levelLoadTimeOut` count as timeouts, `aborted`, `manifestParsingError`,
  `levelParsingError` and `levelEmptyError` do not.
- **`nativeEngine.test.js`:** `online` while reconnecting checks at once despite the spacing; not while playing; not
  after `destroy()`.
- **`throughput.test.js`:** remembering and reading the speed: ages 0, 599,999 (reused), 600,000 and -1 (not);
  missing, malformed, zero and negative entries; a storage that throws on read and on write; the lower middle of an
  even count (100,000 and 500 give 500) while `median()` still gives 3000 where it does today; `offline` and `online`
  remove the remembered speed and move the network counter; the reset puts both back.
- **Screen tests** (`PlayerStreaming`, `PlayerParts`, `PlayerHealth`): a session request that gets no answer shows
  "Reconnecting…" (`role="status"`), sets no `src`, and plays through hls.js once a later request succeeds; the same at
  a **recovery**; a 502 then a session plays through hls.js, never direct; the `online` event retries at once (no real
  wait); a 401 on the session request shows the sign-in card and no "Reconnecting…", and streams as soon as the
  viewer signs in; a split film's network failure shows "Reconnecting…", not the card; an audio track chosen while
  the request is retried is the one asked for next; a quality change during "Reconnecting…" keeps the notice for
  the new request; recovery and an audio change reuse the speed (no probe, `probeKbps` from the samples); a second
  film within 10 minutes reuses it and one after 10 minutes probes (pin the clock with `vi.setSystemTime`,
  `toFake: ['Date']`), as does one after an `offline` event; a low start with nothing measured opens no slow card,
  and a slow segment then does; a probe that calls `onUnreachable` (the probe mock calls the option it was given)
  shows "Reconnecting…" before any session request answers, and one episode, not two, when the request then gets
  no answer; the health record: no `fellBack` and one reconnect for a session request that got no answer, no
  reconnect for a 401, an `aborted` error not counted. A test of reuse never uses the `online` wake-up: it is a
  network change and forgets the speed.
- Every screen test file that plays a film clears `sessionStorage` in `beforeEach` (most already do), so a
  remembered speed never leaks between tests.
- **Backend:** `CompressionTest` above.

Timers: unit tests use fake timers; a screen test waits on real time for at most one 1 s step (as
`PlayerParts.test.jsx` already does) and prefers the `online` wake-up.

## Gates and the rest

- Both local gates from `CLAUDE.md`, by exit code; per-file 95/90; diff-cover 90% on changed lines, front and back.
- Stryker once, at the end, on the changed `src/player` files only, never while a gate runs; PIT is not needed (no
  new Java logic).
- `changelog.d/20261111-poor-link.md`; `playback-health-plan.md` as above; the review's Phases line points here;
  `dev-and-testing.md` "Poor-network check" gains the start-up and recovery checks below.

## Checked by hand by the owner (not provable here)

With the throttled profiles in `dev-and-testing.md` ("Poor-network check"), in Chrome on the Mac:

1. Offline, then open a film: "Reconnecting…", never direct play; back online: the film starts within a second or two.
2. Mid-film, Offline for 30 s and for 3 minutes (the browser knows it is offline): the film plays on from its buffer
   with no notice, BUFFERING only if the buffer runs dry, and back online it carries on by itself at the same place,
   with no fall back in Admin.
3. Mid-film, the stream unreachable while the browser believes it is online (DevTools, Network request blocking, a
   pattern for `/api/sessions/`, which blocks the playlists and segments but not the session or its check) for 30 s
   and for 3 minutes. 30 s: no notice (hls.js's retries take about 47 s to run out), the film plays on from its
   buffer. 3 minutes: "Reconnecting…" once the retries are spent; unblocked, it clears by itself and playback carries
   on at the same place; Admin shows the reconnect and no fall back.
4. On "Train, bad" (300 kbit/s) from a cold start: Auto starts on the lowest rung, the first segment completes however
   long it takes (DevTools Network: one request per segment, no restarts from byte 0 every 20 s, and none every 5 s
   while "Reconnecting…" shows).
5. Two films in a row within 10 minutes: the second starts without the 1.5 s probe (DevTools: no `Range` request to
   `/stream`).
6. DevTools Network with Disable cache ticked (cached files keep their old headers), response headers: a rung's
   `index.m3u8`, the JavaScript and the stylesheet come back `content-encoding: gzip` (the master playlist is too
   small to compress).
7. Block `hls-*.js` alone (Network request blocking, Disable cache ticked) and open a film: the server still
   answers, so after one more try the film direct-plays, as today, and Admin shows a fall back. A second blocked
   request for the chunk in DevTools shows that this Chrome fetches a failed chunk again (none means it remembers the
   failure, as Safari does). The retry under "Reconnecting…" when nothing answers is covered by unit tests only.
8. Sign out in another tab, then open a film: the sign-in card, no direct play, no "Reconnecting…"; sign in and it
   streams.
9. On the host, restart the app (`docker restart cinefilm`) just as a film is opened: "Reconnecting…" while the proxy
   answers 502, then the sign-in card (a restart forgets sign-ins), then it streams.

Not verifiable here or by DevTools: **Safari on the Mac** (hls.js too; a failed hls.js chunk is never fetched again
there, so once the server answers the film direct-plays) and **the iPhone's native player** (only the `online`
wake-up changes there; use Network Link Conditioner).

## Risks

- The no-progress loader wraps hls.js's XHR loader through hls.js's `Loader` interface and relies on how the
  fragment loader builds `fLoader` and on the XHR loader reporting `abort()` through `onAbort`. The fake cannot check
  that, so the loader's unit test pins the interface it relies on, `hls.js` stays pinned at 1.7.4, and a bump
  re-reads `fragment-loader.ts` and `xhr-loader.ts` (said in the loader's comment).
- Whether a restarted segment gets bytes sooner is not known: in production the browser speaks HTTP/2 (or HTTP/3) to
  Caddy, so a new request may share the stalled connection. The limit's value is that a stall now escalates through
  hls.js (backoff, a lower rung, then "Reconnecting…") instead of sitting for up to 20 s per attempt with nothing
  learned; check 4 shows what it does on a real slow link.
- The status-0 retry delays the fatal error, and so "Reconnecting…", by up to about 47 s of hls.js's own backoff
  (8 tries); BUFFERING shows meanwhile if the buffer runs dry. Accepted: with a full buffer the blip is invisible. On
  the lowest rung hls.js already retried status 0 this way. The master playlist, which retries inside its loader,
  now spends about 15 s (4 tries) before its fatal error.
- A split film at home with no recent speed starts low (see 3 and 8), and reads "Remote" until its first segment is
  measured.
- The iPhone measures no segments, so there a probe that measured nothing leaves the speed unknown and the slow card
  does not open; today it opens for any low Auto rung. Accepted: the card's claim needs a measured speed.
- The reachability check assumes a lost session request and `GET /api/session` share the link's fate. Through Caddy
  they share one HTTP/2 connection, so they do; over HTTP/1.1 (the Vite dev proxy) a request lost on a dead
  connection while a fresh one answers would wait on PREPARING until the browser gives up on it.
- Once the server answers, a failed hls.js chunk still direct-plays, as today. Two follow-ups would do better: fall
  back to Safari's native HLS where it exists, and reload the page when a deploy has replaced the chunk.
