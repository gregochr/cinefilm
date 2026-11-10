# Resume still — plan

Written 2026-11-10. Owner: resuming *Warrior* at 14:25 remotely showed a black screen with the startup indicator while
the HLS session was prepared at 720p; "the frame at the resume point should appear immediately". Scrubber sprites
(`spriteReady`) don't exist yet, so a single still endpoint supplies the frame. One PR, built on `main` after the
Player PREPARING/BUFFERING PR.

## Where ffmpeg runs (review of the suggested shape)

- Transcoding is **in the CineFilm container** (`transcoding-plan.md` "The encoder", not the foundation plan's
  separate worker), and subtitles already run ffmpeg there on request (`SubtitleService`). So the still endpoint
  lives in the backend too and needs only software decode: it works in every `EncoderMode`, VAAPI or not, and in the
  "worker offline" state as long as the `ffmpeg` binary starts. If it can't start (`IOException`), the answer is
  **503**, and the player silently stays black.
- It runs beside a starting HLS encoder reading the same SMB mount. One still is one keyframe seek and one decoded
  frame (≪ 1 s of I/O); bounded to **2 at once**, so it can't crowd the encoder.

## Backend

`GET /api/editions/{id}/still?atMs=<edition ms>` → `image/jpeg`.

- **Package** `gregochr.cinefilm.still`: `StillController`, `StillService`, and a pure `StillCommand` (argument list
  builder) — like `subtitle/`.
- **Validation**: `atMs` required, `0 ≤ atMs ≤ timeline total` else **400**; unknown edition, no original part, a part
  not `PLAYABLE`, or its file missing under the library root → **404** (path always from the DB via
  `LibraryRoot.resolveFile`, SECURITY.md rule 3).
- **Multi-part**: `EditionTimeline.of(edition).locate(atMs)` gives the part and offset; a single-part edition uses
  its one file (timeline still validates the bound; a part with no duration → 404, as subtitles do). An offset at the
  very end of a part (`locate` past the total) is clamped to `duration − 1 s` (never negative) so ffmpeg has a frame.
- **Granularity and cache**: the part offset is floored to **whole seconds** (not 10 s: a 10 s jump can land on a
  different shot, and the cache earns little anyway — the resume position changes every viewing; it is for reopening
  and reloads). Cache file `cinefilm.cache.dir/stills/<mediaFileId>-<sec>-<size>-<mtimeMs>.jpg` (a replaced file
  misses the cache), written to a `UUID.tmp` and moved atomically, like subtitles. After a write, when `stills/` has
  more than **300** files the oldest by mtime are deleted down to 300 (~30 MB at most).
- **Command** (argument list, never a shell; exact list asserted in a test):
  `ffmpeg -nostdin -hide_banner -v error -protocol_whitelist file -threads 1 -ss <sec> -i <file> -map 0:v:0 -frames:v 1
  -an -sn -dn -vf scale='trunc(iw*sar/2)*2':ih,setsar=1,scale='min(1280,iw)':-2 -q:v 5 -f image2 -y <tmp>`. `-ss` before `-i` (keyframe seek,
  then accurate decode up to the time: at most one GOP, ≤ 10 s on this library). `-threads 1` keeps two concurrent grabs to two cores (SECURITY.md rule 4 resource limits; added after Codex review). the filter first squares the pixels (width x SAR, kept even, height unchanged) and only then caps the width at 1280, so an anamorphic DVD rip keeps its true aspect; `min` never upscales SD. `-f image2` with a `.tmp` name (the muxer would otherwise guess from the extension).
- **Bounds**: a `Semaphore(2)`; `tryAcquire(3 s)` fails → **503** (no retry: the player just stays black). ffmpeg timeout **10 s** → 503 (and
  a WARN with edition, part, second). Non-zero exit or no output file → 503 too (a transient SMB failure must not look
  like "not found"; the client treats every non-200 the same: no still).
- **Response**: `Cache-Control: private, max-age=86400` (the URL plus the file's stamp is immutable enough; a replaced
  file within a day showing yesterday's frame is harmless). Body streamed from the cached file.
- **Errors on an image route**: the existing `ApiExceptionHandler` answers JSON. A request with `Accept: image/jpeg`
  only (no `*/*`) must still get the right status (400/404/503), not a 406 or 500 from content negotiation; an API test
  covers it. Browsers send `image/...,*/*` for `<img>`.
- **Config**: `cinefilm.tools.ffmpeg` (existing) and `cinefilm.cache.dir` (existing). No new properties.
- **Security**: behind the session cookie like all `/api/**`; add the route to `RouteSecurityDriftTest`'s
  authenticated GET list; SECURITY.md rule 4's list of ffmpeg uses gains the still.

## Frontend

- `editionsApi.stillUrl(editionId, atMs)` in `src/api/resources.js` (no JSX), `atMs` floored to whole seconds on the
  client too so the browser cache key matches the server's.
- `Player.jsx`: when the stream's `initialSeekMs > 0` (resume, not "start from beginning"), render
  `<img className="player__still" src=... alt="" aria-hidden="true" decoding="async" fetchpriority="high">` **above
  the `<video>`, below the surface button and the indicator**, `object-fit: contain`, black behind, `pointer-events:
  none`, so the existing PREPARING indicator sits on top of the frame.
  - Shown only once it has loaded (`onLoad`), so a late or failed image never flashes. `onError` → never shown
    (silent black fallback).
  - Hidden for good on the video's first **`playing`**, on any **viewer seek** (the frame would be wrong), on the
    unavailable card, and on the end overlay. Hiding is a 200 ms opacity fade (none under
    `prefers-reduced-motion`), then unmounted. A load that completes after it was hidden is ignored.
  - Not used when the film starts at 0, and never re-shown on a reconnect or session restart (the video keeps its
    last frame then).
  - The hide/show rule is a small pure reducer in `src/player/` (no JSX) so Stryker reaches it; Player.jsx wires
    events to it.
- Handover §4, next to the Preparing/Buffering note: "When resuming, the frame at the resume point is shown behind
  PREPARING until the film plays."

## Tests

- `StillCommand`: exact argument list; seconds formatting; never a shell string.
- `StillService` (fake `CommandRunner`, temp dirs): single part; multi-part maps to part 2's file and offset; floor to
  seconds; end-of-part clamp; cache hit runs nothing; stamp change misses; 400 bounds; 404 cases (edition, no
  original, not playable, file missing); 503 on IOException, timeout, non-zero exit, missing output, semaphore busy
  (a runner that blocks on a latch, no sleeps); temp file removed on failure; pruning to the cap keeps the newest.
- Controller/API test: 200 `image/jpeg` with the cache header; 400/404/503 mapping; 401 without a session.
- `RealToolsTest`-style test (skips without ffmpeg, runs in CI): generate a 20 s `testsrc` clip, ask for 12 s,
  assert a JPEG (`FF D8`) at most 1280 wide.
- Frontend: `stillUrl`; the reducer (load→shown, error→never, playing/seek/unavailable/ended→hidden, load after
  hide ignored, start-at-0 → none); Player renders the still only on resume, under the indicator, removes it on
  `playing` and on a viewer seek. Floors as `test-standards.md` (95/90 per file, PIT 98, Stryker 95).

## Verification

Dev profile on Homebrew Postgres with `ffmpeg` installed and a real clip in the library root: open a film with a saved
position, see the frame behind PREPARING, then playback; `curl` the endpoint for 200/400/404; a second request is a
cache hit (no ffmpeg run in the DEBUG log).
