# Transcoding and network resilience — plan

Written 2026-11-09, revised the same day after a two-part adversarial review and owner decisions. Product phase 3,
now the owner's top priority: "The transcoding is really important to me… Network resilience is something I want us
to double down on." v0.1.0 direct-plays the original file only; on train wifi in Chrome a 15.6 Mbps original
(*American Beauty*) buffered constantly. Tailscale was direct (96 ms), so the link was the limit.

## What we measured (dockermacmini, 2026-11-09)

- **The library is H.264** (60-file sample: all `h264`, heights 300–1080, most SD), so Ivy Bridge Quick Sync does
  hardware **decode and encode** for every film.
- `h264_vaapi` (i965, `/dev/dri/renderD128`, group `render` 993), *American Beauty* 1920×820 15 Mbps over the SMB
  mount, 60 s of film, including container start: single 720p 6.1 s (~10× real time); single 480p 3.4 s (~17×);
  **one ffmpeg making the whole transcoded ladder** (820p 5 Mbps, 720p, 480p, 360p, AAC each) with the `hls` muxer:
  **16.9 s (~3.5×)**, 4.004 s segments, 72 MB per minute across all rungs.
- **Keyframes** (first 20 min of five films): iTunes rips every **1.0 s**; HandBrake rips every 3.3–3.8 s on
  average, **never more than 10 s** apart. Indexing 20 min took 2–22 s over SMB.
- Audio: some files carry AC-3 5.1 beside AAC stereo; Chrome and Firefox can't play AC-3. Free disk ~21 GB.

## Goals, in order

1. **Never stall**: step down before the buffer runs out, retry instead of failing, keep a deep forward buffer.
2. **Start fast**: first frame within ~3 s remotely.
3. **Survive drops**: Wi-Fi gaps, Tailscale reconnects and tunnels resume at the same position without a reload.
4. **Same experience in Safari and Chrome** (Mac, iPhone): the handover's player, menu, indicator, toasts.
5. **The same resilience at home** (owner): Wi-Fi glitches and contention happen at home too. Home starts at the top
   and steps *down* when the link falters; a poor link starts low and steps *up*. One adaptive policy.

## The design in one paragraph

Every film plays as **one HLS stream whose top rung is the original itself** (video copied untouched, audio made AAC)
and whose lower rungs are transcoded live. All rungs share one segment timeline cut at the original's keyframes, so
the player (hls.js, or Safari's own HLS) switches rung at any segment boundary **without reloading, without losing
its buffer, and without touching subtitles or position**. One ffmpeg per playback makes every rung from a single
read of the file. Direct play of the file remains only as a fallback.

## Segment timeline (per media file)

- A **keyframe index** per media file: the video keyframe times, read once and stored (new table, below). Prefer
  reading them from the MP4 sample tables (`stss`/`stts`/`ctts` in the `moov` box, a few MB at the start of the file)
  rather than decoding; fall back to `ffprobe -skip_frame nokey`. Built by a **background index job** after the scan
  (one file at a time, low priority) and on demand when a film is played before its index exists.
- **Cuts**: the first cut is 0; each next cut is the first keyframe at or after **the previous cut + 4 s**, so the
  list is strictly increasing (no zero-length segments however far apart keyframes are) and segments are 4–~12 s;
  segment *n* is `[cut[n], cut[n+1])`.
  The cut list is the timeline for **every rung**: the copy rung cuts there naturally, and the transcoded rungs force
  keyframes at exactly those times (`-force_key_frames <t1,t2,…>`, ~1,800 entries for two hours; VAAPI gives closed
  GOPs). A file whose largest gap between cuts exceeds **12 s** gets **no copy rung** (its top rung is transcoded at
  source height instead) — none of the sampled files would.
- The server serves **its own playlists** from the cut list and duration before anything is encoded
  (`#EXT-X-PLAYLIST-TYPE:VOD`, `#EXT-X-ENDLIST`, exact `#EXTINF` per segment, `#EXT-X-TARGETDURATION` = longest), so
  players see the whole film and can seek anywhere. ffmpeg's playlists are ignored.

## Rungs

| Rung | Video | When |
|---|---|---|
| `original` | **copy** of the source video | when the file has a keyframe index with gaps ≤ 12 s |
| top (no copy rung) | `h264_vaapi` at the **source height** (capped 1080), named by height: above 720 ⇒ `1080p` 5 Mbps, above 480 ⇒ `720p` 2.5 Mbps, above 360 ⇒ `480p` 1 Mbps, above 240 ⇒ `360p` 0.6 Mbps, else `240p` 0.4 Mbps | only when the file has no copy rung; the rungs below follow the same rule as with a copy rung |
| `720p` | 2.5 Mbps | source above 720 lines |
| `480p` | 1 Mbps | source above 480 lines |
| `360p` | 0.6 Mbps | source above 360 lines (owner: yes; the floor for the worst links) |

- **Every rung is strictly shorter than the one above it** (a transcoded rung exists only below the source height;
  a source of 360 lines or fewer has its top rung only, plus a 240p 0.4 Mbps rung if it is above 240). So heights
  are unique per film and the player can always tell the active rung from `videoHeight`.

- Audio on every rung: the chosen audio stream as **AAC stereo 128 kbps** (96 kbps on 360p); Chrome never meets AC-3.
- `BANDWIDTH` in the master playlist is the rung's **peak** per-segment bitrate (from the index and file sizes for
  the copy rung; the target × 1.2 for transcoded rungs), so ABR never picks a rung the link can't carry.
- Quality ids: `auto | original | 1080p | 720p | 480p | 360p | 240p`; `PreferencesService` and `FilmMapper` accept
  `360p` and `240p` (240p only exists for sources of 360 lines or fewer, common among the older SD rips).

## The encoder: one ffmpeg per session, all rungs

- One ffmpeg reads the file once: the copy rung maps the video stream with `-c:v copy` (`h264_mp4toannexb` applied
  for MPEG-TS); the transcoded rungs decode with VAAPI, `split`, `scale_vaapi`, `h264_vaapi` with the forced
  keyframes; every rung gets the AAC audio. Output with ffmpeg's **`hls` muxer** (`-var_stream_map`,
  `-hls_segment_filename v%v/seg-%d.ts`, `-hls_flags temp_file+independent_segments`, segment times from the cut
  list via `-hls_time` + forced keyframes / `-segment_times`) — the exact flags are settled by the spike below. With
  `temp_file`, a segment appears under its final name only when complete.
- **Seeking** to a segment the encoder won't reach within ~10 segments **restarts the encoder at that cut**
  (`-ss <cut>` on input — accurate for the copy rung because the cut is a keyframe — `-start_number n`, timestamps
  kept on the film's timeline, `-muxdelay 0 -muxpreload 0`). No playlist discontinuity is inserted, so continuity
  across a join must hold.
- **Spike first (in the build PR, before the session code)**, recorded in the PR: (a) copy-rung and transcoded-rung
  segments start at identical PTS at every boundary; (b) segment n-1 from encoder A then segment n from restarted
  encoder B play cleanly in Safari and hls.js (PTS and AAC priming checked with ffprobe); (c) switching between the
  copy rung and 720p mid-film in both players is seamless; (d) CPU and RSS of the full ladder with the copy rung.
  If (a)–(c) fail, the fallback is the transcoded-only ladder with a 4 s grid (still seamless ABR) and direct play as
  "Original"; the plan is updated in the same PR.
- **Waiting** for a segment the encoder will produce soon: at most **6 s**, then **503 with `Retry-After: 1`** (never
  404: Safari treats it as fatal); hls.js's fragment policy matches. Waits run on virtual threads
  (`spring.threads.virtual.enabled=true`).
- **Look-ahead** ≤ ~3 minutes past the furthest requested segment (then the encoder is killed and later restarted —
  Java can't SIGSTOP); segments more than ~2 minutes **behind** the playhead are deleted (the copy rung at 15 Mbps is
  ~110 MB a minute).
- **Processes**: a long-running `EncoderProcess` abstraction (start, `-progress pipe:1`, stop, exit), separate from the
  synchronous `ProcessCommandRunner`; `-nostdin`, `-protocol_whitelist file`, bounded `-threads`, `nice`; destroyed
  on session end and JVM shutdown; compose `init: true`.
- **Software fallback**: VAAPI unavailable ⇒ `libx264 -preset veryfast`, transcoded rungs trimmed to 480p and 360p,
  the copy rung unchanged; logged once and shown in Admin. ffmpeg unusable ⇒ the handover's **"worker offline"**
  state: Auto and lower qualities disabled with its footer copy; the original plays by direct play.
- **In the CineFilm container** behind a `Transcoder` interface rather than the foundation plan's separate worker:
  the measurements say one Mac mini is ample; the interface keeps that extraction mechanical. Container:
  `devices: /dev/dri`, `group_add: ["993"]`, `LIBVA_DRIVER_NAME=i965`, `init: true`, heap pinned (`-Xmx512m`, not
  `MaxRAMPercentage=75`), `mem_limit` sized from the spike's measured RSS (expect 2 g).

## Sessions

- One edition + one audio stream + a random UUID; dir `cinefilm.cache.dir/hls/<id>/` (`hls/` only; `subtitles/`
  untouched).
- **Newest wins**: a new session for an edition replaces the older one; at most **3 sessions**, a 4th replaces the
  least recently used — playback is never refused.
- **Expiry**: no request for 2 minutes ⇒ encoder stopped; 10 minutes ⇒ dir deleted; requests then get **404 on the
  session** and the player silently creates a new one at `currentTime`. Startup deletes leftover `hls/` dirs. An
  active session is never evicted; `hls/` is capped (5 GB) by evicting idle sessions only.

## API

- `POST /api/editions/{id}/sessions` `{ audioStreamId?, startMs?, quality }` → `{ sessionId, masterUrl, renditions:
  [{ id, height, bitrate, copy }] }`; `audioStreamId` must be one of the edition's streams; `startMs` within the
  duration.
- `GET /api/sessions/{id}/master.m3u8`: per session, the **intended start rung listed first** (Safari starts on the
  first variant) and **only the pinned rung** when a quality is chosen.
- `GET /api/sessions/{id}/{rung}/index.m3u8`, `GET /api/sessions/{id}/{rung}/seg-{n}.ts`: validated
  (`^[0-9a-f-]{36}$`, `^(original|1080p|720p|480p|360p|240p)$`, `^seg-\d{1,6}\.ts$`, **n < the file's segment count**),
  served only from that session's dir, `Cache-Control: no-store`.
- `DELETE /api/sessions/{id}` on exit via `fetch(..., { keepalive: true })` with `X-CineFilm` (as `usePageExitReport`
  does); the idle timeout is the backstop.
- `GET /api/editions/{id}/stream` (direct play) stays for the fallback cases.
- All behind the session cookie; `RouteSecurityDriftTest` covers the new routes. `FilmDetail.qualities[]` reports
  the rungs `available: true` (false in the worker-offline state; `original` is always available).

## Player

- **`streamSource`**: HLS everywhere — **native on Safari** (Mac and iPhone; iPhone native fullscreen keeps working,
  decision 10) and **hls.js** (pinned, lazy-loaded) on Chrome and Firefox. Direct play only in the worker-offline
  state or for a file whose index isn't ready and can't be built in time.
- **Auto**: start rung from a **time-boxed probe** (≈1.5 s of a range request on the original, counting bytes after
  the first byte) — at home that is normally `original`, on a poor link 480p or 360p — then let ABR run: hls.js steps
  down at once and up one level after ~60 s healthy (decision 3, via `autoLevelCapping`/`nextAutoLevel`); Safari's
  native HLS adapts on its own (it can't be gated) and the player reads changes from `resize`/`videoHeight`, which
  identifies the rung because heights are unique per film (Rungs: a transcoded rung exists only *strictly below*
  the source height, so `original` and e.g. `480p` never share a height; a test asserts uniqueness for every
  ladder). Toasts
  on every change: "Switching to 720p for smoother playback", "Back to Original" / "Back to 1080p".
- **Chosen quality** pins the master to that rung; remembered per film (decision 2). Choosing **`original`** pins
  the copy rung when the file has one, and otherwise **direct-plays the file** (`GET /api/editions/{id}/stream`), so
  `original` is always available and always means the untouched source video. A pinned quality the film doesn't
  have (e.g. a remembered `720p` on a 480-line film) falls back to Auto.
- **hls.js settings**: `maxBufferLength` 120 s and `maxBufferSize` raised so 120 s of the copy rung fits (~250 MB;
  drops to whatever fits on lower rungs), back buffer 30 s; `fragLoadPolicy`/`playlistLoadPolicy`/
  `manifestLoadPolicy` retries with backoff; on a fatal network error keep retrying every 5 s with "Reconnecting…"
  (polite status) and resume at the same position; session 404 ⇒ new session at `currentTime`. Safari native:
  `stalled` alone does nothing (it fires when fetching pauses while buffered video still plays); the source is
  reloaded at `currentTime` (an explicit `pendingSeekMs`, never the page-load resume position) only on a fatal
  `error`, or when playback has made no progress for 15 s while not paused and the buffer ahead is empty, with
  "Reconnecting…" shown meanwhile.
- **Position and progress**: HLS time equals film time, so subtitles (existing VTT `<track>`s), resume and progress
  line up. On any source (re)load the player re-applies `<track>` modes after `loadedmetadata`, seeks to
  `pendingSeekMs`, and freezes progress reporting until the seek lands; `usePlayback` never reports a position ≤ 0
  over a saved one; failed reports are kept and retried. The build checks subtitle `<track>`s show with hls.js and in
  iPhone native fullscreen with HLS; if not on iPhone, add `EXT-X-MEDIA TYPE=SUBTITLES` pointing at the VTT endpoint.
- **Indicator** (handover §4): `Auto · Original · Home`, `720p · Remote`, `480p · Remote · slow`. "Home" = the median
  measured throughput ≥ 20 Mbps (all clients arrive through `tailscale serve` with 100.x addresses); "slow" = 480p or
  lower; amber whenever Auto is below the top rung. Slow-connection card once per session (decision 11).
- **Audio change** restarts the session with the new `audioStreamId` at the current position.

## Schema (V-next, expand-only)

- `media_keyframes` (`media_file_id` PK/FK, `cuts` — the segment start times in ms as a compact array or text,
  `max_gap_ms`, `copy_ok`, `indexed_at`). Re-indexed when the file's size or mtime changes.

## Admin

- **Transcoding panel**: encoder (VAAPI, software, unavailable), keyframe index progress (n of N files), sessions
  (film, active rung, encoder speed ×, segments ahead), `hls/` size, **Stop** per session (`X-CineFilm`).
  `GET /api/admin/transcoding`, `DELETE /api/admin/transcoding/sessions/{id}`.

## Later slices

- **Download for offline** (owner, 2026-11-09): live transcoding replaces the handover's server-side "travel copies"
  (decision 15); the one thing it can't cover is no signal at all. Save a 480p/720p MP4 of a film to the laptop or
  iPhone before travelling, made on demand by the same encoder; no storage on dockermacmini.
- **Separate worker + workhorse failover**: only if dockermacmini proves too slow.
- **Scrubber sprites** (decision 14), which can reuse the keyframe index.

## Security (`SECURITY.md`)

- ffmpeg/ffprobe only via argument arrays with `-protocol_whitelist file`; input is the DB-known path under the
  library root (rule 3); outputs only under the session dir; session ids random UUIDs; every URL path segment
  regex-validated and bounded; `audioStreamId` and `startMs` validated against the database; the MP4 box reader
  bounds every size it reads (a malformed file can't make it allocate or loop without limit).
- Bounded resources: session cap with replacement, idle timeout, look-ahead cap, behind-playhead deletion, `hls/`
  cap, bounded threads, `nice`, encoders killed on shutdown. `/dev/dri` is the only device passed in.

## Tests (`test-standards.md` in full)

- The **spike** above, recorded in the PR.
- Pure functions: cut list from keyframes (target 4 s, gaps, `copy_ok` threshold, last segment); playlist generation
  (VOD, ENDLIST, exact EXTINF, target duration); ladder from source height and index; peak `BANDWIDTH`; the ffmpeg
  command builder (copy + VAAPI rungs, software fallback, restart at a cut, audio map, whitelist) as exact argument
  lists; validation and bounds; restart-or-wait decision; Auto start rung from the probe.
- MP4 sample-table reader against small synthetic MP4s (generated in the test with ffmpeg in CI, or hand-built
  boxes): keyframes, edit lists, malformed sizes rejected.
- Session manager with a fake `EncoderProcess`, an injected `Clock`, a controllable scheduler and file-watcher (**no
  sleeps**): create, newest wins, cap replacement, serve, wait then serve, wait then 503, restart on seek,
  look-ahead kill/restart, behind-playhead deletion, idle stop, expiry 404, startup cleanup, encoder crash → restart
  once then the unavailable state. The process adapter stays thin so PIT reaches 98.
- Index job: indexes new files, skips unchanged, re-indexes changed, low priority, survives failures.
- **Real-tool integration test in CI** (software path): a 30 s clip with irregular keyframes → index → session →
  playlists valid; copy and transcoded rungs share boundaries (PTS equal); segments decode; PTS continuous across a
  restart.
- Controllers: auth, validation 404s, bounds, CSRF on DELETE, `RouteSecurityDriftTest`.
- Frontend: `streamSource` (native vs hls.js vs fallback), Auto start rung, level-change toasts and step-up gating,
  Safari level detection, reconnect and session-404 recovery with `pendingSeekMs`, track modes re-applied, progress
  never regressing to 0, retried reports, indicator text, audio change restart, 360p in menus and preferences.
  hls.js mocked at its event API.
- **Manual release check** (owner, in the PR): Chrome and Safari on Mac, Safari on iPhone, at home and on a poor link,
  including a forced Tailscale drop mid-film.
- Coverage, PIT ≥ 98 and Stryker floors unchanged.

## Owner actions

- None on the hosts: `/dev/dri` and the `render` group exist on dockermacmini; the release adds the device.
- After release: the manual check above.
