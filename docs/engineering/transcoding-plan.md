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
| `360p` | 0.6 Mbps | source above 360 lines |
| `240p` | 0.25 Mbps | source above 240 lines: the floor of every ladder, for a train's bad spells (owner, 2026-11-12; `train-link-plan.md`). Until then 240p was 0.4 Mbps and only for sources of 360 lines or fewer |

- **Every rung is strictly shorter than the one above it** (a transcoded rung exists only below the source height;
  a source of 240 lines or fewer has its top rung only). So heights
  are unique per film and the player can always tell the active rung from `videoHeight`.

- Audio on every rung: the chosen audio stream as **AAC stereo 128 kbps** (96 kbps on 360p and on a small film's
  single 240p rung, 64 kbps on the 240p floor); Chrome never meets AC-3.
- `BANDWIDTH` in the master playlist is the rung's **peak** per-segment bitrate (from the index and file sizes for
  the copy rung; the target × 1.2 for transcoded rungs), so ABR never picks a rung the link can't carry.
- Quality ids: `auto | original | 1080p | 720p | 480p | 360p | 240p`; `PreferencesService` and `FilmMapper` accept
  `360p` and `240p` (every film above 240 lines has both).

## The encoder: one ffmpeg per session, all rungs

- One ffmpeg reads the file once: the copy rung maps the video stream with `-c:v copy`; the transcoded rungs decode
  with VAAPI, `split`, `scale_vaapi`, `h264_vaapi` with forced keyframes; every rung gets the AAC audio. **Each rung
  has its own `segment` muxer output** (`-f segment`, `<session>/<rung>/seg-%d.ts`) — not the `hls` muxer, which cut
  on its own grid (see "Spike results"). The exact arguments are in "Spike results".
- **Seeking** to a segment the encoder won't reach within ~10 segments **restarts the encoder at that cut**
  (`-noaccurate_seek -ss <cut+1 ms>` on input, `-segment_start_number n`, timestamps kept on the film's timeline
  with `-copyts`; see "Spike results"). No playlist discontinuity is inserted, so continuity
  across a join must hold.
- **Spike first (done; results below)**, recorded in the PR: (a) copy-rung and transcoded-rung
  segments start at identical PTS at every boundary; (b) segment n-1 from encoder A then segment n from restarted
  encoder B play cleanly in Safari and hls.js (PTS and AAC priming checked with ffprobe); (c) switching between the
  copy rung and 720p mid-film in both players is seamless; (d) CPU and RSS of the full ladder with the copy rung.
  If (a)–(c) fail, the fallback is the transcoded-only ladder with a 4 s grid (still seamless ABR) and direct play as
  "Original"; the plan is updated in the same PR.
- **Waiting** for a segment the encoder will produce soon: at most **6 s**, then **503 with `Retry-After: 1`** (never
  404: Safari treats it as fatal); hls.js's fragment policy matches. Waits run on virtual threads
  (`spring.threads.virtual.enabled=true`).
- **Look-ahead** ≤ ~3 minutes past the furthest requested segment (then the encoder is killed and later restarted —
  Java can't SIGSTOP); segments more than ~2 minutes **behind** the furthest requested segment are deleted (the copy
  rung at 15 Mbps is ~110 MB a minute). With the player banking up to eight minutes that point is ahead of the
  playhead: a player that has lost its buffer asks again and the encoder restarts there (`train-link-plan.md`).
- **Processes**: a long-running `EncoderProcess` abstraction (start, `-progress pipe:1`, stop, exit), separate from the
  synchronous `ProcessCommandRunner`; `-nostdin`, `-protocol_whitelist file`, bounded `-threads`, `nice`; destroyed
  on session end and JVM shutdown; compose `init: true`.
- **Software fallback**: VAAPI unavailable ⇒ `libx264 -preset veryfast`, transcoded rungs trimmed to 480p, 360p and
  240p,
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
- **Expiry**: no request for 2 minutes ⇒ encoder stopped; 10 minutes ⇒ dir deleted and only the session's spec kept
  in memory (at most 8, for up to a day). Its next request **rebuilds it under the same id** and restarts the encoder
  at the segment asked for, so the player keeps its playlists and its buffer (`long-pause-plan.md`, 2026-11-11). A
  session that was ended, replaced, pushed out at the cap or out of the 8, whose encoder failed, lost to a restart,
  a day old, or whose file has changed answers **404**, and the player silently creates a new one at `currentTime`.
  Startup deletes leftover `hls/` dirs. An active session is never evicted;
  `hls/` is capped (5 GB) by evicting idle sessions only (which are remembered like expired ones).

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
  the rungs `available: true` (false in the worker-offline state; `original` is always available, direct play being
  the fallback). The listed rungs come from the same function as the session ladder (`Ladder.forSource`), so a
  non-copyable source (HEVC, 10-bit, unknown pixel format, or a stored cut list with gaps over 12 s) lists its top
  transcoded rung named by height (e.g. `1080p`). When the film has no keyframe index row yet, the codec and pixel
  format decide alone (the cut list is only consulted when stored and current); a long-gap film is therefore
  corrected in the list once it has been indexed by its first playback.
- `-map 0:a:N` counts the file's audio streams in file order: N is the rank of the chosen stream's file stream index
  among all the edition file's audio stream indexes, never its database id or list position.

## Player

- **`streamSource`**: HLS everywhere — **hls.js** (pinned, lazy-loaded, standard MediaSource only) wherever
  `window.MediaSource` exists and `Hls.isSupported()` (Chrome, Firefox, Edge and desktop Safari), and the browser's
  **native HLS only when there is no standard MediaSource** (iPhone Safari: native fullscreen, AirPlay and power
  efficiency, decision 10). Recent desktop Chrome answers `canPlayType('application/vnd.apple.mpegurl')` truthy, so
  `canPlayType` alone must never pick the engine: Chrome is where the buffering was seen and needs our ABR, buffer
  and reconnect policy (`chooseEngine` in `streamSource.js`; `preferManagedMediaSource: false`). Direct play only in the worker-offline
  state or for a file whose index isn't ready and can't be built in time: when the server refuses the session, never
  because a request got no answer, which is asked again under "Reconnecting…" (`poor-link-plan.md`).
- **Auto**: start rung from a **time-boxed probe** (≈1.5 s of a range request on the original, counting bytes after
  the first byte, the box itself starting at the first byte, which may take 10 s) — at home that is normally
  `original`, on a poor link 480p or 360p. A speed measured in the last 10 minutes and since the network last changed
  (this film's segments, or another film's in the same browser session) is reused instead of probing; a probe that
  measures nothing, and a split film with no recent speed, start on the lowest rung (`poor-link-plan.md`). Then let
  ABR run: hls.js steps
  down at once and up one level after ~60 s healthy (decision 3, via `autoLevelCapping`/`nextAutoLevel`). The cap
  follows the level hls.js starts to *fetch* (`LEVEL_SWITCHING`), not the level that reaches the screen
  (`LEVEL_SWITCHED`), which comes later by as much as is buffered (`train-link-plan.md`). On a ladder with a 240p
  floor and a 360p level, the buffer is spent before the floor: once 420 s are buffered Auto does not go below
  360p until fewer than 240 s are left (`floorHold.js` raises hls.js's `minAutoBitrate`). iPhone's
  native HLS adapts on its own (it can't be gated) and the player reads changes from `resize`/`videoHeight`, which
  identifies the rung because heights are unique per film (Rungs: a transcoded rung exists only *strictly below*
  the source height, so `original` and e.g. `480p` never share a height; a test asserts uniqueness for every
  ladder). Toasts
  on every change: "Switching to 720p for smoother playback", "Back to Original" / "Back to 1080p".
- **Chosen quality** pins the master to that rung; remembered per film (decision 2). Choosing **`original`** pins
  the copy rung when the file has one, and otherwise **direct-plays the file** (`GET /api/editions/{id}/stream`), so
  `original` is always available and always means the untouched source video. A pinned quality the film doesn't
  have (e.g. a remembered `720p` on a 480-line film) falls back to Auto.
- **hls.js settings**: `maxBufferLength` 120 s, `maxMaxBufferLength` 480 s and `maxBufferSize` 100 MB, so the
  forward buffer follows bytes at the rung's peak bitrate: eight minutes at 480p and below, about 256 s at 720p,
  130 s at a 1080p transcode, and 120 s on an original of 4.4 Mbit/s or more (deeper on a lighter one). A good
  stretch of a poor link banks the next bad one (`train-link-plan.md`; until then it was 120 s on every rung). If
  the browser's buffer fills, hls.js lowers its own ceiling and carries on. Back buffer 30 s;
  `fragLoadPolicy`/`playlistLoadPolicy`/
  `manifestLoadPolicy` retries with backoff (a request that got no HTTP answer is retried like a 5xx); a segment may
  take 120 s, and one that has received nothing for 12 s times out into hls.js's own retries; on a fatal network
  error show "Reconnecting…" (polite status) and restart hls.js once, 5 s later (at once if the browser comes back
  online first), at the same position; session 404 ⇒ new session at `currentTime` (`poor-link-plan.md`). While the
  browser knows it is offline hls.js waits quietly and resumes by itself. Safari native:
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

## Spike results (2026-11-09, real VAAPI on dockermacmini, the deployed image's ffmpeg)

Films: *American Beauty* (regular 1 s keyframes) and *127 Hours* (HandBrake: irregular keyframes, gaps up to 10 s,
non-IDR I-frames). Software encoder was not the spike target; the software path is the same shape with `libx264`.

**What failed**
- The `hls` muxer (`-var_stream_map`) picks its own cut points on an absolute `hls_time` grid, so the copy and
  transcoded rungs diverged on *127 Hours*. Replaced by per-rung `segment` muxers fed the same cut times.
- `-force_key_frames` with the raw keyframe pts landed one frame late: the forced times are the cuts minus half a frame.
- Absolute `-segment_times` on a restart produced one giant segment (the muxer measures from the run's first packet):
  the times passed are relative to the run's start.
- A copy-rung segment starting on a non-IDR I-frame had no SPS/PPS and failed standalone decode ("non-existing PPS 0"):
  `dump_extra=freq=keyframe` is required.
- Without a fixed `-output_ts_offset`, ffmpeg shifted negative B-frame timestamps differently per output and the copy
  rung landed 20 ms off the transcoded ones.

**Adopted recipe (verified: every rung's segment n starts at the identical PTS on both films; restart segments match a
from-zero run exactly; every segment of every rung decodes standalone with zero errors)**
- Keyframes are the video packet pts the demuxer reports for K packets, edit list applied
  (`ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0`, filtered to K); not frame pts.
  The MP4 reader yields the same values. The cut list is unchanged.
- Global: `-nostdin -copyts -hwaccel vaapi -hwaccel_device <dev> -hwaccel_output_format vaapi`, then
  `[-noaccurate_seek -ss <S>] -i <src>`, then `-filter_complex "[0:v]split=K[..];[..]scale_vaapi=w=-2:h=H[vN]..."`.
- Copy rung: `-map 0:v:0 -map 0:a:<idx> -c:v copy -bsf:v h264_mp4toannexb,dump_extra=freq=keyframe`.
- Transcoded rung: `-map [vN] -map 0:a:<idx> -c:v h264_vaapi -b:v <rate> -g 9999 -force_key_frames <ABS>`.
- Every rung: `-c:a aac -b:a <ab> -ac 2 -f segment -segment_format mpegts -segment_times <REL> -segment_start_number <n>
  -reset_timestamps 0 -output_ts_offset 10 -muxdelay 0 -muxpreload 0 <dir>/<rungId>/seg-%d.ts`.
- With h = 0.5 / fps (0.02 if unknown): ABS = every cut after the start segment minus h on the film timeline; REL = the
  same minus the run's start time S; 6 decimals. Segment PTS = film time + 10 s (playlists use EXTINF only).
- Restart at segment n > 0: `-noaccurate_seek -ss <(floor(cutMs)+1)/1000, 3 decimals>` (1 ms past the keyframe),
  `-segment_start_number n`, REL relative to that same value.
- A restarted run's audio starts up to ~70 ms before the cut (a few AAC frames overlap); the video join is
  frame-exact; players trim the overlap.
- Speed: 120 s of film with copy + 720p + 360p took 13–15 s (about 8–9x realtime). ffmpeg peak RSS 105–146 MB, so
  three sessions sit well inside `mem_limit: 2g`.
- (c) Player switching in hls.js and Safari is **not** verified by the spike; the owner checks Safari manually.

## Review fixes (Codex)

- Copy rung and GPU decode only for 8-bit 4:2:0 H.264 (`SourceFormat`; pixel format stored in `media_file.pixel_format`, migration V6, nullable). Unknown (rows scanned before V6, until reprobed) means no copy rung and software decode.
- Non-eligible sources (HEVC, High 10, 4:2:2, 4:4:4) on VAAPI: software decode, `scale,format=nv12,hwupload`, `-vaapi_device <dev>`; i965 cannot decode them.
- The render node comes from `cinefilm.transcode.device` via `SessionSettings` and `TranscodePlan.device`.
- libx264 outputs carry their own `-threads N`; the keyframe ffprobe fallback streams stdout (`LineStreamRunner`) and keeps only K rows.

**Software decode into VAAPI encode (verified 2026-11-09 on dockermacmini):** a 10-bit HEVC source decoded in software,
`hwupload`ed and encoded with `h264_vaapi` produced aligned segments on both rungs (7 of 7), no decode errors, 8-bit
High-profile output, and 40 s of film in 12 s. This is the path for every source that is not 8-bit 4:2:0 H.264.
