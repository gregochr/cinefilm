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
| Persistence | Spring Data JPA, Flyway migrations (Flyway owns the schema). PostgreSQL 17 in production and dev, H2 in-memory for the fast test suite only; migrations target PostgreSQL |
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
Edition 1—1 PlaybackState               resume position (ms), watched, watchedAt, lastPlayedAt, hiddenFromContinueWatching, hiddenAt
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
- **Continue watching** (see `continue-watching-row-plan.md`): films **in progress**, meaning the most recently played
  edition has a position at or above the minimum watch time (`continueWatchingMinMinutes`, default 5, set in Admin;
  always above 0), newest `lastPlayedAt` first, **except** films whose most recently played edition has `hiddenFromContinueWatching` set
  ("Remove from Continue watching": only the flag and `hiddenAt` change, the position stays so "Resume from…" still
  works; any progress report clears the flag, so playing the film again brings it back). The Home hero is the first
  of these, so a hidden film leaves the hero and the row together.
- **Recently added:** `addedAt` within 30 days, newest first. **Not watched in years:** watched, `lastPlayedAt`
  more than 5 years ago (or never played but imported as watched long ago), oldest first.
- **Weekly theme (decision 4):** chosen deterministically from the ISO week (Monday start) and the library
  contents: a decade, a genre or a director with at least 5 films. Same input ⇒ same theme all week. In Halloween
  week (Monday on or before 24 Oct) and Christmas week (Monday on or before 18 Dec), Monday to Sunday, the theme is
  `kind: "holiday"`, `value: "halloween"` (genre Horror) or `"christmas"` (TMDB keyword `christmas`, overridden by
  the owner's rows in the `christmas_override` table, by TMDB id; exclude beats include beats the keyword), with the
  usual oldest-first 12; fewer than 5 matching films falls back to the normal rotation.
- **Search (decision 5):** word-prefix on title, cast names, director names and the collection name: every query word must start a different word of one name, ignoring case, accents, apostrophes and punctuation (`SearchText`, shared test vectors in `docs/design/search/vectors.json`); no typo matching on the server.
- **Sort:** `title` (by sortTitle), `year`, `added` (default, newest first).
- **Preferences (decision 2, 12):** quality, audio and subtitle choices and the last edition are remembered per film.

## REST API (phase 1)

All JSON. All routes under `/api`. Times are milliseconds. All routes except `/api/session` require the session cookie (see Auth).

| Method & path | Returns |
|---|---|
| `GET /api/home` | `{ hero: FilmCard?, continueWatching: FilmCard[], continueWatchingTotal: number, recentlyAdded: FilmCard[], notWatchedInYears: FilmCard[], weeklyTheme: { title, caption, kind, value, films: FilmCard[] } }`; `hero` is the newest film in progress, `continueWatching` is **at most the next 12** after it (the client shows 4 and keeps the rest to backfill), and `continueWatchingTotal` counts every film in Continue watching, the hero included and films removed from Continue watching left out |
| `GET /api/films?q=&sort=title\|year\|added\|imdb\|rt\|last-watched&genre=&decade=1970&show=all\|in-progress\|unwatched\|watched` | `{ total, films: LibraryCard[], facets: { genres: string[], decades: number[] } }`; each `LibraryCard` is a `FilmCard` without `backdropPath` (the Library never shows it), and `collectionId` is set only here. `show` is exclusive: `in-progress` is `FilmCard.inProgress` (films removed from Continue watching included), `watched` is any edition watched and not in progress, `unwatched` is the rest. `sort=last-watched` is newest `lastWatchedAt` first, never-played films last, ties by title |
| `GET /api/films/{id}` | `FilmDetail` |
| `GET /api/people/{id}` | `{ id, name, profilePath, films: [{ film: FilmCard, roles: string[] }] }` — owned films only; roles like "as Neil McCauley", "Director" |
| `GET /api/collections` | `{ filmCount, collections: CollectionCard[] }`: `filmCount` is the number of browsable films (the Films tab count); `collections` holds every collection with at least two owned films, sorted by name |
| `GET /api/collections/{id}` | `{ id, name, posterPath, parts: [{ owned: true, film: FilmCard } \| { owned: false, title, year, posterPath }] }` in release order |
| `GET /api/search-index` | The compact header-search index, one request for the whole library: `{ films: [{ id, t, y, d[], p, left?, at?, s }], collections: [{ id, n, o, of, p[≤2] }], people: [{ id, n, a, d }] }` (`t` title, `d` director names, `p` poster, `left` ms remaining when part-watched, `at` last played, `s` sort title; `o`/`of` owned/total; `a`/`d` browsable films as actor/director). Lists are in id order; weak `ETag` (`W/"…"`, so Tomcat still compresses it; first 16 hex of the SHA-256 of the body), `Cache-Control: no-cache, private`, `304` on a matching `If-None-Match`; see `docs/engineering/search-plan.md` |
| `PUT /api/editions/{id}/progress` body `{ positionMs }` | `{ positionMs, watched }` — applies the watched rule |
| `POST /api/editions/{id}/watched` body `{ watched: boolean }` | `{ watched, previous: PlaybackSnapshot }` (watched=false also resets position to 0); `previous` is the state just before the change (an absent row is `{0, false, null, false, null}`) so the client can undo exactly |
| `PUT /api/editions/{id}/continue-watching` body `{ hidden: boolean }` | `{ hidden }`: true sets the hidden flag and `hiddenAt`, false clears both; never touches position, watched or `lastPlayedAt`. 404 unknown edition, 400 missing flag. An edition with no playback state yet has nothing to hide: no row is created and the answer is `{ hidden: false }` |
| `PUT /api/editions/{id}/playback-state` body `PlaybackSnapshot` | the stored `PlaybackSnapshot`; restores exactly `positionMs`, `watched`, `watchedAt`, `hiddenFromContinueWatching`, `hiddenAt` (never `lastPlayedAt`). 400 for `positionMs` < 0, 404 unknown edition. Used by Undo |
| `PUT /api/films/{id}/preferences` body `{ editionId?, quality?, audioStreamId?, subtitle? }` | the stored preferences `{ editionId, quality, audioStreamId, subtitle }`. `subtitle` is free text saved as sent: `"off"`, `"full"` (the edition's full English subtitle, phase 5c) or a stream id as text. It is `null` until someone chooses, and a scan that removes the chosen stream resets it to null |
| `GET /api/editions/{id}/stream` | the ORIGINAL MediaFile bytes, honouring `Range` (206 partial content), correct `Content-Type`; 409 `{ "error": "This edition is split across several files and cannot be direct-played" }` for an edition made of several files (it plays only as one adaptive session) |
| `GET /api/editions/{id}/still?atMs=` | `image/jpeg` (at most 1280 wide) of the frame at edition time `atMs`, floored to whole seconds and mapped to the right part; `Cache-Control: private, max-age=86400`. 400 missing or out-of-range `atMs`, 404 unknown edition, no original file, part not playable or file missing, 503 when ffmpeg is busy (2 at once), times out (10 s) or fails. Plan: `resume-still-plan.md` |
| `GET /api/editions/{id}/subtitles/{streamIndex}.vtt` | WebVTT (`text/vtt;charset=UTF-8`) of one of the edition's subtitle streams on the edition's own timeline; `streamIndex` is part 1's stream index (the logical stream). Each part's matching stream (same language and forced flag, else the same rank) is extracted like the media-file route, later parts' cues are shifted by the part's start, and the parts are joined; a film in one piece is the file's extraction as it is. 404 for an unknown edition or stream, a missing part file or a failed extraction |
| `GET /api/media-files/{id}/subtitles/{streamIndex}.vtt` | WebVTT (`text/vtt;charset=UTF-8`) of an embedded text subtitle stream of the media file (`file.id` on an edition), extracted with `ffmpeg` on first request and cached under `CINEFILM_CACHE_DIR`; 404 for an unknown file or stream, a file that is gone, or a failed extraction |
| `GET /api/editions/{id}/forced-subtitles.vtt` | WebVTT (`text/vtt;charset=UTF-8`, `Cache-Control: no-cache, private`) of the edition's forced English subtitle on the edition's timeline, the owner's offset applied and cue text escaped (`&`, `<`, `>`). `?v=` is the `forcedSubtitle.version` (a number: a stable hash of which subtitle rows are served, their offsets and last changes, so a switch between an owner's file and a download changes it) and only busts the browser cache: the server ignores it. Which subtitle plays (`ForcedChoice`, owner decision "trust only proven files", `forced-subtitles-plan.md` decisions 41 to 57): a proven owner file; else a proven download (an automatic check agrees, or the owner said "Looks right"); else the film's own embedded forced text track (the player picks it; nothing is served from here and `forcedSubtitle` is null); else an owner file nothing says is wrong; else, for an owner file that failed its checks, a download that has not failed (a failed file with no replacement keeps playing, unless it has 300 lines or more: it is full subtitles and plays nothing); else, with no owner file, an unchecked download. Within the owner's files one whole-edition sidecar beats per-part sidecars (stitched by each part's start); only present sidecars with at least one cue count; two files for one place are a conflict and that place plays nothing. `forcedSubtitle.source` is `SIDECAR` for the owner's files and `DOWNLOAD` for a download from OpenSubtitles. 404 `"No forced subtitles"` when none plays, `"No such edition"` for an unknown edition, `"Forced subtitles are not available"` for per-part files of a split edition whose parts have no durations |
| `GET /api/editions/{id}/full-subtitles.vtt` | WebVTT (`text/vtt;charset=UTF-8`, `Cache-Control: no-cache, private`) of the edition's full English subtitle (phase 5b: the owner's `<stem>.en.srt` files, kind `FULL`), on the edition's timeline with the owner's offset applied and cue text escaped, by the same code as the forced route (`ServedSubtitles.vtt`). `?v=` is `fullSubtitle.version` (a number, built like `forcedSubtitle.version`: a stable hash of which rows are served, their offsets and last changes) and only busts the browser cache. Which files play (`SidecarPrecedence`, over the `FULL` rows only): one whole-edition file beats per-part files (stitched by each part's start); only present files with at least one cue count; two files for one place (say `X.en.srt` and `X.eng.srt`) are a conflict and neither plays. The owner's files serve first; with none, a live full download from OpenSubtitles serves (`fullSubtitle.source` `DOWNLOAD`, played unchecked: decision 66; phase 5d-1, a `subtitle_sidecar` row of kind `FULL` and origin `OPENSUBTITLES`, the owner's offset applies to it). A conflict between the owner's files leaves nothing playing. A file beside an English-language film serves too. 404 `"No full subtitles"` when none plays, `"No such edition"` for an unknown edition, `"Full subtitles are not available"` for per-part files of a split edition whose parts have no durations. Forced and full rows share one table, but no forced path reads a `FULL` row (`FullSidecarIsolationTest`) |
| `GET /api/admin/subtitles` | `SidecarDto[]` in path order, one per `.srt` file found beside the films (forced, or full English: `kind`) and one per subtitle downloaded from OpenSubtitles: `{ id, path, presence: "PRESENT"\|"MISSING", state: "matched"\|"unmatched"\|"conflict", filmId, filmTitle, editionId, editionName, parts, part (1-based, null for a whole-edition file or unmatched), whole, ownerAttached, cueCount, parseReport, offsetMs, verified, endsAfterRuntime, version (epoch ms of the last change), served, check, offsetSource, alignment, origin: "FILE"\|"OPENSUBTITLES", release (a download's release name, else null), openSubtitlesFileId (a download's file id, else null), servedReason, replacedByDownload, kind: "FORCED"\|"FULL" }` (film and edition fields are null/0 when unmatched). Every Admin action takes a row `id`, never a path `served` is true when playback serves this subtitle for its edition (false for an unmatched, empty, missing or conflicting file, one a whole-edition file beats, one that loses to a proven download or the embedded track, or a download that failed or plays second). `servedReason` is one short sentence from the same precedence function that says why it plays or not ("Plays: it agrees with OpenSubtitles", "Replaced by an OpenSubtitles download that agrees", "Not used: the film's own forced subtitle track plays instead", "Plays only because nothing better exists: it failed its checks (needs checking)"). A `FULL` row (phase 5b) is listed beside the forced ones with `kind: "FULL"`: it can be attached, detached and nudged like a forced file, but "Looks right" (`verify`, `DELETE verify`) refuses it (400 `"Only a forced subtitle can be checked"`), it has no `check`, `alignment` or `replacedByDownload`, and `served` means it is found and served (the player menu comes in phase 5c) (`servedReason` "Ready to play as English (full)", or why not). It never takes part in a forced row's `conflict`, `served` or `servedReason`. `replacedByDownload` is true when playback serves a download in place of this owner file because the file failed its checks (decided by `ForcedChoice`; the UI then offers "Use my file (looks right)"). A download is listed with `origin` `OPENSUBTITLES`, a made-up `path` (`opensubtitles:<editionId>:<fileId>`), `whole` true and no `part`; attach and detach refuse it (400). The Admin "Unchecked" state is `served` with a null `check`. `check` is the comparison with OpenSubtitles' file, or null (not compared yet, or what was compared changed since): for an owner's file it is the edition's comparison of its own files, which stays on record while a download plays instead; for a download it is the second opinion against a different OpenSubtitles file, asked for only when its timing fit could not prove it: `{ verdict: "AGREES"\|"CHECK", reasons: string[] (why, and a timing hint such as "OpenSubtitles' file is 1.2 s later", never applied), bestOffsetMs, matchedShare (0 to 1), servedCount, referenceCount, referenceRelease, checkedAt }`. `offsetSource` says who set `offsetMs`: null (never set, it is 0), `"OWNER"` (the owner's `PUT .../offset`, a reset to 0 included: the timing check never changes it again) or `"AUTO"` (the timing check set it; the UI can say "Timing set automatically"). `alignment` is the fit of the file's cue times to the full English subtitle track inside the film's own media file (phase 3a), or null (not a candidate for its edition, not checked yet, or the file or the film file changed since; a download is fitted too): `{ verdict: "ALIGNED"\|"CHECK"\|"TOO_FEW"\|"NO_REFERENCE" (the last two show as Unchecked), reasons: string[] (plain sentences: empty for an `EMBEDDED` `ALIGNED`; an `OPENSUBTITLES` fit holds one sentence, naming the OpenSubtitles file except for "Looks like full subtitles, not forced" and `TOO_FEW`, which name none), fittedOffsetMs (the absolute offset that lines the file up; what was set when applied), matchedShare (0 to 1), matchedCount, servedCount, applied (derived: true while `offsetSource` is `AUTO` and `offsetMs` equals `fittedOffsetMs`, so an owner's nudge or reset clears it; always false for `OPENSUBTITLES`), checkedAt, source: "EMBEDDED"\|"OPENSUBTITLES", referenceRelease (the OpenSubtitles file's release name for `OPENSUBTITLES`, else null) }`. `source` is `EMBEDDED` for a fit to the film's own track (phase 3a) and `OPENSUBTITLES` for a fit to a stored full English file when the film has no English track of its own (phase 3c-2a: the best of the usable stored files, never the file's own OpenSubtitles file; a film the owner marked `NONE_EXIST` never gets one): that fit is shown but never counts as proof (it never makes a file `PROVEN`, never beats the embedded forced track, never overrides a `check` of `CHECK`) and never sets an offset; its `ALIGNED` needs an offset of 1 s or less, and a file of 300 lines or more with more than half the reference's lines is `CHECK` ("Looks like full subtitles, not forced"), except against a reference of under 600 lines (which may itself be a foreign-parts file), where it must have more than twice the reference's lines; a `CHECK` whose only reason is a loose median (80% or more matched, offset within 10 s) says "Lines match a full English file from OpenSubtitles (<release>) but only loosely (typically <n> ms off): one of them may be timed slightly differently". No new route: both checks are made in the background (login, download and quota: `docs/engineering/forced-subtitles-plan.md` "2d"; the timing check: "Phase 3a build") |
| `GET /api/admin/subtitles/{id}/cues` | `{ offsetMs, cues: [{ startMs, endMs, text }] }` on the edition's timeline with the offset applied (never below 0); 404 `"No such subtitle"` |
| `POST /api/admin/subtitles/{id}/attach` body `{ editionId, part }` | attaches by hand (`part` null = the whole edition, stored against part 1 with `whole` on a split edition, file-relative on a single file; a number = that part's file); sets `ownerAttached` (a scan never re-matches it) and clears the owner's check; answers the `SidecarDto`. 400 `"Pick an edition"` / `"That edition has no film file"` / `"That edition has no part N"`; 404 `"No such edition"` or `"No such subtitle"` |
| `POST /api/admin/subtitles/{id}/detach` | gives the file back to matching by name (clears `ownerAttached`, matches at once); answers the `SidecarDto` |
| `PUT /api/admin/subtitles/{id}/offset` body `{ offsetMs }` | sets the timing offset, within ±600,000 ms; keeps the owner's check (owner decision 2026-11-16; it moves `version`); answers the `SidecarDto`. 400 `"An offset is required"` / `"The offset must be within 10 minutes either way"` |
| `POST /api/admin/subtitles/{id}/verify`, `DELETE /api/admin/subtitles/{id}/verify` | "Looks right" and its undo. `verified` is true only while the edition has the same part files as when the owner checked (`verified_parts`). 400 `"Attach the subtitle to a film first"` for an unmatched file; both answer the `SidecarDto` |
| `DELETE /api/admin/subtitles/{id}` | removes a subtitle downloaded from OpenSubtitles (never an owner's file): 204 with no body. The row, its timing fit and its comparison go; that OpenSubtitles file is blocked for the edition (and IMDb id) for good (`forced_download_block`), so no later run offers it again, and the download run looks for the next candidate. "Use my file" is the existing `POST .../verify` on the owner's file (a verified file is proven and plays first). Runs under the library lock like the other Admin changes: 409 with the lock holder's text (see the download row below: "A scan is running" for a scan), 404 `"No such subtitle"`, 400 `"Only a subtitle downloaded from OpenSubtitles can be removed"` for a file beside the film |
| `GET /api/admin/forced-subtitles/films` | `{ films: FilmNeedDto[], counts }`, every film by sort title (built from three queries plus one bounded fetch of the evidence films' editions and playback's own sidecar query, never one per film). `FilmNeedDto` is `{ filmId, title, year, need: "NEEDED"\|"NOT_NEEDED"\|"UNSURE"\|"UNKNOWN", reason: "OWNER"\|"SIDECAR"\|"EMBEDDED_FORCED"\|"OPENSUBTITLES"\|"NO_LANGUAGES"\|"FOREIGN_LANGUAGE_FILM"\|"NO_FORCED_ONLINE"\|"ENGLISH_ONLY"\|"OTHER_LANGUAGES"\|"NO_SPOKEN_LANGUAGES", ownerChoice: "NEEDED"\|"NOT_NEEDED"\|null, originalLanguage (TMDB code or null), spokenLanguages: string[] (TMDB codes, `[]` when none or unknown), hasForcedSubtitle (at least one edition has a forced subtitle playback would show: the same `ForcedChoice` as the player drops empty files, suppresses conflicts, plays no failed full-subtitles file and no failed download, and counts a download and the embedded forced track; films with a download are evaluated too), openSubtitlesForcedCount (integer or null: how many English foreign-parts-only subtitles OpenSubtitles had when last searched for the film's current IMDb id; null = never searched, or the IMDb id has changed since; 0 = searched, none found), openSubtitlesSearchedAt (ISO instant or null, null with the count), unresolved (boolean), beingChecked (boolean), editionId (the edition the Admin picker searches OpenSubtitles for: the film's first present edition, lowest id with part 1 present, one more query for the films that are `NEEDED` with no forced subtitle served; null for every other row and for a film with no present edition), editionName (that edition's name when the film has more than one present edition, else null) }`; `unresolved` and `beingChecked` come from `AdminTodoRules` (Admin To do rules, below) and are both false unless `need` is `NEEDED` and `hasForcedSubtitle` is false, in which case exactly one is true; `counts` is `{ needed, neededMissing (needed with no forced subtitle playback would serve: `hasForcedSubtitle` false), unsure, unknown, notNeeded, unresolved (rows flagged), beingChecked (rows flagged) }`, and `unresolved + beingChecked` equals `neededMissing`. The need is worked out when read (`ForcedNeed.decide`, first match wins: owner, attached present sidecar with cues on a present file, embedded forced English or language-less track, OpenSubtitles (a count above 0 from a search of the film's current IMDb id, `film.os_forced_count`, V15), then TMDB languages: a non-English original is `NOT_NEEDED` (`FOREIGN_LANGUAGE_FILM`), English only is `NOT_NEEDED`, and otherwise a search that found 0 is `NOT_NEEDED` (`NO_FORCED_ONLINE`, decision 69) while a film not yet searched stays `UNSURE`) and is never stored. See `forced-subtitles-plan.md` "Phase 2" |
| `PUT /api/admin/films/{id}/forced-need` body `{ choice: "NEEDED"\|"NOT_NEEDED"\|null }` | stores the owner's choice (null = automatic, `film.forced_need_owner`, V14) and answers that film's `FilmNeedDto`. Runs under the library lock like the other Admin changes: 409 with the lock holder's text (see the download row below: "A scan is running" for a scan), 404 `"No such film"`, 400 `"The choice must be NEEDED, NOT_NEEDED or null"` for any other value |
| `GET /api/admin/subtitles/full-need` | `{ films: FullNeedFilmDto[], counts }`: the films in scope (TMDB's original language is not English, `xx` or missing, or the owner has chosen), by sort title, built from a fixed number of queries (the rows, part 1's audio tracks of every present edition of those films, those films' editions with their files, their full subtitle rows), never one per film (plus one read of every edition with its files, left-joined so an edition with no file row is seen, for the browse rule). `FullNeedFilmDto` is `{ filmId, title, year, need: "NEEDED"\|"NOT_NEEDED"\|"UNSURE"\|"UNKNOWN", reason: "OWNER"\|"NONE_EXIST"\|"NO_LANGUAGES"\|"ENGLISH_FILM"\|"FOREIGN_AUDIO"\|"ENGLISH_AUDIO"\|"ORIGINAL_AUDIO_ASSUMED", ownerChoice: "NEEDED"\|"NOT_NEEDED"\|"NONE_EXIST"\|null, editions: [{ name, audio }], fullSubtitle: { source: "SIDECAR"\|"DOWNLOAD" }\|null, originalLanguage (TMDB code or null), unresolved (boolean), beingChecked (boolean), subtitled (boolean), editionId (the edition the Admin picker searches OpenSubtitles for: the first present edition whose audio label is not English, else the first with no label, else the first, by edition id, `FullNeed.pickerIndex`; null when `editions` is empty), editionName (that edition's name when `editions` has more than one entry, else null) }`, one entry per edition whose part 1 is present; `fullSubtitle` is what the film plays as full English subtitles on some edition (`FullChoice`, as the film page's `fullSubtitle`; the owner's file when any edition plays one, else a download), null when none plays; `audio` is the language label of part 1's default audio track (else its first), as stored, null when unlabelled or there is no audio. `unresolved` and `beingChecked` come from `AdminTodoRules` (Admin To do rules, below): both false unless `need` is `NEEDED` and `fullSubtitle` is null, in which case exactly one is true. A film the owner marked `NONE_EXIST` is `NEEDED` too, but both are false for it. `counts.needed` includes `NONE_EXIST` films. `counts` is `{ needed, unsure, unknown, notNeeded, unresolved (rows flagged), beingChecked (rows flagged), subtitled (rows flagged), inEnglish }`; `subtitled` on a row is true when the film is **browsable** (`Film.browsable(editions)`: not every edition missing or unplayable; a film with no edition shows), its need is `NEEDED` and a full subtitle plays (`AdminTodoRules.subtitledFull`), and `counts.subtitled` is the rows flagged, so a button's count equals the rows it opens; `inEnglish` is the browsable films, across the whole library and not only the listed ones, whose full need is `NOT_NEEDED` (English or `xx` or missing originals, `ENGLISH_AUDIO` dubs, the owner's "not needed"); a film TMDB has no language for (`UNKNOWN`) is not counted as English. The need is worked out when read (`FullNeed.decide`, first match wins: owner, no TMDB languages, an English, `xx` or missing original language, then the audio: any edition labelled non-English is `NEEDED`, every edition labelled English is `NOT_NEEDED`, otherwise (unlabelled audio, or no present edition) `NEEDED` with `ORIGINAL_AUDIO_ASSUMED`, decision 70; `UNSURE` is no longer produced automatically) and is never stored. See `forced-subtitles-plan.md` "Phase 5" |
| `PUT /api/admin/films/{id}/full-need` body `{ choice: "NEEDED"\|"NOT_NEEDED"\|"NONE_EXIST"\|null }` | stores the owner's choice (null = automatic, `film.full_need_owner`, V21, `NONE_EXIST` V31: the film is `NEEDED` with reason `NONE_EXIST`, the owner saying no English subtitles exist; it is never `unresolved` or `beingChecked`, and the automatic OpenSubtitles search and download skip it, while the picker and "Add my own file" still work; a subtitle that plays for it makes it `subtitled`) and answers that film's `FullNeedFilmDto`. Runs under the library lock like the other Admin changes: 409 with the lock holder's text (see the download row below: "A scan is running" for a scan), 404 `"No such film"`, 400 `"The choice must be NEEDED, NOT_NEEDED, NONE_EXIST or null"` for any other value |
| `GET /api/admin/editions/{id}/opensubtitles?kind=full\|forced` | Searches OpenSubtitles live (a search is not charged) for the edition's film and answers what the picker lists: `{ editionId, kind: "full"\|"forced", imdbId, downloadsLeft, results: [{ fileId, release, fileName, fps, downloadCount, fromTrusted, hearingImpaired, uploadDate }] }`. `forced` searches English foreign-parts-only files, `full` full English ones (hearing-impaired files included). Only single-file results are listed (a subtitle split over discs is part of a film and is never downloaded); they are ranked for the edition's name by `OpenSubtitlesRanking.rankFor` (forced) or `rankForFull` (full): the best fit to the edition's cut tags first, then the film-level order. `fileName`, `fps` and `uploadDate` may be null. `downloadsLeft` is `DownloadPlan.MAX_DOWNLOADS` (3) less the downloads and removed files the edition already has of that kind for the film's IMDb id. Each search is remembered in memory for 15 minutes under the edition and kind (a new search replaces it), and only those `fileId`s can be downloaded. No library lock and no CSRF header (a GET). Errors: 400 `"The kind must be full or forced"` (missing, or anything but the lowercase `full` / `forced`), 400 `"This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first."` (also for an id the client rejects), 404 `"No such edition"`, 409 `"This edition has no original file to attach subtitles to."` (an edition with only premade renditions: nothing is searched), 503 `"OpenSubtitles is not set up"` (no API key), 503 `"OpenSubtitles is not answering. Try again in a few minutes."` (an outage: `OpenSubtitlesUnavailableException`), 401 signed out |
| `POST /api/admin/editions/{id}/opensubtitles/download` body `{ fileId, kind: "full"\|"forced" }` | Downloads one file the owner picked and makes it the edition's download: `{ editionId, kind, fileId, release, downloadsLeft }` (`downloadsLeft` as in the search). It accepts only a `fileId` that a search this server ran for **the same edition and the same kind** returned **at most 15 minutes ago** (inclusive: a file asked for exactly 15 minutes after its search is still accepted, one millisecond later is not); anything else (another edition's or the other kind's file, an expired search, a file never offered) is 400 `"That file was not in a recent search for this edition. Search again."`. It uses the nightly download path: `ReferenceFetcher` (a file already stored for the film is never fetched again; the day's budget, `DailyDownloadLimit`, `DownloadShares.forMissing` = the whole limit), then `ForcedDownloadService.addChosen` under the library lock, in one transaction, which re-checks the cap (`MAX_DOWNLOADS`, removed files included), the owner's removals (`forced_download_block`), a download already made of that file, the film's IMDb id and the owner's "not needed" choice. The film's stored candidates and its need are not consulted: the owner chose. **Pinned:** the new row carries `owner_chosen_at` (migration V28), a marker of its own that is not the owner's check: `ownerVerifiedAt` and `verifiedParts` stay null, so Admin and the film detail never say "Looks right" or "checked" for it (`verified` false, `servedReason` `"Plays: you picked it"`), and the timing check still sets its offset. `SidecarTrust.assess` makes a chosen row `PROVEN` (`Proof.CHOSEN`) whatever a comparison says, so it is never judged failed and the nightly plan, finding a live download, adds none; a nudge, a reset, an undo or a change of the edition's parts does not clear it. What a pick beats depends on the kind. **Forced:** it plays before the edition's other downloads (a chosen one first; otherwise the order is unchanged: proven before unchecked, then the lowest id), before the embedded forced track and before an owner's file that is unproven or failed; an owner's proven file still plays first. **Full:** the owner's full file (or a conflict between owner files) always wins, as for any full download; a pick only plays before the edition's other full downloads (the chosen one first, otherwise the lowest id). A later pick of the same edition and kind takes the marker from the earlier one (only a download made for that edition; one made for another edition keeps its own). The file also goes on the edition's comparison ledger, and `SubtitleDownloadsFinished` is published so its timing is fitted. Two POSTs for one edition and kind cannot overlap (the second is 409 while the first runs; after it, the "already downloaded" and cap checks stop a repeat), so neither the cap nor the budget can be exceeded by a double click. Errors: 400 `"The kind must be full or forced"`, 400 `"A file id is required"` (missing, 0 or negative), 400 `"Invalid request"` (no or unreadable body), 404 `"No such edition"`, 409 `"A scan is running"` (the library lock is held; every 409 for the lock names the holder through `LibraryLock.busyText()`: "A scan is running" for a scan, "A Plex import is running", or "Subtitles are being changed or checked; try again in a moment" and the like for the others, "The library is busy; try again in a moment" when the holder let go meanwhile: checked before a download is spent, and again when it is added), 409 `"This edition has no original file to attach subtitles to."` (only premade renditions: checked before any download is spent, and again under the library lock), 409 `"A download for this edition is already running"`, 409 `"This film or edition changed while you were choosing. Search again."`, 409 `"You marked this film as not needing these subtitles. Change that first."`, 409 `"You removed that file from this edition earlier, so it is never downloaded for it again."`, 409 `"That file is already this edition's download."`, 409 `"This edition has already used its 3 OpenSubtitles files (removed ones count), so no more can be downloaded for it."`, **422** `"That subtitle file could not be read."` / `"That file is a full subtitle, not a forced one."` / `"That file is too short to be a full subtitle."` (the file was fetched, which may have used one of today's downloads (a stored copy is reused free), but it cannot be a download of this kind; also the answer when OpenSubtitles charged for a link whose file can never be fetched), **429** `"OpenSubtitles daily limit reached — try again tomorrow."` (`OpenSubtitlesQuotaException`, or CineFilm's own day budget spent), **502** `"OpenSubtitles counted this download against today's limit but could not deliver the file. Try again."` (the retryable `OpenSubtitlesChargedException`: OpenSubtitles issued its link, so the allowance is spent and nothing is refunded; a charged failure that can never succeed is the 422 above), **503** `"OpenSubtitles is not answering. Try again in a few minutes."` (`OpenSubtitlesUnavailableException`: nothing was charged and the budget is given back), 503 `"OpenSubtitles downloads are not set up"` (no API key, username or password), 401 signed out, 403 `"Missing X-CineFilm header"` |
| `GET /api/admin/scan` | `ScanStatus`: the Library scan panel, polled while a scan runs. `counts` is `{ films, newFiles, missing, protectedFiles, needsConversion, needsMatch, unreadable, filmsWithCredits }`; `films` is the browsable films, and `filmsWithCredits` is how many of those have at least one credit (cast or director; a film hidden by the browse rule is in neither), so Admin's Cast & crew card reads "{filmsWithCredits} of {films} films have credits" (admin redesign phase 6) |
| `POST /api/admin/scan` | starts a scan: 202 and the `ScanStatus`; 409 `{ "error": "A scan is already running" }` when one runs |
| `POST /api/admin/ratings/refresh` | starts a forced ratings refresh (every film, oldest first, within the daily OMDb budget): 202 and the `ScanStatus`; 409 `"A ratings refresh is already running"`; 404 `"Ratings need an OMDb API key"` when `OMDB_API_KEY` is blank. `ScanStatus` also carries `ratingsLastRefreshedAt` (ISO time or null), `ratingsRunning`, `ratingsEnabled`, and `openSubtitlesLastSearchedAt` (ISO time or null), `openSubtitlesRunning`, `openSubtitlesEnabled` (an `OPENSUBTITLES_API_KEY` is set) for the forced-subtitle search, and `subtitleChecksRunning` and `subtitleChecksLastFinishedAt` (ISO time or null; in memory, null after a restart until the first pass ends, success or not) for the background comparison of the owner's subtitle files with OpenSubtitles'. `FilmDetail.ratings` is `{ imdb: { score, votes, url } \| null, rottenTomatoes: { score, url \| null } \| null }`; `GET /api/films?sort=imdb\|rt` sorts highest first, films without the score last |
| `GET /api/admin/attention` | `{ items: AttentionItem[] }`: Films needing attention; `AttentionItem` is `{ kind, filmId, title, year, detail, files, candidates, confident, browsable }` (`candidates` ranked best first, so the first is the best guess), where `browsable` is `Film#browsable` (the rule `GET /api/films/{id}` uses): false for a film whose every edition is missing or unplayable, which has no detail page, so Admin's file lists link its title only when true |
| `GET /api/admin/summary` | One read-only aggregate for the Admin rail and the To do strip, never cached on the server: `{ todo: { pickFilm, severalFiles, fullMissing, forcedQuestion }, subtitlesUnresolved, live, library: { films, lastScanFinishedAt \| null, lastScanError \| null }, playback: { films, smooth }, playingNow: [{ playbackId, editionId, filmTitle, device \| null, touch \| null, environment \| null, quality \| null }] }`. `pickFilm` is the `needs-match` attention items, `severalFiles` the `multiple-files` plus `parts-mismatch` ones (the same rules as `/api/admin/attention`, counted without building its items: the summary loads no Film entity or large text column: two counts, the last finished scan, a projection of each edition's film and one read of the editions with their files); `fullMissing` and `forcedQuestion` are the `counts.unresolved` of `/api/admin/subtitles/full-need` and `/api/admin/forced-subtitles/films` (`AdminTodoRules`), read by `FullNeedService.unresolvedCount()` and `ForcedNeedService.unresolvedCount()`, which give the same figure more cheaply; `subtitlesUnresolved` is their sum. `live` is the number of live HLS sessions of `/api/admin/transcoding`. `library.films`, `lastScanFinishedAt` and `lastScanError` are `counts.films` and the last finished scan's `finishedAt` and `error` in `/api/admin/scan` (both null when no scan has finished; `lastScanError` is also null when the last scan succeeded, so a failed scan is not read as a success). `playback` covers the last 30 days by `startedAt` (boundary included): `films` is the viewings (playbacks joined as in the chart) with a total `watchedMs` ≥ `FILM_SESSION_MIN_MS` (180,000), `smooth` those in which no part had a problem; the rows of the window are loaded and joined, so the rail agrees with the chart. `playingNow` is the playback health rows updated in the last 2 minutes (boundary included), newest first, at most 10, direct play included, with only the newest row of each edition (a player reload makes a new `playbackId` for the same edition). A report carries no end marker (the player's exit report is the same snapshot), so a viewing that has ended stays listed until 2 minutes after its last report: `device` is `report.client.userAgent` as stored (the client words it), `touch` is `report.client.touch` (true when the device reported more than one touch point; null for a report that predates it, and the frontend's `describeDevice` then names a Macintosh user agent a Mac, with `true` an iPad), `environment` the stored column, `quality` the rung played in the report's last `minutes` row (`original`, `1080p`, `720p`, `480p`, `360p`, `240p`), else the report's chosen `quality` (`auto` included); `device`, `touch` and `quality` are null when the stored report cannot be read. Every read is bulk: no per-film queries, and all of them run in one read-only transaction |
| `GET /api/admin/settings` | `{ continueWatchingMinMinutes: 0–30 (default 5), inProgressCount }`: the minimum watch time before a film counts as in progress, and how many films are in progress under it (the Library rule: films removed from Continue watching included) |
| `PUT /api/admin/settings` body `{ continueWatchingMinMinutes }` | the same shape with the recomputed `inProgressCount`; 400 `"Continue watching needs at least 0 and at most 30 minutes"` for a value outside 0–30 or missing. Stored in the single-row `server_setting` table (V12), applied at query time (no progress is changed or deleted) and never cached, so Home, Library and the count change at once. Two overlapping PUTs: the later one wins |
| `GET /api/admin/plex` | `PlexImportStatus`: `{ configured, reachable, running, progress: { processed, total, current } \| null, lastImport: { startedAt, finishedAt, error, report } \| null }`; `configured` is false (and the Admin panel hidden) when `PLEX_URL` or `PLEX_TOKEN` is blank. The `report` counts paired, merged, editionsMoved, metadataApplied, postersStored, backdropsStored, artworkFailures, invalidArtPaths, tmdbUnavailable, watchedImported, resumePointsImported, keptNewer, failures, onDiskNotInPlex, with capped lists (200) `failedFilms`, `notOnDisk` (+ `notOnDiskTotal`), `collections` (+ `collectionsTotal`) |
| `POST /api/admin/plex/import` | starts the import: 202 and the status; 409 with the lock text naming the holder (`LibraryLock.busyText()`: "A scan is running", "A Plex import is running", "Subtitles are being changed or checked; try again in a moment" and the like), 409 `"Run a library scan first"`, 404 `"Plex is not configured"` |
| `GET /api/admin/plex/credits` | `PlexCreditsStatus` (credits detection phase 2c, the one-off "Import credits from Plex"): `{ running, progress: { processed, total } \| null, lastImport: { startedAt, finishedAt, error, report } \| null }`; `lastImport` is kept in memory until the server restarts. The `report` is `{ plexFilms, matchedFilms, imported, unmatched, skipped: [{ reason, description, count }] }` (only reasons that happened; `description` is the label the Admin shows before the count) |
| `POST /api/admin/plex/credits/import` | starts the credits import (GET requests to Plex only; stores `plex_credits_marker` rows, applies nothing): 202 and the status; 409 `"A credits import is already running."`, 409 `"Import credits after the Plex import finishes."` (the Plex import holds the library lock for its whole run), 404 `"Plex is not configured"` |
| `GET /api/admin/credits/dataset` | `DatasetStatus` (credits classifier L2b, the job that reads training frames; off unless `cinefilm.credits.dataset.enabled`): `{ enabled, state, waitingFor, targets, alreadyComplete, complete, failed, skipped, remaining, currentMediaFileId, refresherDue, freeBytes, minFreeBytes, startedAt, finishedAt, lastError }`. `state` is `IDLE` (never started in this process, or off), `RUNNING`, `WAITING`, `STOPPED`, `DONE`, `NO_SPACE` (the disk is below the floor) or `FAILED` (see `lastError`). `waitingFor` is `PLAYBACK` (a film is playing or transcoding) or `REFRESHER` (the credits detection has films to analyse first) while `WAITING`, else null; the hourly nudge resumes a wait. `targets` is how many files have a usable Plex marker; `alreadyComplete` how many already held a current `complete.json` when this run started; `complete`, `failed` and `skipped` (the file changed or went) count this run only; `remaining` is what this run still has to do; `currentMediaFileId` is the file being read, or null. `refresherDue` is `CreditsRefresher.dueNotHeldBack()` read at the time of the GET: 0 while the dataset job is off (nothing is asked), 0 when the credits analysis itself is off, and 1 when the refresher's query fails (so the job yields); the job yields while it is above 0, and the planner starts it only when it is 0. `freeBytes` is the usable disk under the dataset directory (null when it cannot be read) and `minFreeBytes` the floor (`min-free-gb`, a GB being 10^9 bytes). `startedAt`/`finishedAt` are ISO instants (`finishedAt` null while running or waiting). `lastError` says why `FAILED` (or an unreadable disk under `NO_SPACE`). The job writes no database rows. |
| `POST /api/admin/credits/dataset/start` | starts a run: the call only takes the job over (so the 404 and 409 are immediate) and the preparation is one task on the credits thread, so `prepare` and the sweep of `frames/` only ever run there (it makes the directories, reads the ffmpeg version, loads the targets, skips files that already hold a current `complete.json`, deletes only the frame directories that are not a finished read: those with no parseable format-1 `complete.json` (partial, old format, unparseable, with any leftover `complete.json.tmp`) and those of a target whose `complete.json` holds another fingerprint; finished directories of files that are not targets now, and anything that is not a directory, are left alone and counted in a log line; writes the `running` marker): 202 with the status, which is usually `RUNNING` with nothing counted yet, or `FAILED` at once if the credits thread refuses the task; a preparation that fails later (ffmpeg missing, targets not loadable, a misplaced directory) shows as `FAILED` with `lastError` in `GET`; 409 `"The dataset job is already running"`, 404 `"The dataset job is off"` |
| `POST /api/admin/credits/dataset/stop` | stops at once and removes the `running` marker (so a restart does not resume; after a restart the job resumes only if the marker was there, queued as a task on the credits thread behind the refresher's start-up run, not on the main thread): 202 and the status; never refused. A run that is done or failed keeps its state; a job that is off answers its status unchanged |
| `GET /api/admin/christmas` | The Christmas films screen: `{ week: { start, end }, limits: { max, min }, keywords: { fetched, total, running }, inWeek: Item[], added: Item[], leftOut: LeftOutItem[], fallsBack }`. `week` is this year's Christmas week (Monday to Sunday, ISO dates, `HolidayWeeks`), or next year's once today is after its Sunday. `limits` are `WeeklyThemeSelector.MAX_FILMS` (12) and `MIN_FILMS` (5). `keywords`: films with a TMDB id (`total`), those whose keywords were fetched (`fetched`), and whether `KeywordsRefresher` is running. `inWeek` is every film that counts, in Home's order (year, null last, then sort title) with **no 12 cap** (Home shows the first 12 of the same list, one code path: `WeeklyThemeSelector.christmasFilms`); rule: owner's exclude beats owner's include beats TMDB's `christmas` keyword. `Item { filmId, tmdbId, title, year, posterUrl, reason: "KEYWORD" \| "ADDED" }` (`posterUrl` is the stored `posterPath`, as on every other card). `added` is the included films TMDB does not tag. `leftOut` is the excluded films, `LeftOutItem` = `Item` fields without `reason` plus `taggedByTmdb`. Overrides for a TMDB id no library film has are ignored. `fallsBack` = `inWeek.length < limits.min` (Home would show the normal rotation) |
| `PUT /api/admin/christmas/{tmdbId}` body `{ choice: "INCLUDE" \| "EXCLUDE" \| null }` | The same body as the `GET`. `null` deletes the owner's choice ("Put back", "Remove"). After every write, an INCLUDE row is deleted only when EVERY film row holding that TMDB id carries TMDB's `christmas` keyword (it is then redundant); a film sharing the id that TMDB does not tag keeps its INCLUDE. Writes are one at a time, so two quick changes to one film are last-write-wins on its one row. 400 `Invalid request` for an unknown `choice` or a non-numeric id; 404 `No film with TMDB id N` when no film that browse shows (not one whose files are all missing or hidden) has that TMDB id, so a choice for a film that has dropped out of view cannot be cleared until it returns (the row is kept and applies again then); 409 with the lock text naming the holder when any library writer holds the library lock (`LibraryLock.busyText()`; the change takes the lock itself for its short write, so a scan, import, Fix match, edit, subtitle or credits write is never interleaved with it): `A scan is running — try again when it finishes` for a scan, "A Plex import is running" and the like for the others; nothing changes |
| `GET /api/admin/christmas/states?filmIds=1,2,3` | `[{ filmId, tmdbId \| null, state }]` in the order asked (unknown ids and films that browse hides are omitted, repeats once), `state` one of `IN_KEYWORD` (counts through the keyword), `IN_ADDED` (counts because the owner added it), `LEFT_OUT`, `NOT_IN`, `KEYWORDS_PENDING` (has a TMDB id, is not in, keywords not fetched yet), `NO_TMDB_ID` (cannot be added). At most 50 distinct ids: more is 400 `Ask for at most 50 films at a time`; none gives `[]` |
| `GET /api/art/{name}` | artwork imported from Plex; `name` must match `^[0-9a-f]{64}\.(jpg\|png)$` (else 404); `Cache-Control: private, max-age=31536000, immutable`. Optional `?w=342\|500\|780\|1280`: a copy resized to that width is made on the first request and cached (any other value, an empty one or two `w` parameters is 400 `Width must be 342, 500, 780 or 1280`; never upscaled: an image not wider than `w`, or whose copy would not be smaller, comes back as stored; one that cannot be resized comes back as stored with `private, max-age=86400`; busy resizer: stored with `no-store`). A film's or collection's `posterPath`/`backdropPath` is either a TMDB path (`/abc.jpg`) or `/api/art/<sha>.<ext>`, to which `imageUrl` adds `?w=` for the size a screen asks for |
| `GET /actuator/health`, `GET /actuator/health/container`, `GET /actuator/info` | Not under `/api`, open to a signed-out caller, and the only Actuator endpoints exposed (all others 404). `health` is `{status}` only; `health/container` (database + ping, status only) is the Docker healthcheck and deploy smoke probe; `info` carries `git.commit.id.full` of the running build |
| `GET/POST/DELETE /api/session` | `GET`: 200 `{authenticated:true}` when signed in, 401 `{authenticated:false}` when not; `POST { password }`: 200 and cookie, 401 on a wrong password; `DELETE`: sign out (200) |

The browse GETs (`/api/home`, `/api/films`, `/api/films/{id}`, `/api/people/{id}`, `/api/collections`, `/api/collections/{id}`) and `/api/search-index` carry a weak `ETag` (`W/"…"`, the first 16 hex characters of the SHA-256 of the exact bytes sent) and `Cache-Control: no-cache, private`, so the browser keeps a copy and asks before every use; a matching `If-None-Match` is answered `304` with the `ETag` and `Cache-Control` and no body. Errors are unchanged: no `ETag`, and Spring Security's `no-store`. A browse answer under 2 KB is not compressed (Tomcat's minimum).

**FilmCard:** `{ id, title, year (number | null), posterPath, backdropPath, watched, progress: { positionMs, runtimeMs, editionId } | null, director, collectionId (number | null), sortTitle, inProgress, lastWatchedAt (ISO time | null), hiddenFromContinueWatching }`. `progress` is any saved position above 0 (Film detail and the player resume below the minimum watch time too). `inProgress` is the rule clients use and never recompute: the last played edition's position is at or above the minimum watch time (`continueWatchingMinMinutes`, below) and above 0; the watched flag is not looked at (a rewatch is in progress). `lastWatchedAt` is that edition's `lastPlayedAt`; `hiddenFromContinueWatching` is that edition's Remove flag. `GET /api/films` returns `LibraryCard`s instead: the same fields without `backdropPath`. `collectionId` is set only by `GET /api/films`, and only when the film's collection has two or more films in the library (the "two films make a collection" rule); everywhere else it is `null`. `sortTitle` is the key titles are sorted by (lower-case, leading "the" dropped, or an import's custom value). The Films grid uses both to fold collections into tiles and place them under the title sort.

**CollectionCard** (Library, Collections tab): `{ id, name, posterPath, ownedCount, totalCount, posterPaths: (string|null)[], lastAddedAt }`. `posterPaths` = up to 3 owned films' poster paths, earliest release first (the front of the fan); `lastAddedAt` = the newest `addedAt` among the owned films (ISO instant), for "Recently added to". `name` is TMDB's; the client drops a trailing " Collection" for display (`state/collections.js`).

**Two films make a collection** (handover decision 16): a collection with fewer than two films in the library
is not in `GET /api/collections`, has no `FilmCard.collectionId` and is `null` as `FilmDetail.collection`. `GET
/api/collections/{id}` still answers for it, so an old link keeps working.

**FilmDetail:** FilmCard fields plus `{ overview, genres, collection: {id,name}|null, directors: Person[], cast: [{ person, character }], editions: [{ id, name, year, runtimeMs, note, creditsMarkerMs (nullable), positionMs, watched, file: { id, videoCodec, width, height, audioSummary, addedAt }, audioStreams, subtitleStreams, qualities: [{ id: "auto"\|"original"\|"1080p"\|"720p"\|"480p"\|"360p"\|"240p", bitrate, premade, available }], parts, forcedSubtitle: { source: "SIDECAR"\|"DOWNLOAD", ownerVerified, version, sidecarIds, offsetsMs } \| null, fullSubtitle: { source: "SIDECAR"\|"DOWNLOAD", version, sidecarIds, offsetsMs } \| null }], preferences: { editionId, quality, audioStreamId, subtitle: "off"\|"full"\|<stream id as text>\|null (never chosen: the player reads it as Off, except that a film whose `fullNeed` is `NEEDED`, with a full subtitle and non-English selected audio, turns the full subtitle on by itself, unsaved) }, ratings, fullNeed: "NEEDED"\|"NOT_NEEDED"\|"UNSURE"\|"UNKNOWN" }`; `fullNeed` is whether the film needs full English subtitles (`FullNeed.decide`, see the full-need routes), worked out with one extra query for the film's audio

`sidecarIds` (the subtitle rows `forcedSubtitle` / `fullSubtitle` are made of, in play order, the ids the Admin list gives) and `offsetsMs` (the timing offset of each of those rows in milliseconds, same order and length) let the player offer "Earlier" / "Later" without reading the whole Admin list; an offset change moves the served `version`.

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
src/components/ Poster, FilmRow, CollectionCard, CollectionTile, CollectionFan, LibraryTabs, Button, Switch, Segmented, Picker, Skeleton, Header, TabBar…
src/screens/    Home, Library, FilmDetail, Person, Collection, Player, Admin (placeholder in phase 1), SignIn, states
src/styles/     tokens.css (copied verbatim from the handover), base.css, one CSS module per component
```

Browse data is cached in memory (`src/state/resourceCache.js`, used by `useResource`): at most 12 answers for up to 30
minutes, shown at once and checked again in the background; Film detail's answer is also reused, without a request,
by the Player for a minute. Every change this tab makes (any `POST`, `PUT`, `PATCH` or `DELETE` except starting or
ending a playback session and the health report; a preference save drops only its own film) empties it, as do a
library change and a session expiry, and an answer whose request overlapped such a change is never stored.

Routes: `/`, `/library` (Films tab), `/library/collections` (Collections tab), `/films/:id`, `/films/:id/play?edition=`, `/people/:id`, `/collections/:id`, `/admin`.

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
- **Phase 3 — transcoding and network resilience (owner's top priority, 2026-11-09).** Plan:
  `docs/engineering/transcoding-plan.md` — one HLS stream per playback whose top rung is the original (video copied)
  and whose lower rungs (1080p/720p/480p/360p/240p) are transcoded live with VAAPI on dockermacmini, ABR in hls.js and
  Safari, the same policy at home and away. Server-side pre-made "travel copies" (decision 15) are replaced by a later
  "Download for offline"; the separate worker and workhorse failover wait until dockermacmini proves too slow.
  The frontend loads the light build (`hls.js/light`, about 65 KB smaller gzipped, parsed on every first play): it
  lacks alternate renditions, EME and HEVC/AC-3 in TS, none of which CineFilm sends; an HEVC copy rung or `#EXT-X-MEDIA`
  would need the full build again.
- **Phase 4 — Plex import.** Import films, posters and watch history from Plex; Admin import panel. Plan: `docs/engineering/plex-import-plan.md` (brought forward; built in the "import the library from Plex" PR).
- **Phase 5 — ship.** Production polish: the undrawn mobile screens (Library, Person, Admin), Apple TV
  focus-navigation groundwork. (Packaging, deploy, backups and HTTPS behind Tailscale moved to
  `ci-test-deploy-plan.md` phases 7–9, which land before product phase 2.)
- **Phase 6 — CineLight (last, owner 2026-11-08).** CineFilm emits **Plex-compatible webhooks** — an HTTP POST,
  not Kafka — to a configured URL (`CINEFILM_WEBHOOK_URLS`): `media.play`, `media.pause`, `media.resume`,
  `media.stop` and `media.scrobble` (sent when the film is marked watched at the credits marker / 92%), in Plex's
  multipart `payload` format. CineLight already consumes exactly these on `POST /plex`, so what the lights do is
  CineLight's concern, not CineFilm's. Sending is fire-and-forget: a failed webhook logs and never affects
  playback. "Dim the lights" off adds a skip flag to the payload for that playback (decision 8).

## Deployment topology (owner, 2026-11-08)

| Machine | Role |
|---|---|
| **dockermacmini** (Mac mini running Ubuntu, Docker) | The default home: runs CineFilm (server + SPA) in Docker, and the default transcode worker. |
| **workhorse** (Mac mini 2018, Intel, macOS, always on) | The films live on an external USB-C disk attached to it. Fallback transcode worker. |

Consequences for the design:

- **Media over the network.** workhorse shares the film disk read-only (SMB); dockermacmini mounts it on the Ubuntu
  host (`/etc/fstab`, `cifs`) and passes it into the container as a read-only volume (`/media`). The scanner and
  direct play read through that mount. Paths in the database are stored relative to a configured library root, so
  the mount point can change.
- **The transcode worker is its own process**, not a thread in the server, so the same worker can run in two
  places. Each worker registers with the server, sends a heartbeat with its capacity (active sessions and encode
  speed as a multiple of real time) and pulls jobs.
- **Failover:** the server sends a transcode to the dockermacmini worker by default and to the workhorse worker when
  dockermacmini is offline or can't keep up (speed below ~1.1× real time, or all its slots busy). Admin's
  Transcode worker panel shows both. "Worker offline" in the UI (§4) means *no* worker is online.
- **Hardware encoding.** dockermacmini is a Late 2012 Mac mini (`Macmini6,2`, Core i7-3615QM, Ivy Bridge, HD
  Graphics 4000) with `/dev/dri/renderD128` exposed. Its Quick Sync can **encode and decode H.264** through VAAPI,
  but it has **no HEVC support**, and Ivy Bridge needs the legacy `i965` VAAPI driver (`i965-va-driver`), not
  `intel-media-va-driver`. Confirmed on the host with `vainfo` (2026-11-08): `iHD` fails to
  initialise, `i965` loads and offers H.264 Baseline/Main/High encode (`VAEntrypointEncSlice`). The worker container
  sets `LIBVA_DRIVER_NAME=i965` and gets `--device /dev/dri` and the `render` group; ffmpeg encodes with
  `h264_vaapi`. Consequences:
  - H.264 sources: hardware decode + hardware encode, comfortably faster than real time.
  - HEVC sources (many rips are 1080p HEVC): software decode on four 2012 cores + hardware H.264 encode. About one
    1080p stream at a time; 4K HEVC will not keep up and fails over to workhorse.
  - Safari plays HEVC natively, so at home "Original" direct-plays and needs no worker at all. Transcoding is for
    remote/slow links and pre-made versions.
  The workhorse worker runs natively under macOS (a launchd service running the worker jar), not in Docker, so it
  can use `h264_videotoolbox`/`hevc_videotoolbox`, and it reads the films from its own disk. workhorse is the
  2018 Intel Mac mini (owner believes the last Intel model; 8th-gen Core, UHD 630 + T2), whose media engine
  decodes and encodes both H.264 and HEVC in hardware, so it handles the HEVC and 4K sources dockermacmini can't. Pre-made versions
  (decision 15) of HEVC films are better made there.
- **TMDB:** free for personal, non-commercial use; the owner creates an account and uses the **Read Access Token**,
  supplied as `TMDB_READ_TOKEN` and sent as `Authorization: Bearer`, never in a URL (library scan, below). TMDB's
  terms require crediting TMDB (a logo and notice in Admin).

## Open questions for the owner

None outstanding for the topology. workhorse is always on, so the film share is always available.

## API details settled in phase 1A

- Streams: `audioStreams[]` = `{ id, index, language, channels, codec, title, isDefault, safariPlayable }`,
  `subtitleStreams[]` = `{ id, index, language, forced, title, format }`; `id` is the database id.
- `preferences` = `{ editionId, quality, audioStreamId, subtitle }`: `audioStreamId` is an audio stream `id`;
  `subtitle` is `"off"` or a subtitle stream id as a string; `quality` is one of `auto|original|1080p|720p|480p|360p|240p`.
  `PUT /api/films/{id}/preferences` changes only the fields sent (400 for an unknown edition or quality).
- `directors` entries and cast `person` are `{ id, name, profilePath }`; Person `films[].roles` are display strings
  ("as Neil McCauley", "Director").
- `GET /api/editions/{id}/stream` takes only the id; query parameters such as `start` and `dim` are ignored.
  `addedAt` fields are ISO-8601 instants.
- Rewatch: when an edition becomes watched (threshold reached, or `POST .../watched` with true) its saved position
  is reset to 0 and `watched` stays true; progress reports at or past the threshold keep it at 0. Continue watching
  is every film whose most recently played edition has `positionMs` at or above the minimum watch time (above 0),
  watched or not, so a watched film you restart is in progress again. "Not watched in years" is unchanged. `continueWatching` omits the `hero` film.
- Errors are JSON `{ "error": "..." }`: 400 invalid input, 401 signed out, 403 missing `X-CineFilm: 1` on a
  mutating request, 404 unknown id.

## Library scan and direct play of the real films (phase 2, first slice)

Plan: `library-scan-plan.md`. What the API and the model now carry:

- **Library root.** `cinefilm.library.root` (env `CINEFILM_LIBRARY_ROOT`, default `/media`). `MediaFile.path` is stored
  **relative to the root** (forward slashes) and resolved only by `LibraryRoot.resolveFile`, which refuses `..`,
  absolute paths and symlinks that leave the root (SECURITY.md rule 3). The stream and subtitle endpoints, and the
  scanner, use it; nothing else reads media.
- **Model additions (Flyway V2, expand-only).** `MediaFile.modifiedAt`, `presence` (`PRESENT` | `MISSING`),
  `playability` (`PLAYABLE` | `PROTECTED` | `NEEDS_CONVERSION` | `UNREADABLE`); `Film.matchStatus` (`MATCHED` |
  `NEEDS_MATCH` | `PENDING`) and `matchCandidates` (JSON, top five TMDB candidates); table `scan_run`. A film is
  **browsable** (Home, Library, Person, Collection, detail) unless every edition's original file is missing or not
  playable: such films appear only in Admin, and a missing file keeps its Film, Edition and watch history.
  `AudioStream.index` and `SubtitleStream.index` are ffprobe's stream indexes in the file (a rescan updates streams in
  place by index, so their ids, which saved audio and subtitle choices hold, survive; a choice for a stream that is
  really gone resets to the default / `off`). **`year` is nullable** (migration V3): a film whose year is unknown has
  `year: null` in `FilmCard`, `FilmDetail` and the Admin API, and the UI omits the year and its separator.
  `FilmCollection.partsComplete` is false while TMDB could not give the collection's parts; later scans (and any film
  of that collection) retry. Merging a pending film into an existing one moves its `film_preference` to the survivor
  (or drops it if the survivor has its own) before deleting it.
- **Saved audio and subtitle choices** (`preferences.audioStreamId`, `preferences.subtitle`) hold a stream id from one
  edition's file, and the API is unchanged: the client translates the saved choice for the selected edition
  (`resolveAudioChoice` / `resolveSubtitleChoice` in `frontend/src/player/tracks.js`). A stream of the selected edition
  is used as is; otherwise subtitles map to the stream with the same language and forced flag (else Off), audio to the
  same language and title, then language and channels (else the edition's default). TMDB collection parts are ordered
  by full release date, then id. The release year of a scanned file comes only from the `date`/`©day` tag (1888 to next
  year) or the title/file name, never from `creation_time` (the rip date).
- **Scan rules.** Walks the root for `.m4v .mp4 .mov` (playable) and `.avi .mkv` (needs conversion), skipping hidden
  and `._*` files. A file is identified by its relative path; a rescan updates changed files, adds new ones, follows a
  rename (same size and modified time, old path gone) keeping the edition, and marks vanished files `MISSING`. A walk
  that finds nothing while files are known is treated as an unmounted share and marks nothing missing. A TMDB outage
  leaves a film `PENDING` (title from the file) and it is retried by the next scan.
- **Admin To do rules (`AdminTodoRules`, package `todo`):** pure, shared by the Admin lists so rail, To do and
  Subtitles cannot disagree. *Confident match:* the top score is at least `TmdbMatcher.ACCEPT_SCORE + 1` and the candidate is alone or leads the
  second by at least `TmdbMatcher.MARGIN + 1`. The matcher decides on unrounded totals and stores whole numbers (floored
  now, rounded in older rows), so asking one more point of each means neither can claim more than the matcher would; an
  exact boundary may be declined, the safe side. A tagged (`{imdb-tt…}`) match the guard refused is one candidate at
  `REFUSED_SCORE` (100), while a refused title-search match keeps the matcher's candidates and is judged like any other.
  *Full row in play:* `need == NEEDED`, the owner choice is not `NONE_EXIST`, and no full English subtitle plays.
  *Forced row in play:* `need == NEEDED` and `hasForcedSubtitle` is false (today's `needed-missing`; a film's embedded
  forced track counts as a subtitle that plays). An in-play row is **being checked** when the film has an IMDb id and
  either OpenSubtitles has not been searched for its current IMDb id (full: no stored full search for that id; forced:
  `openSubtitlesForcedCount` is null, or the film has no stored candidates, which the search scheduler fills in:
  `os_candidates` null, the same predicate as `findOpenSubtitlesDue`) **and searching is enabled** (an API key is set),
  or the **download scheduler would download for some edition** and downloads are enabled (key, username and password
  are set; with downloads off `PendingDownloads` reads nothing and returns none): full,
  `ForcedDownloadService.fullDueFor` is non-null for an edition; forced, the count is above 0 and
  `ForcedDownloadService.dueFor` is non-null for an edition. Those are the scheduler's own decisions (due, a candidate
  left after blocks, the ledger and spent references, under `MAX_DOWNLOADS`), run by `PendingDownloads` over the
  scheduler's own edition set (editions whose files are missing included). Otherwise the row is **unresolved**. The
  daily download limit is never looked at, so a deferred download is still being checked. Rows that are not in play are
  neither. Only Admin's lists and the owner's choice compute the flags; the scheduler does not (its automatic search and
  download use `searchFilmIds()`: the `NEEDED` films minus the owner's `NONE_EXIST`; timing-fit uses `neededFilmIds()`,
  all `NEEDED` films).
- **`ScanStatus`:** `{ running, folder, progress: { processed, total, current } | null, lastScan: { startedAt,
  finishedAt, trigger: "startup"|"nightly"|"admin", filesSeen, newFiles, missingFiles, error } | null, counts: {
  films, newFiles, missing, protectedFiles, needsConversion, needsMatch, unreadable, filmsWithCredits } }`.
- **`AttentionItem`:** `{ kind: "needs-match"|"protected"|"needs-conversion"|"unreadable"|"multiple-files", filmId,
  title, year | null, detail, files: string[], candidates: [{ tmdbId, title, year, runtimeMinutes, posterPath, score }],
  confident: boolean, browsable: boolean }` (`candidates` only for `needs-match`; `kind` also `"parts-mismatch"`). `score` is the matcher's
  points (accepted automatically at 60 with a lead of 10); `confident` is `AdminTodoRules.confident` over the
  candidates' scores and is false for every kind except `needs-match`. `needs-match` is shown as Unmatched with a Fix
  match button; `multiple-files` only for genuine ambiguity in a film that is not `editions_confirmed` (the detail
  says which case); `parts-mismatch` is a multi-part edition that fails `PartsMatch` (it will transcode).
- **Fix match** (`FixMatch`, owner-only like every `/api/admin` route; mutating calls need `X-CineFilm`):
  - `GET /api/admin/films/{id}/match?query=&year=` searches TMDB and answers `MatchSearchDto { fileRuntimeMs | null,
    suggestedTmdbId | null, suggestedBecause: "runtime"|"guess" | null, results }`: `results` is at most 8 in TMDB's
    order, each `{ tmdbId, title, year | null, runtimeMinutes | null, posterPath | null, director | null, cast: string[]
    }`, details fetched through the TMDB pacer. `fileRuntimeMs` is the runtime of the film's default edition (the
    stored default while it is still an edition, else `DefaultEdition.choose`), null when none or 0. The server picks
    the default so every client agrees: (1) if the file's length is known and exactly one result's runtime matches it
    (`TmdbMatcher.runtimeMatches`, the scanner's own 8 minute tolerance) suggest it, `"runtime"`; (2) else the film's
    first stored `matchCandidates` entry, when it is among the results and the length does not contradict it (the
    runtimes match, or either is unknown), `"guess"`; (3) else nothing. It only reads TMDB, takes no library lock and
    works during a scan. `year` is optional.
  - `POST /api/admin/films/{id}/match { tmdbId }` applies `applyMovie` and sets `editions_confirmed`, answering `{
    filmId, joined }`. If another film already has that TMDB id, this film is joined into it (the lowest id; the owner's
    explicit choice, so the different-film guard is not applied), the survivor is confirmed, `filmId` is the survivor's
    and `joined` is true; a `join` line is written to the grouping report. Takes the library lock.
  - `POST /api/admin/films/{id}/leave-unmatched` (no body) sets the film's `match_status` to `LEFT_UNMATCHED` and clears
    its stored candidates, answering 204. To do and the "Need a match" count leave it out (`findNeedingMatch`), no scan
    retries it, and a Fix match by id or a Plex import that supplies a TMDB id still makes it `MATCHED`. Only a film
    that To do lists as needing a match may be left (`NEEDS_MATCH`, or no TMDB id and not `PENDING` or
    `LEFT_UNMATCHED`): any other, an already-left film included, is a
    409 "This film is not waiting for a match". Takes the library lock; 404 unknown film; 409 with the lock holder's text
    while a scan, import or match holds it.
  - Errors (`{ error }`): 400 blank query or missing `tmdbId`; 404 unknown film, or TMDB does not know the id; 409
    409 with the lock holder's text, "A scan is running" for a scan (apply only, when the library lock is held); 503 TMDB unavailable.
- **Edit editions** (`EditionArranger`, owner-only; the panel is `docs/design/handover/edit-editions/README.md`):
  - `GET /api/admin/films/{id}/editions` returns `{ files: [{ id, fileName, durationMs | null, width | null, height |
    null, videoCodec | null, sizeBytes | null, presence: "PRESENT"|"MISSING", editionId, partNumber, modifiedAt | null }], editions: [{
    id, name, default }], suggestion: { reason, editions: [{ id | null, name, files: [{ fileId, part | null }] }],
    notThisFilm: [fileId], defaultName } }`. `files` are the film's original files in edition and part order (`modifiedAt` is the ISO instant the file was last modified on disk);
    `default` marks the edition the film opens on now. The suggestion (`EditionSuggester`) is already in the shape the
    panel edits: `id` is the existing edition a suggested one continues (its history stays), `part` is `null` for a
    single file, `reason` is one line (empty for a single file). A film the owner has settled (`editions_confirmed`)
    is suggested as it is arranged ("As you last arranged it").
  - `PUT /api/admin/films/{id}/editions` takes `{ editions: [{ id | null, name, default, files: [{ fileId, part |
    null }] }], notThisFilm: [fileId] }` and answers `{ filmId, newFilmIds }` (the unmatched films made for
    `notThisFilm`, in its order). One transaction under the library lock. A kept edition (its `id`) keeps its resume
    position and watched state (position clamped to the new runtime) whatever it is renamed to; a new edition starts
    fresh; an edition left with no files is deleted with its state; part numbers, runtimes, `default_edition_chosen`,
    `grouping_source = OWNER`, `editions_confirmed` are set; every file marked Not this film moves, keeping its id, to
    a new film with `NEEDS_MATCH` (title from the file name; Unmatched with Fix match in Admin); one `split` (when files
    moved out) or `join` line goes to the grouping report. Needs `X-CineFilm`.
  - **Validation** (`EditionRules`, mirrored in `frontend/src/state/editionRules.js`, one shared table of cases in
    `backend/src/test/resources/shared/edition-validation-cases.json` that both suites read): every file in exactly one
    edition or in `notThisFilm`; each edition's parts 1..n without gaps or duplicates (`null` only as an edition's only
    file; a lone file may be `null` or 1; parts 1 to 8); names 1 to 32 characters after trimming, unique
    case-insensitively (an existing edition keeps its name over a new one); exactly one `default`; at least one
    edition.
  - **Error shape:** a rule broken is `400 { error: "<first message>", errors: [{ field, message }] }`, in the owner's
    words. `field` is a path into the request, in a fixed order: `files`, then for each edition in turn
    `editions[i].name`, `editions[i].parts` (or `editions[i].files` for an empty edition), then `editions`, then
    `default`. Other errors are `{ error }`: 400 an edition that is not the film's or is listed twice, or an unreadable
    body; 404 unknown film; 409 with the lock holder's text ("A scan is running" for a scan) when the library lock is held (by a scan, an import, a match or
    another save).
- **Grouping report:** `GET /api/admin/grouping-report` returns `{ items: [{ happenedAt, kind: "split"|"join"|"flag",
  summary }] }`, every `grouping_change` row logged from the last finished scan's `startedAt` to its `finishedAt` (both included), newest first, with no cap (a scan holds the library lock, so no other writer logs inside that window); `items` is empty before a scan has finished. Owner edits outside a scan (Fix match, Edit editions, Plex import) are not in it.
- **Forced subtitle files** (`docs/engineering/forced-subtitles-plan.md`): the same walk collects English `.srt` files
  by their trailing dot-tokens (`SidecarNames`, any case, same skips): `<stem>.en.forced.srt` or
  `<stem>.forced.en.srt` (also `eng`) is kind `FORCED`; `<stem>.en.srt` or `.eng.srt`, with at most one of `sdh`, `hi`
  or `cc` before or after the language, is kind `FULL` (V22, phase 5b: `subtitle_sidecar.kind`, also on
  `forced_download_block` and `forced_check_candidate`, whose unique keys now include it). Every repository read takes
  its kind explicitly, so no forced path sees a `FULL` row; the scan's own two `findAll()` calls stay all-kinds. After
  grouping, every scan syncs the `subtitle_sidecar` table
  (V13): a new or changed file is read and parsed (`SrtParser`: encoding detection, at most 1 MB and 5,000 cues), an
  unchanged one is not read again, a file not found is marked `MISSING` (never deleted, and not under a folder the walk
  could not read), and every file the owner has not attached by hand is matched to a film file by exact name (path
  minus suffix equals path minus video extension, ignoring case; anything else stays unmatched for Admin) whether or
  not the film changed. Rows with `origin = OPENSUBTITLES` (V19: subtitles downloaded for an edition, stored against its
  part 1 as whole-edition files with a made-up path) are never read, matched or marked missing by the scan.
- **Downloaded forced subtitles** (phase 4a, `forced-subtitles-plan.md` "Phase 4a build"): a background run
  (`ForcedDownloadRefresher`, after a scan, the OpenSubtitles search and the checks, after a removal, and daily 06:45)
  downloads a subtitle for each edition whose film needs one and where nothing plays, or where the owner's file failed
  every check, within the shared daily budget (`cinefilm.opensubtitles.daily-download-limit`: the account's allowance, capped by it:
  replacements first, up to 70%, then missing, comparisons up to 30%). Each download is proven by its timing fit against the film's own English track, else
  by a second opinion from a different OpenSubtitles file. Only the film's IMDb id changing removes a download
  automatically; otherwise `DELETE /api/admin/subtitles/{id}`.
- **Scheduling.** At start-up when the library has no films, nightly 04:30 `cinefilm.time-zone`, and from Admin. One
  scan at a time.
- **Config.** `CINEFILM_LIBRARY_ROOT`, `CINEFILM_CACHE_DIR` (default `/app/cache`, where the deploy mounts the writable cache; `scripts/test/config-agreement.test.sh` keeps the two in step), `TMDB_READ_TOKEN`
  (empty disables TMDB: films are added with the file's own title and stay `PENDING`).

## Adaptive streaming API (phase 3, see `transcoding-plan.md`)

All behind the session cookie; the mutating routes need `X-CineFilm`.

| Route | Contract |
|---|---|
| `POST /api/editions/{id}/sessions` `{audioStreamId?, startMs?, quality, probeKbps?, playbackId?}` | 201 `{sessionId, masterUrl, startRung, renditions:[{id,height,bitrate,copy}]}`; 404 unknown edition or unplayable file; 400 bad quality, audio stream, `startMs`, or a pinned rung the film lacks; 409 `{error}` when the encoder is unavailable, the index cannot be built, or `original` has no copy rung (the client direct-plays). `playbackId` is the viewing's lower-case UUID (the id its health reports carry; 400 `Invalid playbackId` when it is not one): the session keeps it so Admin's Stop can end that viewing. **410 `{error: "Playback was stopped from Admin"}`** (not a 404, not a new session) when that playbackId was stopped from Admin in the last 24 hours; the client then does not direct-play or retry (owner 2026-11-15). `probeKbps` (Auto only) is the measured speed in kbit/s: 0 means nothing was measured and starts on the lowest rung; absent starts on the top rung. The client direct-plays on a 409, another 4xx (408 and 429 aside) or a second 500; asks again with backoff under "Reconnecting…", without limit, when the request gets no answer or a proxy's 502, 503 or 504 (or a 408 or 429); and on a 401 waits for the sign-in and then asks again (`poor-link-plan.md`) |
| `GET /api/sessions/{id}/master.m3u8` | per-session master, intended start rung first, only the pinned rung when a quality is chosen |
| `GET /api/sessions/{id}/{rung}/index.m3u8`, `.../seg-{n}.ts` | VOD playlist from the cut list; segments 503 + `Retry-After: 1` while the encoder catches up (never 404); 404 for a bad path segment, rung outside the ladder or `n` past the segment count. A session that went quiet (10 minutes without a request) is rebuilt under the same id by its next request to any of the three session routes, with the same playlists (503 + `Retry-After: 1` while its file cannot be read just now); 404 `No such session` only for a session that was ended (`DELETE`, or Admin's Stop after its 24 hours), replaced by a newer one for the edition, pushed out at the cap or out of the 8 remembered, whose encoder failed, lost to a restart, asked for a day or more after its last request, or whose file has changed or gone (`long-pause-plan.md`); `Cache-Control: no-store`. **410 `{error: "Playback was stopped from Admin"}`** on all three routes for a session Admin stopped in the last 24 hours (checked before the session is looked up, so it answers 410 although the session is gone; after the 24 hours, like a session a day after its last request, the 404 above applies again); the player then does not recover (a 404 still makes a new session) |
| `DELETE /api/sessions/{id}` | 204, idempotent |
| `GET /api/admin/transcoding` | `{encoder: vaapi/software/unavailable, index:{indexed,total}, hlsBytes, sessions:[{id,filmTitle,editionId,rung,speed,segmentsAhead,startedAt,unavailable,playbackId}]}`; `playbackId` is the viewing the session was created for (the id its health reports carry), or null when the client named none: Admin's Stop targets the session whose `playbackId` is the row's, and only a session with none is matched by edition |
| `DELETE /api/admin/transcoding/sessions/{id}?playbackId=` | 204, idempotent. Optional `playbackId` (the row's viewing, from `GET /api/admin/transcoding`; a UUID, else 400 `Invalid playbackId`): the stop records the session's own viewing when the session is known, else the one given, and also ends every live (or remembered) session of that viewing, so a newer session the player made after Admin's list was read does not play on. Ends the session like `DELETE /api/sessions/{id}` and also records, in memory for 24 hours (`StoppedPlaybacks`, lost on a restart), the session id and the playbackId it was created with, so that session's routes and a new session for that playbackId answer 410 (Admin's Stop ends playback on the device: owner 2026-11-15). The player's own `DELETE /api/sessions/{id}` records nothing |

`FilmDetail` editions carry `parts` (an int: how many files the edition is made of, 1 for a film in one piece; a split
film is one edition on one timeline, so `runtimeMs`, `positionMs` and `creditsMarkerMs` are edition times and the audio
and subtitle streams are part 1's). For such an edition `original` is available only when the parts match and can be
copied (it is never direct-played), and `POST /api/editions/{id}/sessions` answers 503 `{ "error": "The film is still
being prepared" }` with `Retry-After: 1` while a later part's keyframe index is being made (the client asks again).

`FilmDetail` editions list `qualities[]` with ids `auto|original|1080p|720p|480p|360p|240p`; transcoded rungs and
`auto` are `available:false` when the encoder is unavailable.

## Playback health API (see `playback-health-plan.md`)

All behind the session cookie; the `PUT` needs `X-CineFilm`. The report's fields, their meaning and the rules they
must meet are in the plan ("The record" and "API"). `client` is `{ userAgent, screen, cores, touch }`; `touch` (admin redesign 5a-1) is an optional boolean, `navigator.maxTouchPoints > 1`, sent by every player from this release on. A report without it is valid and is stored as it was (a null is left out of the stored text, so no stored report changes size); a scalar Jackson coerces to a boolean (`"true"`, `1`, `0`) is accepted and stored as a boolean, as `cores` is coerced to a number, and only a value it cannot coerce (`"maybe"`, `[]`, `{}`) is a 400 `Invalid health report`. It is returned inside `report.client` by the Admin read routes, so the log rows carry it. The chart rows carry it too (admin redesign 5a-2). The report also carries two optional film positions (joined viewings, `docs/engineering/joined-viewings-plan.md`): `startPositionMs`, the position of the first beat, and `endPositionMs`, that of the latest, each a whole number of ms from 0 to 2,000,000,000 when present (a negative or larger one is a 400 `Invalid health report`, as any figure is). A report without them is valid and is stored as it was (a null is left out of the stored text); they are read back inside `report` by the Admin routes and are used only to join playbacks into viewings (below).

The report's `minutes` is a list of rows, one per minute, each a list of numbers. A row has **seven** figures:
`[minute, median kbit/s, rung played, ms moved, ms stalled, seconds buffered ahead, rung being fetched]` (rung codes
0 to 6). A row of **five** figures, without the last two, is still accepted from a tab running a player older than
`train-link-plan.md`, and is what records stored before it hold; any other length is refused. The full-record
`GET` returns the rows as they were stored, so a client must read both lengths.

| Route | Contract |
|---|---|
| `PUT /api/playback-health/{playbackId}` body: the health report | 204. `playbackId` is a lower-case UUID made by the browser, one per viewing. A new id creates the record (404 `No such edition`); a known id replaces the report when `seq` is higher, otherwise it is left alone (still 204). 400 `Invalid health report` for an id that is not a UUID, a body over 20,000 characters or not JSON (this route reads the body as text, so it does not answer `Invalid request`), a report outside the rules, or a known id with another `editionId` |
| `GET /api/admin/playback-health` | `{ playbacks: [{ playbackId, filmId, filmTitle, editionId, startedAt, updatedAt, report }] }`: the 20 with the newest `updatedAt`, newest first; `report` is the stored report without its `minutes` |
| `GET /api/admin/playback-health?since={iso}&minWatchedMs={n}&problems={bool}&page={p}&size={n}` | With **none** of these parameter names present, the answer above, unchanged (20 newest by `updatedAt`, no `total`). With any present (whatever its value): `{ playbacks: [...same rows...], total }`, ordered by `startedAt` desc then id desc. `since` (ISO instant) keeps sessions whose `startedAt` is at or after it; `minWatchedMs` (integer ≥ 0) those with `watchedMs` at or above it; `problems=true` only sessions with a problem and `problems=false` only those without (omit for both). A problem is a stall (`stallCount`), a reconnect (`reconnectCount`), a fall back to direct play (`fellBack`), a network error (`netErrors`) or a timeout (`timeouts`); a step down to a lower rung is not one (`HealthFigures.hasProblem`). `page` counts from 0 (default 0), `size` is 1 to 100 (default 20). Rows omit `minutes`. `total` counts every match, so a stored report that cannot be read is left out of `playbacks` but still counted. 400 `Invalid request` for a parameter that is present but empty or malformed (a bad `since`, or one outside the years 1970 to 9999, a non-number, a `problems` that is not exactly `true` or `false`), a negative `page` or `minWatchedMs`, a `size` outside 1 to 100, or a `page * size` above 2,147,483,647 (the offset asked of the database) |
| `GET /api/admin/playback-health/chart?since={iso}` | `{ sessions: [{ playbackId, filmId, filmTitle, startedAt, endedAt, watchedMs, stallCount, stallMs, startupMs, environment, rungMs, userAgent, touch, parts: [{ playbackId, startedAt, endedAt, watchedMs, environment }] }] }`: every **viewing** with `watchedMs` ≥ `FILM_SESSION_MIN_MS` (180,000, in `PlaybackHealthService` and `state/healthFormat.js`) whose first playback started at or after `since` (all when omitted), oldest first so the newest is last; no paging (the table holds at most 2,000 rows). A viewing is one or more playbacks joined by `ViewingJoiner` (a reload or a long pause opens a new `playbackId`): rows are read oldest first and a playback joins the newest viewing of its film on its device when it has the same `filmId`, the same non-empty `report.client.userAgent`, starts 0 to 6 hours after that viewing's last `updatedAt`, and picks up where it stopped (both `startPositionMs`/`endPositionMs` present: the new start within 2 minutes of the old end; else the same `environment` and `operator`); full rules and boundaries in `docs/engineering/joined-viewings-plan.md`. Joining reads every row of the window, the short ones included, and the 3-minute line applies to the joined total. `playbackId`, `startedAt`, `startupMs`, `environment`, `userAgent` and `touch` are the first part's; `endedAt` is the last part's `updatedAt`; `watchedMs`, `stallCount` and `stallMs` are sums and `rungMs` is summed per rung; `parts` lists every playback oldest first and always has at least one entry (a single playback is a one-part viewing). `startupMs` is null when the first frame never came; an unreadable row keeps `watched_ms` 0, so in practice it never reaches the chart (`rungMs` and `userAgent` are null only for a row that was readable once and no longer is; `touch` is `report.client.touch`, and the field is left out when the report has none, so an older row reads as it did). The literal path wins over `{playbackId}` (ids are UUIDs). On both routes the first value of a repeated parameter wins. 400 `Invalid request` for a bad, empty or out-of-range (outside 1970 to 9999) `since` |
| `GET /api/admin/playback-health/{playbackId}` | one such row with the whole report, `minutes` included (rows of seven figures, or five in an older record); 404 `No such playback` |
