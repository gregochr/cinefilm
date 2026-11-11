# Browse: artwork sized to need, lighter browse data — plan (phase 4 of the network resilience review)

Written 2026-11-11. Phase 4 of `network-resilience-review.md`: its items 9 and 10 (section C) and two items of section
E's "Small" line (load the hls.js chunk when Film detail opens; `hls.js/light`). It does not depend on the playback
measurements. Built by Sonnet under the usual gates, one pull request per part below. Reviewed adversarially the same
day; the changes are folded in and listed at the end.

## Parts and pull requests

| PR | Scope | Side | Releasable alone |
|---|---|---|---|
| **A** `feat(art)` | Item 9: `/api/art/{name}?w=`, resized on first request and cached; `imageUrl` asks for the width it is given | backend, small frontend | yes: without `?w` the route is unchanged |
| **B** `feat(api)` | Item 10, server side: the film list without `backdropPath`; weak ETags and `no-cache, private` on the browse GETs | backend, fixtures | yes: the old frontend never read the list's `backdropPath` |
| **C** `feat(browse)` | Item 10, client side: an in-memory cache in `useResource`, Back without a skeleton, Play without a second fetch | frontend | yes |
| **D** `perf(player)` | Section E: `hls.js/light`. The preload on Film detail is **left out** (below) | frontend | only after phase 2 has merged |

Built and merged in the order A, B, C, D, each from the updated `main`. A and B both edit `SECURITY.md` and
`foundation-plan.md`, so B starts after A has merged. D waits for phase 2 (`poor-link-plan.md`), which rewrites
`streamSource.js`, the file D changes; if phase 2 has not merged when C has, D is left out and the owner is told.

## What the code does today (re-read on `main` at `600da54`)

- Plex artwork is stored at **600×900** (posters) and **1920×1080** (backdrops) (`PlexImportService.java:54-57`; Plex
  never upscales, `PlexClient.java:132-133`) and served whole by `GET /api/art/{name}` (`ArtworkController.java:26-36`:
  name checked against `^[0-9a-f]{64}\.(jpg|png)$` by `ArtworkStore.file`, then that some film refers to it, then
  `private, max-age=31536000, immutable`). `imageUrl` passes `/api/art/…` through whatever size it is given
  (`images.js:13-17`).
- Sizes asked for: Library and Home posters `w342` (`Art.jsx:18` default), Film detail poster `w500` and backdrop
  `w1280` (`FilmDetail.jsx:129-146`), the Home hero `w1280` (Art's backdrop default), **Continue watching stills
  `w1280`** (`ContinueCard.jsx:89` passes no size, and Art's default for anything but a poster is the backdrop size),
  search thumbnails `w92`, person photos `w185` (TMDB only). `BACKDROP_SIZE_PHONE` (`w780`) is defined and unused.
  Library tiles are 160–200 px wide (`browse.css:198-209`), Continue watching stills 280–420 px.
- `GET /api/films` returns every film as a `FilmCard` (`FilmService.java:50-56`, `FilmMapper.card`), including
  `backdropPath`, which the Library never shows (only the Home hero, Continue watching and Film detail read
  `backdropPath`, all from other endpoints).
- The browse GETs (`/api/home`, `/api/films`, `/api/films/{id}`, `/api/people/{id}`, `/api/collections`,
  `/api/collections/{id}`) return DTOs with Spring Security's default `Cache-Control: no-cache, no-store, max-age=0,
  must-revalidate`. Only `/api/search-index` has an ETag (`SearchIndexController`, `SearchIndexCache.etag`:
  `W/"<first 16 hex of SHA-256>"`, `Cache-Control: no-cache`, 304 on a match). Subtitles are `no-cache, private`
  without a validator (`SubtitleController.java:29`, `EditionSubtitleController.java:29`).
- `useResource` (`state/useResource.js`) keeps nothing between mounts: Back from a film shows the skeleton and fetches
  again, and the Player (`Player.jsx:73`, `useFilm(id)`) fetches the film Film detail has just loaded before
  `PlayerView` can mount and start the session. Every fetch sets `loading: true` (`useResource.js:25`), and the
  Library dims its grid while it is (`LibraryFilms.jsx:91`, `poster-grid--busy`).
- hls.js is loaded on first play (`streamSource.js:8`, `import('hls.js')`).
- Production runs the JVM at `-Xmx512m` (`deploy/docker-compose.yml:38`), with virtual threads; the host's root disk
  had about 5.8 GB free beside a 5 GB cap on `hls/` (`operations.md:23`).

## Assumption the owner can confirm (artwork sizes)

The review said to measure the average stored artwork size on dockermacmini first. That host cannot be reached from
here, so this plan uses estimates. With four macOS desktop photographs cut to Plex's sizes and saved at JPEG quality
90 (a stand-in for Plex's resizer, whose quality is not known), and resized with `sips` at quality 82:

| Use | Stored | Resized | Ratio (bytes) |
|---|---|---|---|
| Library / Home poster (`w342`) | 600×900: 92–244 KB, mean 144 KB | 342 wide: 18–72 KB, mean 40 KB | about 3.6× |
| Film detail poster (`w500`) | the same | 500 wide: 42–138 KB, mean 79 KB | about 1.8× |
| Continue watching still (`w780`) | 1920×1080: 305–535 KB, mean 384 KB | 780 wide: 32–76 KB, mean 51 KB | about 7.5× |
| Hero and Film detail backdrop (`w1280`) | the same | 1280 wide: 96–204 KB, mean 134 KB | about 2.9× |

Real posters are less detailed than flower close-ups, so their files are smaller on both sides; the ratios are what
the plan relies on.

**Measured with the resizer as built (PR A)**, on the same four posters and four backdrops (JPEG quality 0.82,
progressive): posters at 342 average 23 KB, 6.2× fewer bytes than the stored 144 KB, and at 500 41 KB (3.5×);
backdrops at 780 average 30 KB, 12.8× fewer than the stored 384 KB, and at 1280 70 KB (5.5×). One 1920×1080 → 780
resize took a median 0.47 s with the laptop's load average above 100; a quiet machine takes a fraction of that.

**To confirm on dockermacmini** (counts and average size of the stored posters and backdrops):

```bash
cd /home/gregochr/cinefilm-data/artwork && for f in *.jpg; do echo "$(file -b "$f" | grep -oE '[0-9]+x[0-9]+' | tail -1) $(stat -c %s "$f")"; done | awk '{split($1,d,"x"); k=(d[1]>=1000)?"backdrops":"posters"; n[k]++; s[k]+=$2} END {for (k in n) printf "%s: %d files, %.0f KB average\n", k, n[k], s[k]/n[k]/1024}'
```

## A. Artwork sized to need (item 9)

### API

`GET /api/art/{name}` gains an optional query parameter `w`.

- **No `w`:** exactly as today (the stored file, `private, max-age=31536000, immutable`).
- **Order of checks:** the name is checked first, as today (pattern, file present, referenced by a film; else 404
  `No such artwork`, **whatever `w` is**). Then `w` must be **exactly** one of the strings `342`, `500`, `780`,
  `1280`; anything else (`0`, `341`, `343`, `abc`, an empty value, `0342`, `342.0`, ` 342`, two `w` parameters) is
  400 `Width must be 342, 500, 780 or 1280`. The width used afterwards is the matching constant, never the request
  string.
- **Resized:** the stored image scaled to that width, height `round(h × w / sourceWidth)` (at least 1), in the
  stored file's own format (JPEG stays JPEG, PNG stays PNG), with the stored file's content type and the same
  `private, max-age=31536000, immutable`: the name is a hash of the source and the width is fixed, so the bytes at
  that URL never change.
- **The stored file instead, `immutable`:** when the stored image is not wider than `w` (a 600-wide poster asked for
  at 780; never upscaled), or when the resized copy would not be smaller in bytes than the stored file (an indexed
  PNG can grow when re-encoded). Nothing is cached for these.
- **The stored file instead, `private, max-age=86400`:** when it cannot be resized: not readable as the format its
  name says, any reader warning (the JDK's JPEG reader accepts a truncated file with only a warning and pads it
  grey), samples deeper than 8 bits, more than 4096 px on either side, or more than **4,194,304 pixels** (4 MP:
  twice a 1080p backdrop; real inputs are at most 1920×1080). A day, not a year, so a later resizer fix reaches the
  browser. Size and depth are read from the header **before** a slot is taken and before any pixel is decoded.
- **Busy:** at most **2** resizes run at once; a request waits up to **10 s** for one of the two slots (virtual
  threads, so no platform thread is held). If none comes free it gets the stored file with `Cache-Control:
  no-store`, so the full-size copy is never cached under the resized URL.

### Resizing (`plex/ArtworkResizer`, new; no new dependency)

The JDK's `javax.imageio` (in the runtime image's full JRE, `eclipse-temurin:25-jre`; Spring Boot runs headless):

- An `ImageReader` for the stored file's format with an `IIOReadWarningListener` that marks the read failed on any
  warning. Header first (width, height, bit depth from the image type), the limits above, then decode.
- Scale without aliasing: halve with bilinear interpolation while the image is at least twice the target width, then
  one bilinear step to the exact size (a single step from 1920 to 342 aliases badly). The number of halvings is a
  pure function, tested at 2×−1, 2× and 2×+1 the target.
- JPEG out: RGB (no alpha), quality **0.82**, **progressive** (the JDK's writer accepts both; it renders
  coarse-to-fine on a slow link). PNG out: the default PNG writer, alpha kept when the source has it. Written through
  a `MemoryCacheImageOutputStream` (ImageIO's default stream cache writes temporary files). No metadata copied (Plex's
  resizer output carries no EXIF orientation). Readers and writers are created per call (not thread-safe).
- The result is one of: resized bytes, not wider, cannot be resized.

### Cache (`plex/ResizedArtwork`, new service)

- File: `<cinefilm.cache.dir>/art/<width>/<name>`, built from the constant width and the validated name. Written to a
  temporary file in the same folder and moved into place (as `ArtworkStore.write` does), so a reader never sees half
  a file. Concurrent misses for the same file may both resize; the last move wins with identical bytes.
- A resized copy is served only while the stored file is there and referenced (the controller's checks run first),
  so a copy of swept artwork is never served and is pruned in time.
- Read inside the service into memory (a few hundred KB at most); a file pruned between the existence check and the
  read counts as a miss and is made again (as `StillService.readCached` does).
- **Bounded at 128 MiB** (the host's disk is tight). After each write, when a running total (seeded by one walk of
  `art/` on first use) passes the cap, the writer prunes: **one prune at a time**, and no writer is skipped (each
  waits its turn and prunes only if the total is still over the cap by then). A prune lists `art/`, sums the real
  sizes, deletes the oldest files by modified time (never the one just written) until the total is at most 90% of the
  cap, and deletes leftover `.tmp` files older than an hour. It corrects the running total by a delta (the real figure
  minus the total it started from), so a write that lands during a prune stays counted; a copy counts as freed only
  when its delete succeeded, and a writer whose copy is already there (another request has just made it) neither
  writes nor counts it. Working set: posters at 342
  for the whole library (about 1,100 × 25–40 KB ≈ 30–45 MB) plus the stills, posters and backdrops of films opened;
  the cap holds it, and an evicted copy is made again in a fraction of a second.
- A failed cache write still returns the resized bytes.
- Log lines are constant messages (no request value), so CodeQL's log-injection rule has nothing to flag. If CodeQL
  raises `java/path-injection` on the new file reads and writes (it did on `ArtworkController`, and was dismissed),
  the planner dismisses it in GitHub with the reason (the name is pattern-checked, the width a constant) and names it
  in the PR; the ruleset blocks the merge until then.

### Frontend

- `images.js`: for a stored path (`/api/art/…`) `imageUrl(path, size)` returns `${path}?w=${width}` with
  `w92`, `w185`, `w342` → 342, `w500` → 500, `w780` → 780, `w1280` → 1280. An unknown size returns the path
  unchanged (full size). TMDB paths are unchanged. New export `STILL_SIZE = 'w780'`.
- `Art.jsx`: the default size follows the kind: poster `w342`, **still `w780`**, backdrop `w1280`. Continue watching
  stills then ask TMDB for `w780` too. (`ContinueCard.jsx` needs no change: it passes no size.)
- Search thumbnails (32 px boxes) get the 342 copy, three times fewer pixels than today's 600 but more than they need;
  a 185 width is a follow-up for the owner to decide, not in this set.

### SECURITY.md rule 10 (extend; Sonnet applies this text)

> `GET /api/art/{name}?w=` serves a copy of a stored image resized to one of the widths 342, 500, 780 or 1280: `w`
> must equal one of those four strings (anything else is a 400) and the copy is built from the constant, never the
> request. Copies are made on first request with the JDK's own image reader and writer, kept under
> `cache/art/<width>/<name>` and served only while the stored file is there and referenced; nothing is read from a
> path in the request. Size and bit depth are read from the header before anything is decoded: an image over 4096 px
> on either side, over 4 megapixels or deeper than 8 bits, or one the reader warns about, is served as stored, never
> decoded further. At most two resizes run at once (a request that waits 10 s for a slot gets the stored file,
> `no-store`), and the resized cache is capped at 128 MiB, oldest files removed first.

### Tests

- `ArtworkResizerTest` (real `ImageIO` on images the test draws): 600×900 JPEG at 342 → 342×513 progressive JPEG;
  1920×1080 at 780 → 780×439; a PNG with alpha stays PNG with alpha; the width boundary (a 499-, 500- and 501-wide
  source at 500: not wider, not wider, resized); the limits at the boundary from the header, without decoding: 4096
  and 4097 px a side, 4,194,304 and 4,194,305 pixels, an 8-bit and a 16-bit PNG; random bytes and a truncated JPEG
  cannot be resized (the warning listener); the halving count at 2×−1, 2× and 2×+1; a resized copy that would not
  be smaller is reported as such.
- `ResizedArtworkTest` (temporary folder): a miss writes `art/342/<name>` and a second call reads it without resizing
  (a counting resizer); not wider, not smaller and cannot be resized return "serve the stored file" with the right
  kind; the cap prunes the oldest down to 90% and never the file just written (a small cap injected); a write that
  lands during a prune prunes after it if the total is still over the cap, does not if it is not, and keeps its size
  in the total; a file pruned between check and read is made again (a seam, as `StillServiceTest` does);
  with both slots held, a request after the (injected, short) wait gets "busy", and the header check of an oversized
  image needs no slot; a failed cache write still returns the bytes. No `Thread.sleep`: hold the slots with a latch.
- `ArtworkControllerTest` (slice): `?w=342` returns the resized bytes, the source's content type and the immutable
  header; each bad `w` above is 400 with the exact error; an unknown or unreferenced name with a good **or a bad** `w`
  is 404 `No such artwork`; no `w` is unchanged; busy returns the stored file with `no-store`; not wider and not
  smaller return the stored file with the immutable header; cannot be resized returns it with
  `max-age=86400, private`. 401 and route drift are covered by `RouteSecurityDriftTest`.
- Surefire's `argLine` and PIT's `jvmArgs` gain `-Djava.awt.headless=true`: these tests run without Spring, and on
  the Mac `createGraphics()` would otherwise start the window system (a Dock icon, or a failure without one).
- Frontend: extend the existing `imageUrl` tests rather than starting a new file, and update the two that assert
  today's pass-through (`api/resources.test.js:213-217`, `components/CollectionCard.test.jsx:51-61`): every size for a
  stored and a TMDB path, `null` / `''` / `undefined` paths, an unknown size, the default size. `Art.test.jsx`: a still
  asks for `w780` from TMDB and `?w=780` for stored art (rename the test that says stills use the backdrop size); a
  poster `w342`; a backdrop `w1280`.

### Docs

`foundation-plan.md` (the `GET /api/art/{name}` row), `SECURITY.md` rule 10, `operations.md` (the data table: `cache`
now also holds resized artwork, at most 128 MiB), the handover's "Images and data" (one line: stored artwork is
served at the same widths), `changelog.d/`.

## B. Browse data on the server (item 10, server side)

### The film list without `backdropPath`

- New record `FilmDtos.LibraryCard`: every `FilmCard` field except `backdropPath`, in the same order.
  `FilmList.films` becomes `List<LibraryCard>`; `FilmMapper.libraryCard(view, qualifying, minMs)` builds it (share the
  code with `card`, do not copy it). Every other endpoint keeps `FilmCard` with `backdropPath`.
- Frontend: the list fixtures (`test/fixtures/films.json`, `films-empty.json` if it has cards) drop `backdropPath`
  from cards, and `api/fixtureServer.js`'s `/api/films` answer has none. Nothing in the frontend reads it from the
  list (checked: only `Home.jsx` hero, `ContinueCard.jsx` and `FilmDetail.jsx`, all from other endpoints).

### Weak ETags and `no-cache, private`

- A shared component in a **new package `gregochr.cinefilm.web`** (PIT excludes `config.*`, and this logic should be
  mutation-tested as `SearchIndexCache.etag` is today), for example `web/JsonEtag`. It serialises a body with the
  application's `JsonMapper` (as `SearchIndexCache` does), takes the weak ETag of the bytes (`W/"` + first 16 hex of
  SHA-256 + `"`, the existing rule, moved here from `SearchIndexCache.etag`), and answers:
  - `If-None-Match` names it (the existing `SearchIndexController.matches`, moved here: a comma list, `*`, weak or
    strong, quoted or not) → **304**, no body, with `ETag` and `Cache-Control`;
  - otherwise **200**, `Content-Type: application/json`, the bytes, `ETag`, `Cache-Control`.
  - `Cache-Control` is `CacheControl.noCache().cachePrivate()` (Spring renders `no-cache, private`). Setting it stops
    Spring Security writing its `no-store` default (and its `Pragma` and `Expires`) on these responses.
- Used by the six browse GETs and by `/api/search-index` (which keeps its memo and gains `private`). Slice tests
  `@Import` the component. The ETag is the hash of the exact bytes sent, so anything time-dependent (the weekly theme,
  Recently added) changes it too. Errors (404 and so on) are unchanged: no ETag, Spring Security's `no-store`.
- The browser then stores these responses and revalidates them on every use by itself; an unchanged list costs a
  304 of a few hundred bytes instead of the whole body. Tomcat still compresses them (weak ETags). One change of
  detail: a `byte[]` body has a `Content-Length`, so Tomcat's 2 KB minimum now applies and a browse answer under 2 KB
  goes uncompressed (today's streamed JSON is compressed at any size); nothing that size is worth compressing.

### SECURITY.md (new rule 11, Sonnet applies this text; written for after A has merged)

> 11. **Browser caching.** API responses are `no-store` (Spring Security's default) except: the browse GETs
>     (`/api/home`, `/api/films`, `/api/films/{id}`, `/api/people/{id}`, `/api/collections`, `/api/collections/{id}`)
>     and `/api/search-index`, which are `no-cache, private` with a weak ETag (the browser keeps a copy and asks the
>     server before every use; a 304 carries no body); WebVTT subtitles (`no-cache, private`); artwork, stored or
>     resized (`private, max-age=31536000, immutable`, since names are content hashes, or `private, max-age=86400`
>     for a copy that could not be resized, `no-store` when the resizer was busy); and the resume still
>     (`private, max-age=86400`). HLS playlists and segments are `no-store`. Nothing under `/api/admin`, no session
>     route and no mutating response is cached. Any other exception needs a note in the PR.

### Tests

- `JsonEtagTest` (unit): the ETag format and its 16 hex characters for known bytes; `matches` for an exact weak tag,
  the strong form of the same value, an unquoted value, `*`, a comma list with spaces, a different tag, an empty
  header (the existing search-index cases move with the code).
- One slice test per route (`HomeControllerTest`, `FilmControllerTest`, `PersonControllerTest`,
  `CollectionControllerTest`, and the search index's): a 200 carries `ETag: W/"…"` and exactly `no-cache, private`
  (no `no-store`, no `Pragma`); the same request with that `If-None-Match` is a 304 with an empty body and the same
  ETag; a different tag gets the 200 and the body. A 404 has no ETag. `SearchIndexControllerTest` and
  `SearchIndexApiTest` (and the CI-only `SearchIndexIntegrationTest`) now expect `no-cache, private`.
- `FilmsApiTest` (full context): a list card has no `backdropPath` key (`doesNotExist()`; update
  `filmCardHasTheContractFields`) while `/api/home`'s and `/api/films/{id}`'s still have it; **after Mark as watched,
  a conditional `GET /api/films` with the earlier ETag is a 200 with the film watched and a new ETag** (a change is
  never hidden behind a 304); after Remove from Continue watching, `GET /api/home` likewise. `FilmServiceTest` moves
  to `LibraryCard`.
- A Tomcat-level test (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, signed in, `Accept-Encoding: gzip`) that
  `/api/films` comes back gzipped with its weak ETag; the body must be over 2 KB (enough films). Phase 2 adds
  `config/CompressionTest`; name this class differently (for example `web/BrowseCachingTest`) so the two never
  collide.

### Docs

`foundation-plan.md`: `FilmCard` note (no `backdropPath` in `GET /api/films`), the caching headers on the browse GETs
(one sentence under the REST table, and the search index row's `no-cache` becomes `no-cache, private`); `SECURITY.md`
rule 11; `changelog.d/`.

## C. A small in-memory cache in `useResource` (item 10, client side)

### Behaviour

`useResource(key, fetcher, options)`; without `options.cache` it behaves exactly as today (the Admin hooks keep it).
Ages are measured with `Date.now()` (wall clock, injectable for tests), never `performance.now()`, which stands still
while a Mac sleeps. The effect depends on `options.cache` and `options.maxAgeMs` (primitives), not on the options
object, so a render never refetches.

- **`cache: 'revalidate'`** (Home, Library list, Film detail, Person, Collections, Collection):
  1. An answer for this key that the server gave less than **30 minutes** ago is shown **in the very first render**
     (no skeleton frame) and the resource is fetched again in the background. **`loading` stays false throughout a
     background revalidation**; only a miss, `reload()` and the refetch after sign-in set it (so the Library grid is
     not dimmed on Back). With B in place the background fetch is usually a 304.
  2. The background answer replaces what is shown, except when it is JSON-equal to it: then the shown object is kept
     (same reference), so the grid does not re-render and nothing keyed on it restarts, and **the entry's time is
     refreshed** (the server has just confirmed it). Serialise each answer at most once; keep the string with the
     entry.
  3. **An answer is stored only if no invalidating mutation was in flight when its request began and none started
     since** (below). An answer that fails that test is shown only if the hook has nothing for this key yet;
     otherwise it is dropped, so a revalidation that crossed a Remove or Mark as watched cannot put the old state back
     over the screen's optimistic one. The screen keeps what it shows; the next mount fetches.
  4. A failed background fetch keeps the shown answer (the error is set, as a failed reload does today).
  5. With nothing cached: today's behaviour (`loading: true`, the previous key's data stays), and the answer is
     stored under rule 3.
  6. `reload()` and the refetch after sign-in are unchanged.
- **`cache: 'fresh', maxAgeMs`** (the Player only, `maxAgeMs` 60,000): an answer the server gave less than
  `maxAgeMs` ago is used **without any fetch** (no second request, and no answer arriving mid-start to change the
  film the player latched its resume point and tracks from). Otherwise it loads as without a cache, showing nothing
  older, and stores the answer under rule 3. Film detail → Play within a minute is the case this serves.

### The cache (`state/resourceCache.js`, new; no JSX, no React)

- At most **12** entries, least recently used evicted; each holds the answer, its serialised form and when the
  server gave or last confirmed it. An entry 30 minutes old or older is never shown (dropped when read).
- A **generation** number bumped by every invalidation, and a count of **invalidating mutations in flight**. A request
  notes both when it starts; its answer is stored only if the count was 0 then and the generation is unchanged when it
  lands. (Without the count, a request that began after a slow progress PUT started and landed before it ended would
  be stored and could serve the Player a stale resume point until the PUT's end cleared it.)
- **Invalidation**, from the API client (below), at the **start and again at the end** of every mutating request
  (whether it succeeded or failed), matched on the path without its query string, each pattern anchored at both
  ends and with no alternation:

  | Request | Drops | Generation and in-flight count |
  |---|---|---|
  | `PUT /api/films/{id}/preferences` | `film:{id}` only (the list, Home and the rest do not show preferences) | bumped and counted |
  | `POST /api/editions/{id}/sessions`, `DELETE /api/sessions/{id}`, `PUT /api/playback-health/{id}` | nothing (they change nothing a browse GET returns) | untouched |
  | any other `POST`, `PUT`, `PATCH`, `DELETE` (progress, watched, Continue watching, playback state, sign in and out, every Admin action) | everything | bumped and counted |

  Unknown mutations fall into the last row, so a new endpoint can only cost a cache hit, never show stale data.
- `LIBRARY_CHANGED_EVENT` (a scan or import finished, the player closed) and `SESSION_EXPIRED_EVENT` clear everything
  and bump the generation.
- `clearResourceCache()` is exported (it also resets the in-flight count); `src/test/setup.js` calls it after every
  test, so no test sees another's answers.

### The API client (`api/client.js`)

`onMutation(listener)` registers `listener({ method, path, phase })` (`phase` `'start'` or `'end'`) and returns an
unsubscribe function. `request()` calls the listeners for `POST`, `PUT`, `PATCH`, `DELETE` before sending and in a
`finally` after it settles (an HTTP error, a rejected fetch and an abort included), in fixture mode too. A listener
that throws does not break the request. `getWithEtag` is GET only and calls nothing. No DOM is added to `src/api`.

### Hooks and screens

`useHome`, `useFilms`, `useFilm`, `usePerson`, `useCollections`, `useCollection` pass `cache: 'revalidate'`. A new
`useFilmToPlay(id)` (same key `film:${id}`, `cache: 'fresh'`, 60 s) replaces `useFilm(id)` at `Player.jsx:73`, one
line (phase 2 changes a different line of `Player.jsx`). Nothing else in the screens changes: they already render
data that is present.

Accepted: a change made on **another** device shows only after the background answer lands (a moment at home,
longer on a train). Within this tab nothing stale is ever shown after a change, which is the rule.

The playback health record's start-up time counts from the moment the play route opens
(`playback-health-plan.md`). After C it no longer includes the film's fetch when Play follows Film detail within a
minute; the changelog says so, so readings before and after are compared fairly.

### Interplay with work in flight

- `useContinueWatching` (merged in PR #94) reseeds its row whenever `data` changes identity. Rule 2 keeps the identity when
  nothing changed; rule 3 stops a revalidation that crossed a Remove or Mark as watched from reseeding over the
  optimistic row and its Undo.
- The continue-watching plan's phase C (Library ⋯ menu) needs nothing: its requests go through the client, so they
  invalidate like any other.

### Tests

- `resourceCache.test.js`: the LRU at 12 and 13 entries; the 30-minute edge (29:59.999 shown, 30:00.000 not) on an
  injected clock; a JSON-equal confirmation refreshes the time; storing refused after an invalidation since the
  request began, and refused for a request that began while one was in flight even if it lands before that one ends;
  the invalidation table, row by row, at both phases (a preference save drops only its own film: another film and
  the list stay, and the generation moves); the two events.
- `client.test.js` (or a new file): `onMutation` sees `start` and `end` for each mutating method and nothing for a
  GET or `getWithEtag`; `end` comes after an HTTP error, a rejected fetch and an abort; unsubscribe stops it; a
  throwing listener does not fail the request.
- `useResource` (`renderHook`): `revalidate` shows a cached answer in the first render with `loading: false`, keeps
  `loading` false until the background answer lands, and fetches once; a JSON-equal answer keeps the same reference
  (`toBe`); a different one replaces it; a failed background fetch keeps it; a background answer that crossed an
  invalidation is neither shown nor stored; an entry of 30 minutes is a miss; `fresh` within 60 s makes no request
  (the fetcher is not called) and at 60,000 ms loads with `data: null`; changing an unrelated render does not
  refetch.
- Screens (through `renderApp`, `installFetch` counting requests):
  - **Back from a film shows the Library at once**: Library → a film → back to the Library; the grid is there in the
    first render and no skeleton is (`queryAllByTestId('skeleton')` empty), and one background `GET /api/films` is
    made.
  - **Play reuses the film Film detail loaded**: Film detail → Play; the player starts the session and exactly one
    `GET /api/films/{id}` was made in total.
  - **Never stale after Mark as watched**: Library → a film → Mark as watched → back to the Library; with the next
    `/api/films` held pending, the skeleton shows, not the cached grid; when it lands, the film is watched.
  - **A revalidation that crosses Remove does not undo it** (Home, with `useContinueWatching`): cached Home
    shown, its background `/api/home` held; Remove a Continue watching film; release the held answer with the film
    still in it; the row is unchanged and Undo still works. The same for Mark as watched on Film detail.
  - **A preference change makes Play fetch again**: Film detail → choose another audio track → Play; two
    `GET /api/films/{id}` in total. And a `film:{id}` revalidation that crosses a preference save is not stored.

### Docs

`foundation-plan.md` ("Frontend structure": one sentence on the cache and its invalidation), `test-standards.md`
(the cache is cleared after every test by `setup.js`), `changelog.d/` (with the health-record note above).

## D. The hls.js chunk: `hls.js/light`; the preload left out (section E)

### What `hls.js/light` lacks, against what the engine uses (checked in hls.js 1.7.4)

The light build leaves out alternate audio, subtitles, EME, CMCD, Interstitials, I-frame playlists, variable
substitution, Media Capabilities, and **HEVC and AC-3 in MPEG-TS** (`node_modules/hls.js/README.md`; its dist has no
`AudioStreamController`, `AudioTrackController`, `SubtitleStreamController`, `TimelineController`, `EMEController`,
`CMCDController`, `InterstitialsController`, `IFrameController`). What CineFilm sends and uses:

- Video is H.264 on every rung: the copy rung exists only for 8-bit 4:2:0 H.264 (`SourceFormat.java:28-31`,
  `Ladder.java:116`), the others are encoded to H.264. Audio is AAC stereo on every rung, the copy rung included
  (`FfmpegCommandBuilder.java:215`). Segments are MPEG-TS (`SEGMENT_FLAGS`).
- The master playlist has only `#EXT-X-STREAM-INF` variants (`PlaylistWriter.java:69`): no `#EXT-X-MEDIA` audio or
  subtitle renditions. Subtitles are `<track>` elements over `/api/editions/{id}/subtitles/{n}.vtt`, not hls.js's.
- The engine uses `new Hls(config)`, `Hls.isSupported`, `Events.MANIFEST_PARSED`, `LEVEL_SWITCHED`, `FRAG_LOADED`,
  `ERROR`, `ErrorTypes`, `levels`, `autoLevelCapping`, `loadSource`, `attachMedia`, `startLoad`,
  `recoverMediaError`, `destroy`, and the buffer and load-policy settings (`hlsjsEngine.js`); phase 2 adds
  `Hls.DefaultConfig.loader`, `fLoader`, `shouldRetry`, `errorPenaltyExpireMs`, `loadingEnabled`. All are in the core
  that both builds share (the light dist has `FragmentLoader`, `XhrLoader`, `FetchLoader`).

So **the light build has everything the engine uses** today and in phase 2's plan. HEVC copy, AC-3 passthrough in the
TS segments, or alternate renditions in the master playlist would need the full build again; the import's comment
says so. Size (hls.js's own minified ESM, `gzip -9`): **182,201 → 116,678 bytes** (−65.5 KB); CineFilm's build today
makes a 578,153-byte chunk, 177,730 gzipped. The chunk is also parsed and compiled on every page load that plays, so
the smaller build helps each first play, not only the download.

### The preload: left out

The chunk is fetched only when it is not in the HTTP cache (its name changes only with hls.js), so a preload helps the
first play after an hls.js update. Safari, the owner's main browser, remembers a failed dynamic `import()` until the
page is reloaded (`poor-link-plan.md`), and phase 2 then direct-plays. A preload made with `import()` would move that
one attempt from the moment of Play to while Film detail's artwork downloads, and a blip then would make the next Play
on that page stream the 15 Mbit/s original, even on a link that has recovered. That is worse on a poor link than a
slower first play. The safe form warms the HTTP cache with a plain `fetch` of the chunk's URL, which never touches the
module map, but needs the hashed URL at run time (a Vite manifest or a fixed chunk name): a follow-up for the owner to
decide, not part of this phase.

### Change (after phase 2 has merged, on top of it)

- `streamSource.js`: `import('hls.js/light')`, with a comment naming what the light build lacks and when to switch
  back. Every test file that mocks `'hls.js'` (six today; phase 2 may add more) mocks `'hls.js/light'`.
- Tests: the existing player suites pass with the new mock; a test that the engine is loaded from `hls.js/light`.

## Measurements

- **Before, measured 2026-11-11** (`npm run build` of `9227b64`, `gzip -9`): `hls-*.js` 578,153 / 177,730;
  `index-*.js` 426,965 / 131,227; `index-*.css` 63,923 / 11,322.
- **After each PR:** the bundle from the same command; the API sizes from the real code on a synthetic library of
  1,087 films in the production shape (the Mac has no Postgres, so a throwaway test on H2 that Sonnet runs against
  the old and the new code and does not commit): `/api/films` plain and gzipped, and a 304's size; artwork bytes
  from `ArtworkResizer` on the sample photographs above.
- **Not verifiable here:** the real stored artwork sizes (the command above), production response sizes, and how the
  resized posters look on the owner's screens with his own artwork.

## Gates and process (every PR)

`CLAUDE.md`: Sonnet builds in its own worktree and runs both gates to exit 0; the planner reads the diff; `codex
review --uncommitted` until a run finds nothing new; conventional commit with a `changelog.d/` entry; ready PR;
CI green and Codex 👍 on the exact head; squash-merge with `--match-head-commit`. Mutation tools once per PR at the
end, changed files only, never beside a gate. UI checks in the browser: Library, Home and Film detail at 1440 and 390
with stored artwork (the dev seed has none: say so if it cannot be seen).

## Left alone

- `srcset`/`sizes` for artwork, and a 185 width for search thumbnails: follow-ups if the owner wants them.
- Making resized copies during the import: first-request is what the review asked for, and the import stays as it is.
- `backdropPath` in the Home rows other than the hero and Continue watching, and on Person and Collection pages: small
  lists.
- A service worker or `localStorage` for browse data: the in-memory cache and the HTTP cache with ETags cover Back and
  reload.

## Adversarial review: what changed

1. **`loading` during a background revalidation** was unspecified; reusing today's effect would dim the Library grid
   on every Back. It stays false; only a miss, `reload()` and sign-in set it.
2. **The clock** is `Date.now()`, not `performance.now()` (which stands still while a Mac sleeps and would let the
   Player trust an entry hours old), and a JSON-equal confirmation refreshes an entry's time.
3. **A request that began during a mutation** could be stored stale until the mutation ended; the in-flight count
   closes that window. Both window events bump the generation, and the preference row says it bumps too.
4. **Screen tests for the races** the cache exists to avoid (Remove on Home against PR #94's hook, Mark as watched on
   Film detail, a preference save) were added; the plan had them only at the hook level.
5. **Heap:** 4096 px a side allowed a decode of about 50 MB (more for 16-bit PNG) in a 512 MB JVM. Now at most 4
   megapixels and 8 bits per sample, read from the header before a slot is taken.
6. **Truncated JPEGs** decode with only a warning in the JDK; a warning listener makes them "cannot be resized", which
   is served with a one-day lifetime instead of a year.
7. **CodeQL:** the new file reads and writes may raise `java/path-injection`; the dismissal is planned and logs carry
   no request value.
8. **Rule 11** omitted subtitles (already `no-cache, private`) and is now written for after A; A and B both edit
   `SECURITY.md` and `foundation-plan.md`, so B starts after A merges.
9. **The ETag helper** moved out of `config` (excluded from PIT) into a new `web` package.
10. **Cache cap** 256 → 128 MiB (the host's disk had about 5.8 GB free beside a 5 GB `hls/` cap), with one prune at a
    time (Codex's local review then found that a writer skipped during a prune could leave the folder over the cap,
    that a copy written twice at once was counted twice, and that a failed delete was counted as freed: no writer is
    skipped now, the total is corrected by a delta, and only real deletions count); a copy that is not smaller is not
    served; `MemoryCacheImageOutputStream`; progressive JPEG without a
    fallback branch; the halving count as a pure function; headless AWT for the tests; existing image-URL tests named.
11. **The preload is left out:** on Safari a failed preload would make the next Play direct-play the original even
    after the link recovers. The light build stays.
12. **Search thumbnails** still get far more than they need at 342; a 185 width is a follow-up, outside the widths the
    task set.
