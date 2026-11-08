# CineFilm — foundation plan (phase 1) and roadmap

Written 2026-11-08 from the UX handover in `docs/design/handover/` (the spec is its `README.md`; section numbers
below, e.g. "§4" or "decision 12", refer to it).

## What CineFilm is

A private, single-user movie server for a few hundred self-ripped films, replacing Plex for films. Companion to
CineLight (`~/IdeaProjects/plex-webhooks`), which dims the Hue lights when a film starts. Primary client: Safari on
a MacBook, at home and remote over VPN. Must work on iPhone Safari. Later: Apple TV and a native iPhone app, so the
API and the frontend's data layer must stay separate from the UI.

## Stack (matches goldenhour and CineLight)

| Part | Choice |
|---|---|
| Backend | Java 25, Spring Boot 4.1.2, Maven wrapper (`backend/mvnw`), package `gregochr.cinefilm` |
| Persistence | Spring Data JPA, Flyway migrations, H2 file database (`./data/cinefilm`) for now; PostgreSQL-compatible SQL only |
| Frontend | React 19, Vite, React Router, plain JavaScript (ES modules), plain CSS on `design/tokens.css` (no Tailwind: the tokens *are* the design system) |
| Fonts | Self-hosted via `@fontsource/instrument-serif`, `@fontsource/inter`, `@fontsource/ibm-plex-mono` |
| Tests | Backend: JUnit 5, Spring Boot test slices, MockMvc, AssertJ. Frontend: Vitest + Testing Library + jsdom |
| Media tools | `ffprobe` / `ffmpeg` (phase 2+). Not needed in phase 1 |

Dev: Vite on 5173 proxies `/api` to Spring on 8080. Production packaging (Spring serving the built SPA, Docker) is
phase 5.

## Repository layout

```
backend/        Spring Boot app (own pom.xml + mvnw)
frontend/       React app (own package.json)
docs/design/handover/   the UX handover, read-only reference
docs/engineering/       plans and engineering docs
changelog.d/    one changelog entry file per change
.github/workflows/ci.yml
```

## Domain model (decision 12, §6b)

```
Film 1—n Edition 1—n MediaFile 1—n AudioStream / SubtitleStream
Film n—1 Collection (nullable)          Collection also lists missing parts (CollectionPart)
Film 1—n Credit n—1 Person              Credit: role DIRECTOR | CAST, character, order
Edition 1—1 PlaybackState               resume position (ms), watched, watchedAt, lastPlayedAt
Film 1—1 FilmPreference                 defaultEditionId, lastEditionId, quality, audioStreamId, subtitle choice
```

- **Film:** id, tmdbId, title, sortTitle (leading "The " removed, lower-cased), year, overview, genres (many),
  posterPath, backdropPath, addedAt, collectionId.
- **Edition:** id, filmId, name ("The Final Cut"), year, runtimeMs, note ("No voice-over"), creditsMarkerMs
  (nullable).
- **MediaFile:** id, editionId, path, kind (`ORIGINAL` | `PREMADE_720` | `PREMADE_480`), container, videoCodec,
  width, height, bitrate, sizeBytes, durationMs, spriteReady.
- **AudioStream:** index, language, channels ("5.1"), codec ("DTS"), title ("Commentary — Michael Mann"),
  isDefault, safariPlayable.
- **SubtitleStream:** index, language, forced, title, format.
- **Person:** id, tmdbId, name, profilePath. **Credit:** filmId, personId, role, character, order.
- **Collection:** id, tmdbId, name, posterPath. **CollectionPart:** collectionId, tmdbId, title, year,
  releaseOrder, posterPath (for "Not in your library" tiles; owned parts are matched by tmdbId).

Images are TMDB paths; the frontend builds URLs through one helper (`imageUrl(path, size)`), so a later local image
cache only changes that helper.

## Behaviour rules the backend owns

- **Watched (decision 1):** an edition becomes watched when a progress report reaches `creditsMarkerMs`, or 92% of
  `runtimeMs` when there is no marker. Watched is per edition; a film is "watched" in browse views if any edition is.
- **Resume (decision 1):** the API returns the saved position; the *client* seeks to `max(0, position − 10 s)`.
- **Continue watching:** films whose most recently played edition has a position > 0 and is not watched, newest
  `lastPlayedAt` first. The Home hero is the first of these.
- **Recently added:** `addedAt` within 30 days, newest first. **Not watched in years:** watched, `lastPlayedAt`
  more than 5 years ago (or never played but imported as watched long ago), oldest first.
- **Weekly theme (decision 4):** chosen deterministically from the ISO week (Monday start) and the library
  contents: a decade, a genre or a director with at least 5 films. Same input ⇒ same theme all week.
- **Search (decision 5):** case-insensitive substring on title, cast names and director names.
- **Sort:** `title` (by sortTitle), `year`, `added` (default, newest first).
- **Preferences (decision 2, 12):** quality, audio and subtitle choices and the last edition are remembered per film.

## REST API (phase 1)

All JSON. All routes under `/api`. Times are milliseconds. All routes except `/api/session` require the session cookie (see Auth).

| Method & path | Returns |
|---|---|
| `GET /api/home` | `{ hero: FilmCard?, continueWatching: FilmCard[], recentlyAdded: FilmCard[], notWatchedInYears: FilmCard[], weeklyTheme: { title, caption, kind, value, films: FilmCard[] }, collections: CollectionCard[] }`; `continueWatching` excludes the film shown as `hero` |
| `GET /api/films?q=&sort=title\|year\|added&genre=&decade=1970&collection=<id>&show=all\|unwatched\|watched` | `{ total, films: FilmCard[], facets: { genres: string[], decades: number[], collections: {id,name}[] } }` |
| `GET /api/films/{id}` | `FilmDetail` |
| `GET /api/people/{id}` | `{ id, name, profilePath, films: [{ film: FilmCard, roles: string[] }] }` — owned films only; roles like "as Neil McCauley", "Director" |
| `GET /api/collections/{id}` | `{ id, name, posterPath, parts: [{ owned: true, film: FilmCard } \| { owned: false, title, year, posterPath }] }` in release order |
| `PUT /api/editions/{id}/progress` body `{ positionMs }` | `{ positionMs, watched }` — applies the watched rule |
| `POST /api/editions/{id}/watched` body `{ watched: boolean }` | `{ watched }` (watched=false also resets position to 0) |
| `PUT /api/films/{id}/preferences` body `{ editionId?, quality?, audioStreamId?, subtitle? }` | the stored preferences |
| `GET /api/editions/{id}/stream` | the ORIGINAL MediaFile bytes, honouring `Range` (206 partial content), correct `Content-Type` |
| `GET/POST/DELETE /api/session` | `GET`: 200 `{authenticated:true}` when signed in, 401 `{authenticated:false}` when not; `POST { password }`: 200 and cookie, 401 on a wrong password; `DELETE`: sign out (200) |

**FilmCard:** `{ id, title, year, posterPath, backdropPath, watched, progress: { positionMs, runtimeMs, editionId } | null, director }`

**CollectionCard** (Home): `{ id, name, posterPath, ownedCount, totalCount, posterPaths: (string|null)[] }`, `posterPaths` = up to 3 owned films' poster paths for the fanned posters.

**FilmDetail:** FilmCard fields plus `{ overview, genres, collection: {id,name}|null, directors: Person[], cast: [{ person, character }], editions: [{ id, name, year, runtimeMs, note, creditsMarkerMs (nullable), positionMs, watched, file: { videoCodec, height, audioSummary, addedAt }, audioStreams, subtitleStreams, qualities: [{ id: "auto"\|"original"\|"1080p"\|"720p"\|"480p", bitrate, premade, available }] }], preferences: { editionId, quality, audioStreamId, subtitle } }`

In phase 1 `qualities` lists only `auto` and `original` as available; lower qualities appear with
`available: false` until phase 3.

## Auth (single user)

One password from `CINEFILM_PASSWORD` (dev fallback `changeme`, logged as a warning). Spring Security, form-less:
`POST /api/session` sets an HTTP-only, `SameSite=Lax`, `Secure`-when-HTTPS session cookie with a long idle timeout
(30 days, "remember this Mac"). A 401 from any API makes the frontend show the §5 "signed out while you were away"
card and, after sign-in, return to the same route and playback position. CSRF: the SPA sends a custom header
(`X-CineFilm: 1`) on mutating requests and the backend rejects mutating requests without it.

## Seed data (dev profile only)

A `dev` profile seeds a sample library mirroring the comps (Heat, Arrival, Chinatown, Blade Runner with three
editions, the Alien collection with missing parts, The Wicker Man, Three Colours…) with realistic TMDB poster and
backdrop paths left null (the UI shows the placeholder colour blocks), in-progress positions and watched states so
every Home row has content. MediaFile paths point at `CINEFILM_SAMPLE_VIDEO` if set, so direct play can be tried
with any local .mp4.

## Frontend structure

```
src/api/        fetch client (adds X-CineFilm header, maps 401 → session-expired event), one module per resource
src/state/      hooks over the API (useHome, useFilms, useFilm, usePerson, useCollection, useSession, usePlayback);
                no JSX here — this is what Apple TV / native will reuse
src/player/     player engine logic (keyboard map, auto-hide timer, watched/resume maths, time formatting) as plain
                functions and hooks, separate from player components
src/components/ Poster, FilmRow, CollectionCard, Button, Switch, Segmented, Picker, Skeleton, Header, TabBar…
src/screens/    Home, Library, FilmDetail, Person, Collection, Player, Admin (placeholder in phase 1), SignIn, states
src/styles/     tokens.css (copied verbatim from the handover), base.css, one CSS module per component
```

Routes: `/`, `/library`, `/films/:id`, `/films/:id/play?edition=`, `/people/:id`, `/collections/:id`, `/admin`.

Rules that apply to every component (from the handover): real elements and labels (§7); the focus ring and 1.03
scale (§2) on every focusable card, never a bare `outline: none`; lazy images with reserved aspect ratios; static
skeletons; amber only where §2 allows; ≥ 44px touch targets; phone layout at ≤ 600px (§3 Responsive).

## Phase 1 — this round

Two parallel builds, each in its own worktree and PR. They meet at the API contract above.

**1A — backend** (`backend/`): Maven project, domain + Flyway `V1__schema.sql`, repositories, services for the rules
above, the REST API, range streaming, auth, dev seed, CI job. Tests: unit tests for every rule (watched threshold,
resume list ordering, weekly theme determinism, title sort, search) and MockMvc tests for every endpoint including
401s and the CSRF header. Gate: `./mvnw clean verify` (Checkstyle Google-style-lite, JaCoCo 90% line / 80% branch
bundle).

**1B — frontend** (`frontend/`): Vite app, tokens and fonts, every browse screen in the handover at desktop and
phone widths (Home, Library with live debounced search / sort / filters / no-results, Film detail with editions,
pickers and "Dim the lights" switch, Person, Collection), the §5 states (loading skeletons, empty library, session
expired sign-in card, no subtitles), and the player for direct play (custom controls, scrubber with buffered range
and `role="slider"`, remaining time, keyboard map §4 scoped to the player, 3 s auto-hide with the hairline,
quality menu and Audio & Subtitles panel driven by the API data, buffering spinner, end-of-film overlay at the
credits marker / 92%, progress reporting every 10 s and on pause/exit, resume −10 s, native fullscreen hand-off on
iPhone). Admin is a placeholder page reached from the header settings icon. Tests: Vitest for `src/state`,
`src/player` logic and key screens with fixture JSON. Gates: `npm run lint`, `npm test`, `npm run build`.

The "Dim the lights" switch is stored in the play request (`?dim=0`) and has no effect until phase 4.

## Roadmap after phase 1

- **Phase 2 — real library.** Folder scanner with `ffprobe` (streams, codecs, durations), TMDB matching and
  metadata (films, people, collections, images with a local cache), multiple files for one TMDB id flagged for
  "Group as editions", Admin: Library scan panel, Films needing attention, Fix match (TMDB search with runtime).
- **Phase 3 — transcoding.** Separate transcode worker (heartbeat, sessions), HLS output for 1080p/720p/480p, audio-only
  transcode for Safari-unplayable tracks, Auto with step-down / step-up (decision 3) and toasts, the network
  indicator and remote/slow detection, worker-offline behaviour, pre-made versions with the automatic queue
  (decision 15), scrubber sprite sheets at scan time (decision 14). Admin: worker and pre-made panels.
- **Phase 4 — Plex import and CineLight.** Import films, posters and watch history from Plex; Admin import panel.
  Tell CineLight about play / pause / stop with the `dim` flag (decision 8) — the contract is agreed with the
  CineLight repo.
- **Phase 5 — ship.** Spring serves the SPA, Docker image, deploy target, backups, HTTPS behind the VPN, the undrawn
  mobile screens polished, Apple TV focus-navigation groundwork.

## Open questions for the owner

1. Where do the films live (NAS path / mount), and where will CineFilm run (same Mac mini / NAS / a server)? Needed
   for phase 2 and 5.
2. Does the transcode worker run on the same machine as the server or a separate, more powerful one?
3. TMDB API key: the owner supplies it as `TMDB_API_KEY` (phase 2).

### API details settled in phase 1A

- Streams: `audioStreams[]` = `{ id, index, language, channels, codec, title, isDefault, safariPlayable }`,
  `subtitleStreams[]` = `{ id, index, language, forced, title, format }`; `id` is the database id.
- `preferences` = `{ editionId, quality, audioStreamId, subtitle }`: `audioStreamId` is an audio stream `id`;
  `subtitle` is `"off"` or a subtitle stream id as a string; `quality` is one of `auto|original|1080p|720p|480p`.
  `PUT /api/films/{id}/preferences` changes only the fields sent (400 for an unknown edition or quality).
- `directors` entries and cast `person` are `{ id, name, profilePath }`; Person `films[].roles` are display strings
  ("as Neil McCauley", "Director").
- `GET /api/editions/{id}/stream` takes only the id; query parameters such as `start` and `dim` are ignored.
  `addedAt` fields are ISO-8601 instants.
- Rewatch: when an edition becomes watched (threshold reached, or `POST .../watched` with true) its saved position
  is reset to 0 and `watched` stays true; progress reports at or past the threshold keep it at 0. Continue watching
  is every film whose most recently played edition has `positionMs > 0`, watched or not, so a watched film you
  restart is in progress again. "Not watched in years" is unchanged. `continueWatching` omits the `hero` film.
- Errors are JSON `{ "error": "..." }`: 400 invalid input, 401 signed out, 403 missing `X-CineFilm: 1` on a
  mutating request, 404 unknown id.
