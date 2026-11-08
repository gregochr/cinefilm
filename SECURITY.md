# SECURITY.md — security review guidelines for CineFilm

CineFilm is a private, single-user movie server. It runs in Docker on `dockermacmini` (Ubuntu), reachable only on
the owner's tailnet through `tailscale serve`. Films are read from a read-only SMB share on `workhorse`. Reviewers
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
4. **External processes.** `ffmpeg`/`ffprobe` (phase 2+) are invoked with argument arrays, never a shell string, with
   timeouts and resource limits; file names are never interpolated into a command line. Treat probe output as
   untrusted input.
5. **Secrets.** `CINEFILM_PASSWORD`, `CINEFILM_DB_*`, `TMDB_API_KEY`, Plex tokens and the CineLight credentials come
   from the environment, never from the repo, and never reach the browser, API responses, logs, error messages or
   `/actuator`. Only `health` and `info` are exposed by actuator.
6. **Network exposure.** The app binds to `127.0.0.1` on the host and is published only via `tailscale serve`. The
   database publishes no host port. No Cloudflare tunnel, port forward or public listener. CORS stays off.
7. **Untrusted data in the UI.** TMDB, Plex and file metadata (titles, overviews, track names) are rendered as text,
   never with `dangerouslySetInnerHTML`. Image URLs come from the `imageUrl` helper only.
8. **Dependencies and CI.** No new dependency without a reason in the PR. OWASP/`npm audit` findings at High or above
   block. Workflows declare least-privilege `permissions:`, pin third-party actions, and never print secrets.
9. **Data.** The watch history is the owner's only irreplaceable data: migrations follow expand-then-contract, and
   deploys take a pre-release dump.

## Reporting

Single owner: open a private issue or tell the owner directly. Never put a real secret in an issue, PR or log.
