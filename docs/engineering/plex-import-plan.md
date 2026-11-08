# Plex import — plan

Written 2026-11-08, revised after an adversarial review the same day. Product phase 4 ("Plex import"), brought
forward: the owner is cancelling Plex, and the library as it stands should arrive with **its Plex identity, artwork
and watch history**, not re-guessed from TMDB. TMDB search stays for files Plex never knew ("TMDB for new additions",
owner).

## What the owner's Plex holds (read from a copy of the database, 2026-11-08)

- One movie library section, **1,087 films**; every live file is directly under `/Volumes/Media Library/Movies/`
  (the same flat folder CineFilm scans), no non-ASCII paths.
- **26 films have two files** (editions); **11** carry a Plex `editionTitle`.
- Nearly every film has a `tmdb://` and an `imdb://` guid.
- **378 watched, 143 with a resume point**, all under one Plex account (the owner's).
- **337 films use a poster the owner uploaded or chose** (`upload://`), the rest Plex's agent posters.
- 95 Plex collections.
- Plex once kept "Optimised for Motorhome" copies under `Movies/Plex Versions/` (deleted now).
- About 50 files on disk are not in Plex: they go through the existing TMDB search.

## Approach: read Plex's HTTP API, once, from dockermacmini

Plex Media Server keeps running on workhorse until the import is done (the free server works without Plex Pass).
CineFilm reads it over Tailscale (`PLEX_URL`, e.g. `http://100.92.70.50:32400`; WireGuard encrypts the hop) with
`PLEX_TOKEN` from `~/cinefilm/.env` (already saved there, mode 600; verified from dockermacmini). **The API, not the
SQLite file**: no Plex database parsing, nothing to copy onto the server, and the same channel serves the artwork.

- Config: `cinefilm.plex.url: ${PLEX_URL:}`, `cinefilm.plex.token: ${PLEX_TOKEN:}`,
  `cinefilm.plex.path-prefix: ${PLEX_PATH_PREFIX:/Volumes/Media Library/Movies/}` in `application.yml`. The feature
  is off when url or token is blank.
- Token only in the `X-Plex-Token` **header**, never a query string, never logged; retry/error logs name the call
  ("plex sections", "plex poster") never a URI (same pattern as `TmdbClient`). `SECURITY.md` already names Plex
  tokens as secrets; add the header-only rule. `Accept: application/json`.
- **GET only, to `PLEX_URL` only.** The client has no method that writes to Plex; redirects are not followed;
  responses are capped (JSON 50 MB, images 10 MB). Tests assert every request is a GET to the configured host.
- Calls: `GET /library/sections` (sections with `type=movie`), then per section
  `GET /library/sections/{id}/all?includeGuids=1`, paged with `X-Plex-Container-Start`/`-Size` (200) **until
  `MediaContainer.totalSize` is reached**. Per film it uses: `ratingKey`, `title`, `titleSort`, `year`, `summary`,
  `editionTitle`, `addedAt`, `thumb`, `art`, `Guid[]`, `viewCount`, `viewOffset`, `lastViewedAt`,
  `Media[].Part[].file`. (Genres are **not** taken from the list endpoint, which truncates tags: they come from
  TMDB.)
- Artwork through Plex's resizer so files stay small: `GET /photo/:/transcode?width=600&height=900&minSize=1&upscale=0&url=<thumb>`
  (poster) and `width=1920&height=1080` (backdrop), `url=` URL-encoded. A `thumb`/`art` value is used only if it
  matches `^/library/metadata/\d+/(thumb|art)/\d+$`; anything else (absolute URLs, `..`, `?`, `//`, encodings) is
  skipped and counted, so CineFilm never asks Plex to fetch an arbitrary URL.
- Pacing: at most 4 requests in flight, timeouts (10 s connect, 60 s read), 3 retries with backoff on 5xx and
  network errors. An unreachable Plex fails the import cleanly ("Plex is not reachable"), changing nothing.

## Pairing a Plex film with CineFilm's files (by edition)

The import runs **after a completed scan** (`scan_run.finished_at IS NOT NULL AND error IS NULL`): the scan owns the
files; the import only enriches them. Pairing is per file, so per **edition**.

1. Map Plex's path to CineFilm's: strip the path prefix to get the path relative to the library root (what
   `media_file.path` stores) and compare after **Unicode NFC normalisation**. Exact match only.
2. A Plex part outside the prefix, or under `Plex Versions/`, is ignored.
3. Plex films whose files CineFilm has not seen are reported ("In Plex, not on disk"), never created.
4. CineFilm files Plex does not know are untouched (they keep their TMDB result or *needs a match*).
5. **Guard:** if no file pairs at all (wrong `PLEX_PATH_PREFIX`), abort before writing anything and say so.

## What the import writes, per Plex film

Pure decisions (guid parsing, path mapping, watch-state merge, thumb validation) are static functions like
`WatchedRule`, so they are cheap to test and to mutation-test. Plex and TMDB I/O happen **outside** transactions
(the `LibraryIngest` rule); each Plex film is then written in **one short transaction**, so a failure part-way
leaves every finished film complete and the rest untouched, and a re-run picks up where it stopped.

1. **Bring the paired editions together under the right film.** Target tmdb id = the Plex film's `tmdb://` guid.
   - Every other CineFilm film with that tmdb id (it is not unique) is **merged** into one survivor with the shared
     merge below.
   - A paired edition that sits in a CineFilm film with a *different* identity (CineFilm put files that Plex keeps
     apart into one film) is **moved** to the target film (existing, or a new film created for it); a film left
     with no editions is deleted.
   - A Plex film with no tmdb guid keeps CineFilm's own match and only gets the Plex metadata below.
2. **Shared merge, extracted from `LibraryIngest.applyOutcome`** into a public, tested `merge(fromId, toId)`: moves
   editions (with their files, streams and `playback_state`, which follow the edition id) and `film_preference`,
   then deletes the source film (credits and genres cascade). The scan's own merge calls it too. Because
   `EditionRepository.moveAll` clears the persistence context, **merge first, reload the survivor, then write Plex
   data to it** — never write to a film before it might be merged away.
3. **TMDB by id, through the existing path:** `TmdbApi.movie(tmdbId)` + `applyMovie` (credits, genres, collection
   and its parts, TMDB poster/backdrop as a starting point). No search. Skipped when the film already has that tmdb
   id **and** credits; a film with the tmdb id but no credits (a TMDB outage last run) is fetched again.
4. **Overlay from Plex (Plex wins):** title, sort title (`titleSort` through a new explicit setter; else the
   existing derivation), year, overview (`summary`), `film.added_at` from Plex `addedAt` (so "Recently added" keeps
   its order), and poster/backdrop (below). Plex `editionTitle`, when set, becomes `edition.name`.
5. **Settle the match:** `match_status = MATCHED`, `match_candidates = NULL`. This is what stops
   `ScanService.retryPending` from ever re-searching and overwriting an imported film, and clears it from Admin's
   attention list. No `metadata_source` column: nothing else rewrites a matched film's metadata today; if a TMDB
   refresh is ever added it must skip Plex-imported films, and a test here pins today's behaviour.
6. **Watch state** (Plex's is per film, CineFilm's per edition), merged by a pure `PlexWatchMerge`:
   - `viewCount > 0` ⇒ **every paired edition** watched, `watched_at = lastViewedAt`.
   - `viewOffset > 0` ⇒ position on **the edition whose file Plex lists first**, passed through `WatchedRule` so a
     near-the-end offset becomes watched with position 0, exactly as `PlaybackService.reportProgress` would.
   - `last_played_at = lastViewedAt`; when Plex has no `lastViewedAt` (an offset without a completed view), use
     Plex `updatedAt`, else the import time.
   - **Never downgrade or overwrite newer:** an existing CineFilm state that is watched stays watched (even with a
     null `last_played_at`); a CineFilm state whose `last_played_at` is newer than Plex's is kept as is.
7. **Idempotent.** A second run makes no new rows, downloads no artwork it already has, and changes nothing.

## Artwork

- Downloaded through the resizer into the **artwork store**, `cinefilm.artwork.dir` (env `CINEFILM_ARTWORK_DIR`,
  prod `/app/artwork`): a **persistent data mount, not the cache** — after Plex is gone these files cannot be
  fetched again, so they are backed up with the database (phase 9 must include them).
- File name = SHA-256 of the bytes + `.jpg`/`.png` chosen from the response content type (anything else rejected),
  so re-imports dedupe and names cannot traverse. Written to a temp file and moved into place.
- `poster_path`/`backdrop_path` store **`/api/art/<sha>.<ext>`** directly (TMDB paths stay `/abc.jpg`), so every
  reader — `FilmMapper`, `CollectionService`'s fanned posters, anything later — just passes it through.
  `frontend/src/api/images.js` `imageUrl` returns a path starting `/api/art/` unchanged and builds TMDB URLs for the
  rest.
- `GET /api/art/{name}`: `name` must match `^[0-9a-f]{64}\.(jpg|png)$` (else 404), resolved inside the artwork dir
  only; `Content-Type` from the extension; `Cache-Control: private, max-age=31536000, immutable`. Behind the session
  like all `/api` routes (images are same-origin, so the cookie is sent). Update `RouteSecurityDriftTest` and the API
  contract in `foundation-plan.md`.

## One library lock

`ScanService`'s private running flag becomes a small shared `LibraryLock` used by the scan (startup, nightly 04:30,
Scan now) and the import. A scheduled scan that finds the import running is logged and skipped; a manual one gets
409 as today.

## Admin

- A **Plex import** panel on Admin, shown only when Plex is configured: reachable or not, last import time and its
  report — paired, merged, editions moved, metadata applied, posters/backdrops stored (and failures, and
  invalid-path skips), watched imported, resume points imported, kept because CineFilm was newer, *In Plex, not on
  disk* (titles, first 200 and a total), *On disk, not in Plex* (count), Plex collections not imported (names, first
  200). **Import from Plex** with progress polling, the Scan now pattern.
- API: `GET /api/admin/plex` → `PlexImportStatus`; `POST /api/admin/plex/import` → 202, 409 `"A scan or import is
  already running"`, 409 `"Run a library scan first"`.
- Nothing runs automatically.

## Schema (V4, expand-only)

- `plex_import_run` (id, started_at, finished_at, report `TEXT` (JSON, lists capped at 200), error `VARCHAR(500)`).
- No Plex ids stored on films: pairing is by path every run, and they mean nothing once Plex is gone.

## Scanner change

- Skip any directory named `Plex Versions` (Plex's optimised copies), alongside hidden and `._*` files.

## Deploy and docs

- `deploy/docker-compose.yml`: `${CINEFILM_DATA_DIR}/artwork:/app/artwork`; `deploy.sh` creates `artwork` with the
  other data dirs, owned by the container user (uid 1001) like `cache`. `PLEX_URL`, `PLEX_TOKEN`, `PLEX_PATH_PREFIX`
  from `~/cinefilm/.env`.
- `operations.md`: the Plex settings; "keep Plex running on workhorse until the import report is right, then it can
  be uninstalled"; the artwork dir is backed up with the database.
- `SECURITY.md`: Plex token header-only; the importer only GETs from `PLEX_URL`, follows no redirects and only
  fetches validated `/library/metadata/...` artwork paths; artwork names are content hashes validated on read.
- `foundation-plan.md`: API contract (`/api/art`, the two admin routes, `posterPath` may be `/api/art/...`); phase 4
  marked as this plan.

## Tests (`test-standards.md` in full)

- Plex client with a `RestClient` mock server and **synthetic** JSON (never the owner's library): sections, paging to
  `totalSize` across pages, guids, editions, missing fields; token only as a header and in no URL; every request a
  GET to the configured host; redirects not followed; size caps; unreachable Plex; 5xx retry; logs carry no URI or
  token.
- Pure functions: guid parsing; prefix strip + NFC (NFC vs NFD names pair), `Plex Versions/` and outside-prefix
  ignored; thumb validation (valid path, absolute URL, `..`, `?`, `//`, percent-encoding); `PlexWatchMerge` (every
  rule above, including no-downgrade, CineFilm newer, missing `lastViewedAt`, near-end offset ⇒ watched).
- Import service:
  - metadata overlay and `MATCHED`/candidates cleared; **then a scan whose TMDB returns a different hit leaves the
    film unchanged**;
  - two CineFilm films with the Plex tmdb id ⇒ one film, two editions, preferences and playback kept, Plex data on
    the survivor;
  - one CineFilm film holding two Plex films ⇒ the edition moves (to an existing film, and to a new one);
  - no tmdb guid ⇒ CineFilm match kept;
  - TMDB outage ⇒ film keeps Plex metadata and is fetched again next run;
  - zero pairs ⇒ abort, nothing written;
  - a failure on one film leaves the others committed;
  - re-run idempotent (row counts and artwork downloads unchanged).
- Shared `merge`: the scan's existing merge tests still pass through it.
- Artwork: hash naming, content-type allow-list, temp-then-move; `/api/art/{name}` rejects `..`, `%2f`, uppercase,
  other extensions, names outside the store, and needs the session.
- `LibraryLock`: import blocks scan and vice versa; scheduled scan skipped and logged.
- Admin endpoints: 202, both 409s, status and report shape, report capped.
- Frontend: `imageUrl` passthrough for `/api/art/`, collection cards with art paths, the Plex import panel (hidden
  when not configured, polling, 409s, report lists). Coverage, PIT and Stryker floors unchanged.

## Owner actions

- Done: Plex token in `~/cinefilm/.env` on dockermacmini; dockermacmini reaches Plex over Tailscale.
- Add `PLEX_URL=http://100.92.70.50:32400` to that `.env` during the dockermacmini setup.
- Keep Plex Media Server running on workhorse until the import report is right.
- After the first deploy and scan: Admin, **Import from Plex**, read the report.

## Open questions for the owner (non-blocking; defaults in bold)

1. Plex's 95 collections: **not imported now**; the report lists them and the owner picks any worth keeping.
2. Films whose Plex title differs from TMDB's: **Plex's title wins** (it's the owner's library).
