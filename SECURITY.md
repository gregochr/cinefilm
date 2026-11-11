# SECURITY.md — security review guidelines for CineFilm

CineFilm is a private, single-user movie server. It runs in Docker on `dockermacmini` (Ubuntu), reachable only on
the owner's tailnet (Caddy at https://cinefilm.chrisjgregory.dev, bound to the tailnet addresses only; `tailscale serve` on :8443 as a fallback). Films are read from a read-only SMB share on `workhorse`. Reviewers
(Codex, Claude) flag any change that weakens the rules below, and name the rule.

## Threat model

- **In scope:** anyone on the tailnet or the home LAN who isn't the owner; a stolen session cookie; a malicious
  or malformed media file or TMDB/Plex response; a compromised dependency; mistakes that expose the app beyond the
  tailnet.
- **Out of scope:** an attacker with shell access to dockermacmini or workhorse.

## Rules

1. **Authentication.** One password from `CINEFILM_PASSWORD`, compared in constant time. Every `/api/**` route
   except `GET/POST/DELETE /api/session` requires the session; `RouteSecurityDriftTest` must keep proving that. 401s
   are JSON, never a redirect. No new unauthenticated route without an explicit note in the PR. The dev fallback
   password (`changeme`) must never work under the `prod` profile.
2. **Sessions and CSRF.** Session cookies are HTTP-only, `SameSite=Lax`, and `Secure` behind HTTPS
   (`X-Forwarded-Proto` trusted from the local proxy only). Every mutating request needs `X-CineFilm: 1`; don't
   loosen the filter or re-enable token CSRF without replacing it.
3. **File access.** The stream endpoint and anything else that reads media serve only paths stored in the database,
   resolved under the configured library root, never a path, filename or URL taken from the request. Reject `..`,
   symlinks out of the root, and absolute paths from imports. The media mount is read-only; nothing writes to it.
   **Only `/Volumes/Media Library/Movies` is ever shared from workhorse** (a dedicated read-only SMB share). The
   rest of that disk, above all **`Pictures`** (irreplaceable to the owner) and the personal documents, must never be
   shared, mounted, scanned or written by CineFilm, its scripts or its CI. Read-only is enforced three times: the SMB
   share, the `ro` cifs mount, and the container's `:ro` volume. Any change that widens the share, the mount or the
   library root is a blocking finding.
4. **External processes.** `ffmpeg`/`ffprobe` are invoked with argument arrays, never a shell string, with
   timeouts and resource limits; file names are never interpolated into a command line. Treat probe output as
   untrusted input. Transcoding: `-protocol_whitelist file`, input only the database-known path under the library root,
   outputs only under the session directory (`cache/hls/<uuid>/`), every session URL segment regex-validated and
   bounded, the MP4 box reader bounds every size it reads, and resources are capped (3 sessions, idle stop, look-ahead
   cap, `hls/` cap, bounded threads, encoders killed on shutdown). A session that went quiet (no request for 10
   minutes, or idle while `hls/` is over its cap) is kept in memory only as what rebuilding it needs: the spec the
   database gave when it was made, its files' size and modification time, and its start time, last request, playhead
   and last rung. At most 8 are kept, and none is rebuilt 24 hours or more after its last request. Its next request
   rebuilds it under the same id only while every file still has that size and modification time (the database is not
   asked again). A rebuilt session counts towards the 3, pushes out the least recently used session for good at the
   cap (even when a playlist request rebuilt it), and starts no encoder until a segment is asked for. A session that
   was ended, replaced or pushed out at the cap, whose encoder could not run, or that was lost to a restart is never
   rebuilt (`docs/engineering/long-pause-plan.md`). `/dev/dri` is the only device passed in. The
   resume still (`GET /api/editions/{id}/still`) takes one number from the request (`atMs`, bounds-checked against the
   edition); the input is the database-known path, `-protocol_whitelist file`, output only under `cache/stills/`, at
   most 2 grabs at once, a 10 s timeout, and the cache is capped at 300 files.
5. **Secrets.** `CINEFILM_PASSWORD`, `CINEFILM_DB_*`, `TMDB_READ_TOKEN`, Plex tokens and the CineLight credentials come
   from the environment, never from the repo, and never reach the browser, API responses, logs, error messages or
   `/actuator`. The Plex token is sent only in the `X-Plex-Token` header, never in a URL, and logs and errors name the
   call ("plex sections"), never an address or the token. Only `health` and `info` are exposed by actuator.
6. **Network exposure.** The app binds to `127.0.0.1` on the host and is published only through the tailnet-bound Caddy proxy (and `tailscale serve`). Caddy never binds 0.0.0.0, the LAN or port 80, and its Cloudflare token is DNS-edit scoped to one zone. The
   database publishes no host port. No Cloudflare tunnel or proxying (the DNS records are grey cloud), no `tailscale funnel`, port forward or public listener. CORS stays off.
7. **Untrusted data in the UI.** TMDB, Plex and file metadata (titles, overviews, track names) are rendered as text,
   never with `dangerouslySetInnerHTML`. Image URLs come from the `imageUrl` helper only.
   **The operator lookup** is the browser's one request to a host other than CineFilm and TMDB's images: when the
   player opens away from home it asks `https://ipinfo.io/json` which network operator the device is on. It is
   made from a hidden `sandbox="allow-scripts"` frame built from `srcdoc`, with `credentials: 'omit'` and
   `referrerPolicy: 'no-referrer'`, so the request carries `Origin: null`, no referrer and no cookie: the service
   sees the device's public address and nothing about CineFilm. Never give that frame `allow-same-origin`, and
   never make the request from the page itself (it would send the app's hostname as `Origin`). The page takes a
   message only from that frame's own window and only when the message's origin is the string `null`, which is
   what a sandboxed frame reports: a frame that had lost its sandbox would report the app's own origin and be
   ignored, so the lookup fails closed. It keeps only `org`, as text of 1 to 100 characters, and stores an answer
   against it in `localStorage` only. No location is requested or stored. Any other outside request from
   the browser needs a note in the PR.
8. **Dependencies and CI.** No new dependency without a reason in the PR. OWASP/`npm audit` findings at High or above
   block. Workflows declare least-privilege `permissions:`, pin third-party actions, and never print secrets.
9. **Data.** The watch history is the owner's only irreplaceable data: migrations follow expand-then-contract, and
   deploys take a pre-release dump.

10. **Plex import.** The importer only issues GET requests to `PLEX_URL` (no method writes to Plex), follows no
    redirects, caps response sizes (JSON 50 MB, images 10 MB), and fetches artwork only for `thumb`/`art` values that
    match `^/library/metadata/\d+/(thumb|art)/\d+$`, URL-encoded into Plex's resizer, so it can never be made to fetch
    an arbitrary URL. Stored artwork names are SHA-256 content hashes with a `.jpg`/`.png` extension, validated against
    `^[0-9a-f]{64}\.(jpg|png)$` on every read of `/api/art/{name}` (behind the session), never a path from the request.
    `GET /api/art/{name}?w=` serves a copy of a stored image resized to one of the widths 342, 500, 780 or 1280: `w`
    must equal one of those four strings (anything else is a 400) and the copy is built from the constant, never the
    request. Copies are made on first request with the JDK's own image reader and writer, kept under
    `cache/art/<width>/<name>` and served only while the stored file is there and referenced; nothing is read from a
    path in the request. Size and bit depth are read from the header before anything is decoded: an image over 4096 px
    on either side, over 4 megapixels or deeper than 8 bits, or one the reader warns about, is served as stored, never
    decoded further. At most two resizes run at once (a request that waits 10 s for a slot gets the stored file,
    `no-store`), and the resized cache is capped at 128 MiB, oldest files removed first.
11. **Browser caching.** API responses are `no-store` (Spring Security's default) except: the browse GETs
    (`/api/home`, `/api/films`, `/api/films/{id}`, `/api/people/{id}`, `/api/collections`, `/api/collections/{id}`)
    and `/api/search-index`, which are `no-cache, private` with a weak ETag (the browser keeps a copy and asks the
    server before every use; a 304 carries no body); WebVTT subtitles (`no-cache, private`); artwork, stored or
    resized (`private, max-age=31536000, immutable`, since names are content hashes, or `private, max-age=86400`
    for a copy that could not be resized, `no-store` when the resizer was busy); and the resume still
    (`private, max-age=86400`). HLS playlists and segments are `no-store`. Nothing under `/api/admin`, no session
    route and no mutating response is cached. Any other exception needs a note in the PR.

## Reporting

Single owner: open a private issue or tell the owner directly. Never put a real secret in an issue, PR or log.
