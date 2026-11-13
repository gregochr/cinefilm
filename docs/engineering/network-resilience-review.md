# Network resilience review — what more we can do on a poor link

Written 2026-11-10. Findings and proposed phases; nothing here is built. A read-only review of `main` at `973f2d0`
(player, transcoder, delivery, browse), of the hls.js 1.7.4 source the app ships, and of production's response headers.
It follows `transcoding-plan.md` (phase 3, shipped in #39) and answers the owner's question: beyond what we have, what
else helps when the network is poor?

Every time estimate below uses one reference link: **600 kbit/s (75 KB/s)**, about what train wifi gives on a bad
stretch. Each claim is marked **confirmed** (read in code, measured, or run), **estimate** (calculated from known
sizes) or **unverified** (needs the host or a browser).

## Short answer

Yes. Four things are small and fix places where a poor link is handled worse than it should be. Four more lower what
the link has to carry or bank more while it is good. The rest is browsing weight, measurement, and bigger bets.

| # | Change | Why it matters on a poor link | Size |
|---|---|---|---|
| 1 | Never fall back to direct play on a transient failure | A dropped request at start or recovery plays the 15 Mbps original until reload | S |
| 2 | Time out a segment on no progress, not on total time | Below 150–450 kbit/s (by segment length) a 360p segment can never finish; partial downloads are thrown away | S |
| 3 | A probe that measures nothing means "slow", not "top rung" | The slower the link, the more likely Auto starts at 720p | XS |
| 4 | Compress text (JS, CSS, playlists, subtitles) | 985 KB of app and player code goes uncompressed; so does every 27–48 KB playlist | XS |
| 5 | A 240p floor of about 300 kbit/s for every film | Halves the bitrate needed to keep playing, and the wait for a first frame | S + host check |
| 6 | Bank more on slow rungs (about 8 min instead of 2) | Rides out tunnels and dead zones; costs nothing at home | S |
| 7 | Sessions survive a long pause or a closed lid | Today the film restarts from scratch and discards its buffer | M |
| 8 | Do not probe again when we already know the speed | Saves 1.5 s or more on every recovery, audio change and next film | S |
| 9 | Artwork sized to need for stored (Plex) images | Posters are about 3× and stills about 6× the pixels shown | M |
| 10 | Browse data: smaller list, revalidate, keep in memory | About 106 KB re-downloaded on every Library visit | S |
| 11 | Playback health record | Trains are not reproducible; without numbers every tuning choice is a guess | M |

## What is already in place

One HLS stream per playback with the original as the top rung and live VAAPI rungs below it; a 1.5 s throughput probe
that chooses the start rung; a 120 s forward buffer; retries with backoff on playlists and segments; "Reconnecting…"
with a retry every 5 s; a new session when the old one has expired; Auto stepping up one rung after 60 s without a
stall; progress reports that retry. On the browse side: hashed assets cached for a year, lazy images with reserved
ratios, gzip on JSON, and the search index behind an ETag.

## Confirmed facts this review rests on

- **Production serves JS and CSS uncompressed** (read from `https://cinefilm.chrisjgregory.dev` on 2026-11-10 with
  `Accept-Encoding: gzip, br, zstd`): `index-*.js` 358,406 bytes (gzip 110,104), `index-*.css` 48,901 (9,085),
  `hls-*.js` 578,153 (178,323). `server.compression.mime-types=application/json` only
  (`application.properties:43-45`) and Caddy does not encode.
- **Production speaks HTTP/2 and advertises HTTP/3** (`alt-svc: h3=":443"`). API responses carry
  `cache-control: no-cache, no-store, max-age=0, must-revalidate` (Spring Security's default, `SecurityConfig.java:27`).
- **A media playlist is 27–48 KB and gzips to about 5 KB** (generated with `PlaylistWriter`'s format: 1,800 four-second
  segments, and 1,030 irregular ones). Playlists and segments are `no-store` (`HlsSessionController.java:55,66`).
- **hls.js starts on the best rung at or below min(first-listed rung, 5 Mbit/s)** (run against the real library with
  the app's settings and ladder; `level-controller.ts` seeds the estimate, `abrEwmaDefaultEstimateMax`). So the
  first-listed rung does steer it, and at home the first segment is 720p, then Original.
- **hls.js buffer depth** is `min(max(8 × maxBufferSize / rung bitrate, maxBufferLength), maxMaxBufferLength)`
  (`base-stream-controller.ts`, `getMaxBufferLength`). With our 120 / 120 / 250 MB that is 120 s on every rung.
- **hls.js does not retry a failed request whose status is 0 while the browser says it is online**
  (`error-helper.ts`, `retryForHttpStatus`); it switches rung instead, or retries only when there is no other rung.
- **hls.js never abandons a slow download on the lowest rung** (`abr-controller.ts`, `_abandonRulesCheck`); only the
  load timeout applies there.
- **The path from the owner's laptop was direct, 39 ms** (`tailscale ping`, 2026-11-10), not relayed.

## A. Where a poor link is handled worse than it should be

### 1. A transient failure drops the film to direct play (confirmed)

Three paths end in `toDirect()` (`useStream.js:73-84`), which sets `video.src` to the original file. On a poor link
that is the v0.1.0 experience, and it sticks until the page is reloaded.

- `createSession` returns null for **any** failure of the session request (`createSession.js:28`), including a
  network error and a 5xx, and `useStream.js:129-131` then direct-plays. The tests pin it
  (`createSession.test.js:50`, `PlayerStreaming.test.jsx:160`). The request also has no timeout.
- This runs again on **recovery** (`useStream.js:93-100`): a session expires after a long gap, the player asks for a
  new one at the moment the link is weakest, and one failed request means direct play for the rest of the film.
- A failed load of the hls.js chunk does the same (`streamSource.js:42-46`, `useStream.js:175-178`).

**Change:** direct play only on an answer that means "this cannot be streamed" (409). A network error, a timeout or a
5xx retries with backoff under "Reconnecting…", as the engine's own loop does. The chunk import retries too.

### 2. A segment is abandoned after 20 s however well it is going (confirmed)

`hlsjsEngine.js:17-24,36` sets `maxLoadTimeMs` to twice the first-byte limit: **20 s** for a segment. hls.js's own
default is 120 s. At 20 s the request is aborted and started again from byte 0, up to 8 times, then the reconnect loop
does the same.

A 360p segment is 0.7 Mbit/s of audio and video for 4 to 12 s (`CutList`: iTunes rips 4 s, HandBrake rips about 7 s,
at most 12 s). To finish inside 20 s it needs (estimate, with 8% MPEG-TS overhead):

| Segment | Bytes | Slowest link that can ever finish it |
|---|---|---|
| 4 s | 376 KB | 150 kbit/s |
| 7 s | 658 KB | 265 kbit/s |
| 12 s | 1.13 MB | 450 kbit/s |

Below those speeds the film cannot get past that segment at all, although the same link would play it stop-start.
Above them, every abort still discards up to 20 s of downloading.

**Change:** restore the 120 s ceiling and add a no-progress watchdog: hls.js updates `frag.stats.loaded` as bytes
arrive, so the engine can restart a load that has received nothing for about 12 s. That detects a dead transfer sooner
than today and never kills a live one.

### 3. A probe that measures nothing starts Auto high (confirmed)

`measureThroughput` returns null when the request fails, when no byte arrives inside its 1.5 s box (which includes
the time to first byte) or when only one chunk does (`probe.js:16,48`). `useStream.js:121` then sends no `probeKbps`,
`StartRung.pick` answers the **top** rung (`StartRung.java:25-27`), and hls.js starts at 720p with no cap. The first
720p segment (about 1.3 MB) is abandoned after a few seconds and 360p's playlist is fetched. Films split across
several files never probe (`useStream.js:113`), so they always start this way.

**Change:** a probe that ran and measured nothing sends 0, which already picks the lowest rung. Split films use the
remembered speed from item 8, or start low.

### 4. Text is sent uncompressed (confirmed)

- **App and player code:** 985 KB on the wire where gzip gives 297 KB: 13 s against 4 s. The main bundle is new after
  every release, so the 407 KB of JS and CSS (5.4 s against 1.6 s) recurs often. The hls.js chunk is paid on first
  play and when hls.js is bumped.
- **Playlists:** 27–48 KB per rung instead of about 5 KB. One is fetched at start and one for each rung Auto moves
  to, which is exactly when the link is in trouble: 0.4–0.6 s each.
- **Subtitles:** WebVTT is `text/vtt`, uncompressed, and re-downloaded whole each time a track is shown.

**Change:** one property: add `text/html`, `text/css`, `text/javascript`, `application/javascript`, `image/svg+xml`,
`application/manifest+json`, `application/vnd.apple.mpegurl` and `text/vtt` to `server.compression.mime-types`.
Tomcat compresses these as it does JSON (none carries a strong ETag). Doing it in Spring covers Caddy and the
`tailscale serve` fallback alike.

## B. Carry less, bank more

### 5. Lower the floor: 240p at about 300 kbit/s for every film (owner decision)

For a source above 360 lines the lowest rung is 360p at 600 kbit/s video plus 96 kbit/s audio (`Ladder.java:48,83`,
`Rung.java:20-28`): about **750 kbit/s** to keep playing once MPEG-TS overhead is counted (estimate). 240p exists
today only for sources of 360 lines or fewer. Train wifi is often under that.

A 240p rung at about 250 kbit/s video and 64 kbit/s audio (Netflix's old bottom rung was 320×240 at 235) would:

- keep a film playing down to about **350 kbit/s**;
- cut the download of the first segment, which is most of the wait for a first frame, from 4.6 s to 2.1 s for a
  4 s segment and from 8.1 s to 3.7 s for a 7 s one;
- cost little: the id, the route pattern, the menus and the preferences already accept `240p`; the change is the
  ladder rule, the bitrate and the audio rate.

**Unverified:** how the i965 encoder looks at 250 kbit/s, and the encoder's speed with one more rung (read it in
Admin → Transcoding). If hardware 240p is too rough, encode the bottom rung with libx264 (`scale_vaapi`, `hwdownload`,
then x264): a spike on dockermacmini.

### 6. Bank more when the rung is cheap (owner decision on one trade-off)

Train connectivity comes in bursts between dead zones. Today a good burst fills 120 s and stops; at 360p that is only
10 MB. Raising `maxMaxBufferLength` (and setting `maxBufferSize` to about 100 MB) makes depth follow bytes: about
**8 minutes at 480p and below, 4 at 720p, an unchanged 2 at Original** (`hlsjsEngine.js:27-33`). hls.js keeps loading
while paused, so pausing at a station banks the next stretch.

- **Limit (unverified):** Chrome's Media Source quota is about 150 MB of video and 12 MB of audio; 8 minutes of
  128 kbit/s audio is 8 MB. Check Chrome and Safari; hls.js trims itself when it hits a quota.
- **Trade-off:** what is banked at 360p is watched at 360p, so a recovered link shows later. Either accept that
  (resilience over fidelity) or re-fetch the banked stretch on a step up when throughput is at least twice the new rung.
- **Bank before upgrading:** let the step gate raise the cap only when a safety stock (say 90 s) is in the buffer,
  as well as 60 s without a stall (`stepGate.js`). At home the buffer fills in seconds, so nothing changes there.
- iPhone's native player manages its own buffer; items 5 and 7 are what help there.

### 7. Sessions that survive a pause (confirmed gap)

No request for 10 minutes deletes the session (`SessionManager.java:248-253`, `cinefilm.transcode.expiry`). A paused
film with a full buffer, or a closed lid, sends no requests. On resume the next segment is a 404 and the player
rebuilds everything: probe, session, hls.js, playlists (`useStream.js:93-100`), **discarding the buffer it had** and
risking item 1. A deeper buffer makes that loss larger.

**Change:** keep the spec of a session that expired from idleness (a few KB; a small LRU for a day) and rebuild it on
the next request under the same id, restarting the encoder at the requested segment. The player never sees the 404
and keeps its buffer. A session that was ended or replaced stays gone.

**Pulled forward and built on its own (2026-11-11):** it needs no owner decision and no measurement, and it changes
no client code. Plan: `long-pause-plan.md`.

### 8. Do not probe twice (confirmed)

Every Auto start probes for 1.5 s before asking for a session, including each recovery and each audio change
(`useStream.js:112-117`), although the hook already holds a median of real segment speeds (`throughput.js`). The probe
also fills the link with bytes of the original that are thrown away. **Change:** reuse the measured median inside a
film, remember it for about 10 minutes across films (session storage), and probe only when there is nothing to reuse.

## C. Browsing

### 9. Artwork sized to need (confirmed sizes, estimated bytes)

The handover says images are "sized to need" and that remote use matters. Artwork imported from Plex is stored at
**600×900** (posters) and **1920×1080** (backdrops) (`PlexImportService.java:54-57`) and `imageUrl` passes it through
at that size for every use (`images.js:14`). Library tiles are 160–200 px wide (`browse.css:198-209`): about 3× the
pixels needed at 2× density. Continue-watching stills are 280–420 px wide and get the full 1920: about 6×.

**Change:** `/api/art/{name}?w=` for a fixed set of widths (342, 500, 780, 1280), made on first request and kept in
the cache folder; `imageUrl` maps the size it is already given. Measure first: the average file size in the artwork
folder on dockermacmini.

### 10. Browse data (estimate from 1,087 synthetic cards in the real shape)

- `/api/films` returns every film: about 375 KB, **106 KB gzipped**, on every Library visit (`no-store`).
- Each card carries `backdropPath` (`FilmMapper.java:48-49`), which the Library never shows; it is a 64-character hash
  that does not compress. Leaving it out of the list gives about **62 KB**.
- `useResource` keeps nothing between mounts, so Back from a film shows the skeleton and fetches again, and Play
  fetches the film a second time before the session can start.

**Change:** drop `backdropPath` from the list; weak ETags and `private, no-cache` on the browse GETs (the browser then
revalidates by itself, as the search index does); a small in-memory cache in `useResource` that shows the last answer
while it revalidates.

## D. Measure, and the host

### 11. A playback health record

The player knows its start-up time, stalls, rung changes, throughput samples, retries and reconnects, and discards
them. Send a small summary per playback (at exit and each minute), store it, and list recent playbacks in Admin.
The next train ride then says which of these changes worked and what to tune (timeouts, step rules, the floor).

### 12. Congestion control on dockermacmini (unverified, owner action)

The host sends the video, so its TCP congestion control decides how throughput reacts to loss. Linux defaults to
CUBIC, which a few percent of loss can hold under 1 Mbit/s whatever the link's capacity; BBR tolerates loss. Two
caveats: nobody has measured loss on the train path, and browsers currently use HTTP/3 to Caddy, which has its own
congestion control, so BBR only applies if HTTP/3 is switched off in the Caddyfile. Worth one measured trial (a fixed
download with macOS's Network Link Conditioner at 3% loss, before and after), not a blind change. The host is shared
with goldenhour.

### 13. A repeatable poor-network check

Add to the manual release check: Chrome DevTools throttling at 300, 500 and 800 kbit/s with 150 ms latency, and the
Offline switch for 30 s and for 3 minutes mid-film. It reproduces items 1–3 and 5–7 without a train.

## E. Bigger bets and small extras

- **Download for offline** (already a later slice in `transcoding-plan.md`): the only answer to no signal at all.
- **Rebuffer goal:** after a stall, bank 10–15 s before resuming, so a link under the lowest rung gives a few long
  waits instead of constant stutter. A behaviour decision.
- **hls.js `progressive`** (experimental): frames are appended as they arrive, so the first frame needs the first
  chunk, not the whole 4–12 s segment. Needs a spike in Chrome and Safari.
- **Honest peaks:** transcoded rungs have `-b:v` only (`FfmpegCommandBuilder.java:209`); add `-maxrate`/`-bufsize`
  so `BANDWIDTH` is true, and measure real segment sizes on the host first.
- **Resume a broken segment download** with a `Range` request rather than from byte 0 (a custom loader; the segment
  needs a validator so two encodes are never mixed).
- Small: load the hls.js chunk when Film detail opens; use `hls.js/light` (117 KB gzipped against 182); retry at once
  on `online` and when the tab becomes visible; restart a paused encoder before the player reaches its last segment
  (`SessionManager.LOOK_AHEAD`); treat a status-0 failure as a retry so a blip does not cost a rung for 60 s; change
  quality inside an Auto session instead of starting a new one.

## Looked at and left alone

- **Segment and playlist caching in the browser:** session ids are new each time, so there is nothing to reuse.
- **fMP4 instead of MPEG-TS:** saves a few percent and reopens the muxer spike.
- **HEVC or AV1 low rungs:** no hardware encode on the host and uneven browser support.
- **A service worker for the app shell:** hashed assets are already cached for a year; update bugs outweigh the gain.
- **An audio-only rung:** the 240p floor covers the same links and keeps the picture.
- **Relayed Tailscale paths:** direct from the train before (96 ms) and today (39 ms). If playback is ever poor on a
  new network, `tailscale ping dockermacmini` says whether it is relayed; a relay caps speed and no app change helps.

## Noticed in passing (not a poor-network matter)

At home hls.js plays the first segment at 720p before Original, because its starting estimate is capped at 5 Mbit/s.
`transcoding-plan.md` goal 5 says home starts at the top; raising `abrEwmaDefaultEstimateMax` would make it so.

## Owner decisions

Made on 2026-11-10:

- **Measure first.** The playback health record (item 11) is built before anything is tuned.
- **The environment is the second dimension.** Recognise where a viewing happens (home, a train, a hotel), confirm
  it with a quiet question in the player, record it with every viewing, and later choose the streaming strategy from
  it, so problems are pre-empted rather than reacted to. His laptop use is the London–Durham train and hotel Wi-Fi;
  home matters too. Plan: `playback-health-plan.md`.
- **No location.** A laptop has no GPS and an address lookup's place guess was 40 miles out. What is needed about
  blackspots is how long they last and how often they come, which the per-minute record gives; where they are is not.

Made on 2026-11-12, from the first train viewing in the record (`train-link-plan.md`); these were open decisions 1
and 2:

- **A 240p floor for every film: yes** (item 5).
- **Bank about eight minutes on the slow rungs and keep what is banked when the link recovers: yes** (item 6).

Still open (numbered as before):

3. Keep the 1.5 s probe for the first play of a browser session (recommended), or drop it and start low?
4. Try BBR on dockermacmini?
5. Bring Download for offline forward?

## Phases

Each is one PR built by Sonnet under the usual gates, with a `changelog.d/` entry.

1. **Measure:** the playback health record with environment tagging, and the throttled manual check (items 11 and
   13; one expand-only migration). Plan: `playback-health-plan.md`.
2. **Stop making a poor link worse:** items 1, 2, 3, 4 and 8, plus the status-0 retry and the `online` retry.
   Frontend apart from the compression property and its test. It does not depend on the measurements. Plan:
   `poor-link-plan.md`.
3. **A strategy per environment:** items 5 and 6, set from what the record shows: on a train start low, bank
   deep and step up only with a reserve; at home start at the top. (Item 7 was pulled forward on its own:
   `long-pause-plan.md`.) **Planned 2026-11-12 as `train-link-plan.md` and merged 2026-11-13** as four pull requests
   (#123 to #126): the floor, the player following what it fetches (with the bank in the record), the deeper buffer,
   and the floor held back while the buffer is deep. They are driven by the link, not by the train tag, and do
   nothing at home; rules that read the tag, and the step-up reserve, wait for a journey recorded with these in
   place.
4. **Browse:** items 9 and 10, the hls.js preload and the light build.

Beyond these, only if the record asks for it: early warning (does latency climb before a dropout?) and knowledge of
the route.

## How each is proved

- Frontend: unit tests at the hls.js event API (`src/test/fakeHls.js`) for retry-not-direct, the no-progress
  watchdog, probe reuse, buffer settings and the gate; screen tests for "Reconnecting…" in place of direct play.
- Backend: pure tests for the ladder and start rung; `SessionManager` with the fake clock for rebuilding an expired
  session; a controller test that a playlist and a stylesheet come back gzipped.
- Host: encoder speed and picture at 240p; real segment sizes against `BANDWIDTH`; average artwork size.
- By hand: the throttled check in item 13, in Chrome and Safari on the Mac and on the iPhone.
