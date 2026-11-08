# Library scan and direct play of the real films — plan

Written 2026-11-08. Owner priority: **streaming and remote access working first.** This replaces the start of
product phase 2 in `foundation-plan.md` with the smallest slice that puts the real films on screen and playing.
Editions grouping, Fix match UI, scrubber sprites and transcoding come later.

## What we know about the films (owner, 2026-11-08)

- They live on workhorse at `/Volumes/Media Library/Movies/`, **flat**: one file per film, named `<Title>.m4v`
  (e.g. `10 Cloverfield Lane.m4v`, `101 Dalmations Cartoon.m4v` — note the owner's own spelling and suffixes).
  About 1,172 `.m4v` files over 500 MB across `Media Library` and `MediaBackup` together, so roughly 500–600 films.
  A few `.mp4` and `.avi` files exist elsewhere on the disk.
- Only `Movies` is shared (read-only SMB) and mounted into the container at `/media`. The rest of the disk holds
  personal documents and is never shared. `MediaBackup` and `TV Shows` are out of scope.
- `.m4v` is Apple's MP4: Safari on Mac and iPhone plays it natively, so **direct play with HTTP Range (built in
  phase 1A) is enough to stream them**, at home and over Tailscale. No transcoding is needed for this slice.

## Scope

1. **Library root.** `cinefilm.library.root` (env `CINEFILM_LIBRARY_ROOT`, default `/media` in prod; dev reads the
   same property). Media paths are stored **relative to the root** and resolved under it at read time; anything
   that resolves outside the root (`..`, symlink escape) is rejected (SECURITY.md rule 3).
2. **Scanner.** Walks the root recursively for `.m4v`, `.mp4`, `.mov` (playable candidates) and `.avi`/`.mkv`
   (recorded as *needs conversion*, not playable yet). Skips hidden files, `._*` AppleDouble files and anything under
   500 MB is still included (no size filter in code). Idempotent: a file is identified by its relative path; a
   rescan updates size/mtime, adds new files, and marks vanished files `missing` (never deletes watch history).
   `addedAt` = the file's creation time (fallback mtime) on first sight, so "Recently added" means something.
3. **ffprobe** (`ffprobe -v error -print_format json -show_format -show_streams <file>`), run with an **argument
   array via `ProcessBuilder`, never a shell**, with a timeout (60 s) and at most 2 concurrent probes (the share is
   SMB over the LAN and the host is a 2012 Mac mini). Parse: duration, container, video codec/width/height/bitrate,
   every audio stream (language, channels → "5.1"/"Stereo", codec, title, default), every subtitle stream
   (`mov_text`/`tx3g`, language, forced via disposition, title), and format tags (`title`, `date`/`©day`,
   `media_type`, `hd_video`, `description`). **FairPlay detection:** a video or audio stream with codec tag
   `drmi`/`drms`/`drmp` or an `encrypted` flag ⇒ the file is marked **protected** and not playable; show it in
   Admin's attention list, not in browse.
4. **Title and year guess.** Prefer the embedded `title` tag; else the file name without extension. Strip a trailing
   `(1999)` / `[1999]` year if present and use it. Year also from the `date` tag. Keep the original file name for
   Admin.
5. **TMDB matching** with the owner's **Read Access Token** (`TMDB_READ_TOKEN`, sent as `Authorization: Bearer`,
   never in a URL or log): `GET /3/search/movie?query=<title>&year=<year?>&include_adult=false`, then
   `GET /3/movie/{id}?append_to_response=credits` for the chosen result. Score candidates: normalised exact title
   match, year match (±1), **runtime within 8 minutes of the file's duration** (the handover's Wicker Man rule), and
   popularity as a tie-break. Accept automatically above a confidence threshold; otherwise store the top 5
   candidates and mark the film **needs a match**. Store: tmdbId, title, year, overview, genres, poster and backdrop
   paths, directors, top 10 cast with characters, and the TMDB collection (id, name, poster) plus its parts (for
   Collection pages). Rate-limit to ~20 requests/s, retry 429/5xx with backoff, cache by tmdbId so rescans don't
   refetch. A TMDB outage never fails the scan: the film is added with the file's own title and retried next scan.
   Credit TMDB in Admin ("This product uses the TMDB API but is not endorsed or certified by TMDB", with the logo).
6. **Model mapping.** One file ⇒ one Film with one Edition ("Original", runtime from ffprobe) and one MediaFile of
   kind ORIGINAL. Two files matching the same tmdbId are **both kept** as separate editions of one Film, and the
   film is listed in Admin as "N files match one film" (naming the editions is a later slice).
7. **Subtitles.** Embedded `mov_text` subtitles aren't exposed by Safari for a custom player. Add
   `GET /api/media-files/{id}/subtitles/{streamIndex}.vtt`: extract with `ffmpeg` (argument array, timeout) to
   WebVTT on first request, cache under `cinefilm.cache.dir` (env `CINEFILM_CACHE_DIR`, prod
   `/var/lib/cinefilm/cache`), serve with `text/vtt`. The player adds `<track kind="subtitles">` elements for the
   film's subtitle streams and applies decision 6 (forced track auto-on when Subtitles is Off). Films with no
   subtitle streams keep the "None for this film" state.
8. **Scheduling and triggers.** Scan on startup when the library is empty, nightly at 04:30 Europe/London (not
   Monday 04:00 UTC: goldenhour's ZAP), and on demand from Admin. Only one scan at a time.
9. **Admin, minimal.** Replace the placeholder with two real panels from the handover's Admin comp (`12-admin.png`):
   **Library scan** (folder, last scan time, counts — films, new, missing, protected, needs conversion, needs a
   match — and **Scan now** with live progress via polling `GET /api/admin/scan`), and **Films needing attention**
   (needs a match with its candidates shown read-only, protected, needs conversion, several files for one film).
   API: `GET /api/admin/scan`, `POST /api/admin/scan` (202, or 409 when a scan is running),
   `GET /api/admin/attention`. Fix match itself (choosing a candidate) is the next slice.
10. **Profiles.** The dev seed stays for `dev` only. `prod` starts empty, shows "The house lights are up", and its
    buttons point at Admin's Scan now.

## Tests (the standards in `test-standards.md` apply in full)

- ffprobe parsing from **recorded JSON fixtures** (an iTunes-style `.m4v` with AAC + AC-3 and `mov_text`, a
  HandBrake file, a FairPlay-protected file, an `.avi`, a file with no audio, a corrupt probe).
- TMDB client with recorded responses (`MockRestServiceServer` or WireMock), including 429 retry, outage, and the
  bearer header; matching scenarios: exact match, year disambiguation, runtime disambiguation (Wicker Man 1973 vs
  2006), the owner's misspelling (`101 Dalmations` → *101 Dalmatians*), cartoon vs live action resolved by runtime,
  and a low-confidence case that lands in *needs a match*.
- Scanner against a temp directory with fake files and a fake probe: add, rescan, rename, vanish ⇒ missing (history
  kept), `..`/symlink escape rejected, two files ⇒ one film with two editions.
- `ProcessBuilder` is always given an argument list (test the command builder), with timeouts.
- **Real-tool integration test in CI:** CI installs `ffmpeg`, generates a 2-second test clip with a `mov_text`
  subtitle, probes it, and extracts the VTT. Locally it's skipped when `ffprobe` isn't on the PATH (the Mac has
  none), tagged so the local gate excludes it.
- Frontend: Admin panels (polling, Scan now disabled while running, 409 handling, counts and attention list),
  `<track>` elements and the forced-subtitle rule in the player, empty-library buttons. Coverage floors unchanged.
- PIT and Stryker floors must still pass (the weekly runs).

## Owner actions

- Share **only** `/Volumes/Media Library/Movies` read-only from workhorse and mount it on dockermacmini (steps in
  `operations.md`).
- Put `TMDB_READ_TOKEN` in `~/cinefilm/.env` on dockermacmini.
