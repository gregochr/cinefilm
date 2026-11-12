# Forced English subtitles — plan

Written 2026-11-11. Status (2026-11-12): **phase 1 merged (#105) and released (v0.4.0); phase 2a merged (#109),
2b in review; 2c built (PR in review); 2d and phases 3 to 6 not started.** Phases 5 and 6 were added on 2026-11-12: the owner says
solving subtitles is what would let them stop depending on Plex.

## What the owner wants

Only **forced English** subtitles: the lines a cinema audience saw on screen when characters speak another language.
For every film, now and whenever one is added:

1. **Decide** whether the film needs forced subtitles (it has foreign dialogue in the cinema release).
2. **Find** them: already beside the film, or downloaded.
3. **Check** they are the right lines and in sync with the speech. This includes the existing `.srt` files: over the
   years the owner has edited, merged, hand-made and downloaded them, so none is trusted just because it exists.
4. **Show** them automatically during playback when Subtitles is Off (handover decision 6).

The original discs are gone, so nothing can be ripped again. DVDFab handled subtitles badly; Subscene no longer
exists.

## What the library holds today (inventory, 2026-11-11)

Read-only ffprobe run over all 1,108 files on dockermacmini (`/mnt/workhorse-films`). Raw results were kept in the
planning session's scratchpad, not the repo.

| What | Count | Notes |
|---|---|---|
| Film files (`.m4v`) | 1,108 | 23.976 fps in every file sampled |
| Forced `.srt` files beside a film | 28 | `<stem>.en.forced.srt` (one `.forced.en.srt`); the sample was UTF-8 with a BOM |
| …that match a film by exact name (ignoring case) | 21 | includes `No Time to Die` → `No Time To Die.m4v` |
| …that do not | 7 | 3 LOTR theatrical (`.srt` has no `{edition-Theatrical Cut}` tag, and the film has two editions); 3 LOTR extended (one `.srt` for a film split into `pt1`/`pt2`; Fellowship's also says "The Fellowship"); see phase 1 |
| Files with embedded subtitle tracks | 61 | **All** `dvd_subtitle` (pictures), all `eng`, **none** flagged forced |
| …with a real forced track | 3 | LOTR theatrical cuts (3–41 lines). Each also has a `.srt`, so no OCR is needed |
| …with a full English track | ~25 | Harry Potter, Hunger Games, Maze Runner, Top Gun… (not forced; picture-based) |
| …whose tracks are all empty | ~33 | DVDFab placeholders with no packets (checked directly on `Volcano.m4v`) |

Picture-based tracks stay unsupported (`FfprobeParser.TEXT_SUBTITLES`).

## Design

### Model (Flyway, expand-only)

- **`subtitle_sidecar`**: one row per forced `.srt` file found on disk.
  `id, path (unique, relative to the library root), size_bytes, modified_at, presence (PRESENT | MISSING),
  media_file_id (nullable), owner_attached (bool), cues_text (normalised SRT, ≤ 1 MB), parse_report,
  offset_ms (default 0), sync_status, sync_detail, owner_verified_at, updated_at`.
  The row hangs off the **media file** whose stem it matches, not the edition. Files move between editions when the
  owner edits, joins or Fix Matches editions (`EditionArranger`, `EditionJoin`, `LibraryIngest.merge`), and a sidecar
  then moves with its file for free. A row with no `media_file_id` is **unmatched**.
- **`downloaded_subtitle`** (phase 4): edition-level, because a download is timed for the whole edition.
  `id, edition_id, opensubtitles_file_id, release_name, cues_text, offset_ms, sync_status, sync_detail,
  owner_verified_at, imdb_id (what it was fetched for), updated_at`.
- **`film.forced_need`** (`NEEDED | NOT_NEEDED | UNSURE`, null = not yet decided) and **`forced_need_source`**
  (`AUTO | OWNER`); a scan never overwrites the owner's choice. Phase 2.
- **`film.original_language`, `film.spoken_languages`** from TMDB. `TmdbModels.MovieDetails` does not parse either
  today, so it gains both fields. Phase 2.

Everything is stored in Postgres (a few KB per subtitle). `pg_dump` backs it up, and the read-only media mount is
never written.

**Why not reuse `subtitle_stream`:** those rows are updated in place by ffprobe index on every refresh
(`LibraryIngest.fill` deletes any index the probe did not report). Saved subtitle preferences hold their ids, and the
forced subtitle is never a menu choice (it shows when Subtitles is Off), so it needs no id in that space.

### Which forced subtitle an edition plays (precedence)

1. Sidecars: one whole-edition sidecar (no part tag) beats per-part sidecars. Per-part sidecars are stitched onto the
   edition timeline exactly as embedded tracks are today (`EditionTimeline.partStartMs`, `VttShifter.join`); a part
   with none contributes nothing.
2. An embedded **text** track flagged forced (none today; future rips might have one).
3. A download.

Two sidecars competing for the same slot (`X.en.forced.srt` and `X.forced.en.srt`) show as a **conflict** in Admin.
Neither plays until the owner picks one.

### Keeping it right when editions change

- Sidecars follow their media files, so editing, joining and Fix Match need no extra code for them.
- **Downloads:** when `EditionArranger` or `EditionJoin` deletes an edition (JPQL bulk delete,
  `EditionRepository.deleteByIds`), its downloads move to the surviving edition that receives its part 1 before the
  delete, in the same place that handles playback state today. A plain FK would make the delete fail, and
  `ON DELETE CASCADE` would silently throw away downloads that cost quota. Whenever an edition's set of parts changes,
  its forced subtitles' `sync_status` resets to `UNCHECKED` and `owner_verified_at` is cleared (the timeline moved).
- **`LibraryIngest.merge`** carries `forced_need` (owner's choice wins) the way `mergePreferences` carries
  preferences.
- **Fix Match to a different TMDB id:** an `AUTO` need resets to undecided, and downloads fetched for another IMDb id
  are marked `CHECK` ("fetched for a different film").

### API

- `FilmDetail.editions[].forcedSubtitle`: `{ source: "SIDECAR" | "EMBEDDED" | "DOWNLOAD", syncStatus, ownerVerified,
  version } | null`. `version` is the latest `updated_at` among its parts, so a nudge changes the URL.
- `GET /api/editions/{id}/forced-subtitles.vtt?v=<version>` → `text/vtt;charset=UTF-8`: the winning subtitle on the
  edition timeline, offset applied; 404 when there is none. Built per request (small and fast), no disk cache.
- Admin routes per phase. Every action takes a **row id** (sidecar, download, edition), never a path
  (SECURITY.md rule 3).

### Player

- `src/player`: the forced subtitle is a track with its own key (`"forced"`), not a stream. `activeSubtitleKey`
  returns `"forced"` when the choice is Off and the edition has `forcedSubtitle`. That beats the embedded-forced pick,
  because the API already chose the winner. `applySubtitleModes` (`Player.jsx` ~228) covers the extra track.
- `hasSubtitles` (`Player.jsx` ~180) becomes true when the edition has streams **or** a forced subtitle, so the
  captions button and the C key work for a sidecar-only film.
- Wording stays the current code's "forced if needed" (`tracks.js` `subtitleOptions`).
- **iPhone first.** The owner watches on an iPhone, often in native fullscreen on HLS, and the native player may
  ignore out-of-band `<track>` elements (unverified). Phase 1 starts with a spike: one hard-coded VTT on an HLS
  session on the owner's iPhone. If it does not show, the forced subtitle is also published in the HLS master
  playlist as an `EXT-X-MEDIA TYPE=SUBTITLES,LANGUAGE="en",FORCED=YES,AUTOSELECT=YES` rendition
  (`PlaylistWriter.master`), which iOS honours and a future Apple TV app wants anyway. Embedded tracks have the same
  gap today; it gets recorded either way.

### SRT handling (pure Java, no ffmpeg)

`SrtParser`:
- **Encoding:** BOM-detected UTF-8 or UTF-16, else valid UTF-8, else Windows-1252.
- **Line endings and times:** CRLF, LF or CR; comma or dot before the milliseconds.
- **Cues:** blocks with missing or wrong numbers are accepted. Cues out of order are sorted. Overlapping and
  zero-length cues are kept and counted. Malformed blocks are skipped and counted.
- **Markup:** tags are dropped (text kept).
- **Limits:** at most 1 MB and 5,000 cues.

Each file gets a **parse report** shown in Admin ("3 blocks skipped, 2 cues overlap, read as Windows-1252"). A file
with no cues is "unusable". Downloaded and hand-made text is untrusted (SECURITY.md rule 7): cue text is escaped when
written as WebVTT, and Admin renders it as text.

## Phases

Each phase is one PR, branched from the updated `main`, built by Sonnet under the CLAUDE.md process. Phase 1 stands
on its own; after it ships, measure (how many films are still unsure or missing) before committing to phases 3–4.

### Phase 1 — use the forced `.srt` files that already exist, and let the owner check them

**Status: built.** Deviations from the plan above, with the reason:

- **The iPhone/HLS spike was not run** (it needs the owner's phone) and the HLS rendition is not built; Phase 1 serves
  the `<track>` only.
- **`subtitle_sidecar` has no `sync_status` or `sync_detail`** (phase 3 adds them when something sets them). It has
  `whole_edition`, `cue_count`, `last_end_ms` (the end of the last cue, for the "ends after the film" flag without
  re-parsing) and `verified_parts` instead. `owner_verified_at` alone would not reset when editions change, so the
  check is `owner_verified_at` set **and** `verified_parts` equal to the edition's current part ids joined with commas.
  Nothing in `EditionArranger` or `EditionJoin` was touched: a file moved into or out of an edition changes the
  edition's parts, and that is the reset.
- **`media_file_id` is a plain column with a foreign key, not a JPA relation**, so a sidecar follows its file through
  `MediaFileRepository.moveToEdition` with no code of its own. Original files are never deleted (only renditions are),
  so the foreign key cannot block an edit.
- **A conflict in a whole-edition slot stops the edition playing any sidecar** (a whole-edition file is meant to beat
  per-part ones, so falling through would hide the conflict). A conflict in one part's slot leaves only that part with
  none; the other parts still play.
- **A nudge, an attach, and any change to a file on disk clear the owner's check** (the lines or timing the owner
  looked at are no longer the ones that play). `offset_ms` is kept when a file changes on disk.
- **Stitching** reuses `VttShifter.join` on one WebVTT per part (`VttWriter` applies the offset); a first part with no
  file is replaced by an empty header so the later part is still shifted.
- **A cue with no duration** is kept and counted, and shown for one second in the WebVTT (a browser drops a cue that
  ends when it starts).
- **A markup tag** is `<` followed by a letter or `/` and a letter, up to `>`, so "5 < 6" survives; ASS overrides
  (`{\an8}`) are dropped too.
- **"Detach"** (Admin: "Back to automatic") clears the owner's attachment and matches the file by name at once.
- **Admin's film search** uses `GET /api/films?q=` and `GET /api/films/{id}` (no new endpoint); the Player's `at`
  value is an edition time in milliseconds that starts playback exactly there (clamped to the runtime), instead of the
  resume point.
- **`GET /api/admin/subtitles`** lists every file with its `state` (`matched`, `unmatched`, `conflict`); Admin groups
  them as Unmatched, Conflicts, Missing (a `MISSING` file wherever it belongs) and Matched.

Gives forced subtitles to the 28 films that have them, with no external service.

- **Spike first:** the iPhone/HLS check above, which decides between `<track>` and `<track>` + HLS rendition.
- **Scanner:** the existing `visitFile` walk also collects files ending `.en.forced.srt` or `.forced.en.srt`
  (case-insensitive), with the same skips (hidden, `Plex Versions`, `._*`) and resolved through `LibraryRoot`. Nothing
  is collected under a folder the walk could not read (`Found.isUnder`), and the existing "library looks empty, stop"
  guard in `ScanService` covers sidecars too.
- **Sync rows:** an unchanged size and modified time skips re-reading. A file not found is marked `MISSING` (never
  deleted, so a brief SMB outage cannot lose the owner's attachments). A missing file that comes back is
  `PRESENT` again.
- **Matching** runs on every scan, after `groupLibrary`, for every sidecar. It does not wait for the film to change:
  unchanged films are skipped by the scan, and a `.srt` added later must still be found. Owner attachments are never
  re-matched. **Owner decision 2026-11-11: exact names only; anything else is attached in Admin.** A sidecar attaches
  by itself only when its path minus the `.en.forced.srt` / `.forced.en.srt` suffix equals a known original file's
  path minus its video extension, ignoring case, in the same folder (`Taken.en.forced.srt` → `Taken.m4v`,
  `X - pt1.en.forced.srt` → `X - pt1.m4v`, `No Time to Die.en.forced.srt` → `No Time To Die.m4v`). Several matches
  (`.m4v` and `.mkv` with one stem) → unmatched. Whole-edition files for split editions, names without the edition
  tag and near-misses stay **unmatched** for the owner to attach in Admin; automatic matching never sets
  `whole_edition` (only an owner's attach with no part on a split edition does).
- **Admin → Subtitles:**
  - Unmatched files, each with "Attach to…" (an edition and, for a split edition, whole or a part). That covers the
    7 LOTR files without renaming anything on the owner's disk.
  - Conflicts.
  - Each sidecar with its parse report and cues; "Play from here" opens the player 3 s before a cue. Buttons:
    nudge ±0.25 s / ±1 s, "Looks right" (sets `owner_verified_at`), "Reset timing".
  - The owner's own spot-check is the first proof of "right lines, in sync"; phases 2–3 add automatic checks.
- **Check against the database:** a sidecar whose last cue ends after the edition's runtime is flagged
  ("ends after the film: wrong film or cut?").
- **Playback:** route, DTO field and player changes above.
- **Tests:**
  - parser: every edge case above, including cp1252 and UTF-16 samples;
  - matching table: every rule, plus the 28 real file names from the inventory;
  - scan: idempotent, unreadable-folder skip, missing then present;
  - edit and join: sidecars follow files, sync resets;
  - route: 404s and `?v`;
  - player: the forced track shows only when Off, and the captions button works for a sidecar-only film.

### Phase 2 — does a film need forced subtitles, and do the existing files agree with OpenSubtitles?

No source says outright whether a film needs them. CineFilm combines signals and the owner settles the rest:

| Signal | Result |
|---|---|
| Owner set it | That, always |
| A forced subtitle exists (sidecar, embedded text forced track) | `NEEDED` |
| OpenSubtitles has English "foreign parts only" subtitles for the film's IMDb id | `NEEDED` (that flag is set by uploaders, so not proof; Admin shows the evidence) |
| TMDB lists English as the only spoken language | `NOT_NEEDED` |
| TMDB lists other spoken languages, nothing found | `UNSURE` |
| TMDB `original_language` is not English | `UNSURE`, marked "foreign-language film" (needs full subtitles: out of scope) |

- **TMDB:** fields stored from the movie details already fetched; existing films backfilled by a rate-limited job
  like the ratings refresh.
- **OpenSubtitles:**
  - Search runs after each scan for undecided or `UNSURE` films and is retried monthly, since subtitles get uploaded
    later.
  - Searches do not use download quota (to be confirmed).
  - Every API detail in this plan is **from memory and confirmed at the start of this phase**: `api.opensubtitles.com`
    v1, `Api-Key` and `User-Agent` headers, `/subtitles?imdb_id=&languages=en&foreign_parts_only=only`, `/login` for a
    token, `/download` returning a temporary link, quotas.
- **Checking existing sidecars against OpenSubtitles:**
  - One download per film within quota: the best "foreign parts only" match, compared with the sidecar.
  - The comparison looks at cue counts, and at the share of the sidecar's cues that have a cue within 2 s in the
    other once a single best offset is applied.
  - Under 70% overlap, or a cue count off by more than half, marks the sidecar `CHECK` with the reason
    ("OpenSubtitles has 41 lines, this file has 12"). This catches incomplete files and files for a different cut.
  - The best offset found is also a timing hint: shown in Admin as "OpenSubtitles' file is 1.2 s later", never applied
    automatically.
- **Admin → Subtitles:**
  - Filters: Needed and missing, Unsure, Needs checking, Verified.
  - Needed / Not needed buttons per film.
- **Security** (SECURITY.md is updated in this PR: a new outbound service):
  - The API key, username, password and session token are server-side secrets (rule 5).
  - The download link comes from a response, so it is fetched GET-only, from an allowlist of OpenSubtitles hosts,
    without following redirects off them, with a size cap enforced while reading (before any decompression).

#### Phase 2 build: four small PRs (planned 2026-11-12)

**API facts checked on 2026-11-12** (one live search from dockermacmini with the owner's key):
- `GET https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=936501&languages=en` with
  `Api-Key` and `User-Agent` headers answers 200 **without logging in**. Parameters are sent in alphabetical order and
  the IMDb id without `tt` or leading zeros (the API redirects otherwise).
- Rate limit: 5 requests a second (`ratelimit-*` headers). Responses carry `cache-control: max-age=86400`.
- Each result: `id`, and `attributes` with `foreign_parts_only`, `fps`, `download_count`, `from_trusted`,
  `hearing_impaired`, `machine_translated`, `ai_translated`, `release`, `upload_date`, `uploader.rank`,
  `feature_details.imdb_id`, and `files[].file_id` (what `/download` takes). Paged: `total_count`, `per_page` 50.
- *Taken* (tt0936501) returns 7 results, 23.976 to 25 fps. Login and `/download` are still unchecked (phase 2d).

**Model change from the design above:** the need is **worked out when read**, not stored. Only its inputs are stored:
the owner's choice, TMDB's languages and (2c) the OpenSubtitles evidence. A stored verdict would go stale whenever a
sidecar was attached or moved. So `film` gains `forced_need_owner` (`NEEDED | NOT_NEEDED`, null = automatic) instead
of `forced_need` + `forced_need_source`.

**The rule** (`ForcedNeed.decide`, pure, first match wins):

| # | Input | Need | Reason |
|---|---|---|---|
| 1 | Owner chose | that | `OWNER` |
| 2 | A `PRESENT` sidecar with at least one cue is attached to a `PRESENT` file of the film (a file with no cues is no evidence; a conflicting pair still is, though playback serves neither) | `NEEDED` | `SIDECAR` |
| 3 | A file of the film has an embedded forced track in English (`eng`, `en`) or with no language (null, `und`) | `NEEDED` | `EMBEDDED_FORCED` (pictures: cannot be shown yet) |
| 4 | (2c) OpenSubtitles has English foreign-parts-only results | `NEEDED` | `OPENSUBTITLES` |
| 5 | TMDB not asked yet, or no TMDB match | `UNKNOWN` | `NO_LANGUAGES` |
| 6 | `original_language` is not `en` | `UNSURE` | `FOREIGN_LANGUAGE_FILM` (needs full subtitles: out of scope) |
| 7 | Spoken languages are exactly English | `NOT_NEEDED` | `ENGLISH_ONLY` |
| 8 | Anything else (other spoken languages, or none listed) | `UNSURE` | `OTHER_LANGUAGES` / `NO_SPOKEN_LANGUAGES` |

TMDB's `xx` ("no language") is ignored in the spoken list and as the original language (a film whose original language is `xx` falls through to the spoken-language rules, not rule 6). `hasForcedSubtitle` (the Admin "has a forced subtitle"
filter, and `neededMissing`) is stricter than rule 2: at least one edition has a forced subtitle that playback would
serve (`ForcedSubtitleService.forEditions`, so `SidecarPrecedence` decides: empty files and conflicting files are not
served). Only the films with rule-2 evidence are evaluated.

**2a — backend: TMDB languages, the rule, the owner's choice** (no UI)
- `V14__forced_need.sql` (expand-only): `film.original_language varchar(8)`, `film.spoken_languages varchar(200)`
  (ISO 639-1 codes, lower case, comma-separated, TMDB's order, duplicates dropped, cut at a whole code to fit; empty string = TMDB lists none),
  `film.languages_fetched_at timestamptz` (null = not asked yet), `film.forced_need_owner varchar(16)` with a check
  constraint.
- `TmdbModels.MovieDetails` gains `originalLanguage` and `spokenLanguages` (`spoken_languages[].iso_639_1`). Keep the
  existing shorter constructors working. Codes are validated (`^[a-z]{2,3}$`) and anything else is dropped: TMDB text is
  untrusted.
- `LibraryIngest.applyMovie` stores both and stamps `languages_fetched_at` from the injected `Clock`. Every scan,
  Plex import and Fix match then keeps them current.
- **Backfill** (`LanguagesRefresher`, modelled on `RatingsRefresher`): films with a `tmdb_id` and no
  `languages_fetched_at`, one run at a time on its own executor, started after `ScanFinished`, `PlexImportFinished` and
  at start-up. Uses `TmdbApi.movie(id)` (paced by `TmdbClient`). Saves with one targeted update
  `... where id = :id and tmdb_id = :tmdbId` (a film rematched meanwhile is left for the next run). A TMDB outage stops
  the run; nothing is stamped, so it is retried. A film TMDB no longer has is stamped with null languages, so it is
  not asked again every scan (it shows `UNKNOWN`). No periodic refresh: languages do not change.
- **Read:** `GET /api/admin/forced-subtitles/films` → every film (sorted by sort title):
  `{filmId, title, year, need, reason, ownerChoice, originalLanguage, spokenLanguages[], hasForcedSubtitle}` and
  `counts {needed, neededMissing, unsure, unknown, notNeeded}`. Built from **three queries** (films with language
  columns; film ids with an attached present sidecar; film ids with an embedded English or language-less forced
  stream), plus one bounded fetch of the editions and files of the films with sidecar evidence and playback's own
  sidecar query (for `hasForcedSubtitle`), never one query per film (a test counts statements for 1 and 3 evidence
  films). 1,077 films is about 150 KB; no paging.
- **Write:** `PUT /api/admin/films/{id}/forced-need` `{choice: "NEEDED" | "NOT_NEEDED" | null}` → that film's row.
  Under `LibraryLock` (`LibraryLock.SUBTITLE`) in a `TransactionTemplate`, lock released after commit (409 while a
  scan holds it, 404 for an unknown film, 400 for another value), as in `SubtitleAdminService`.
- **Tests:** the rule table row by row, including `xx`, upper-case codes, empty lists and every precedence pair;
  TMDB parsing (missing, empty, malformed fields); `applyMovie` stamping; the refresher (outage stops it, a rematch
  during the call is not overwritten, a vanished film is stamped, one run at a time); the three-query read
  (`@DataJpaTest`, including a `MISSING` sidecar and an unmatched sidecar not counting); the controller contract;
  the `V14` migration in the integration suite. `foundation-plan.md` API section updated.

**2b — frontend: Admin → Subtitles, "Which films need them"**
- A section above the file list. Filters (radio chips): **Needed, none yet** (default), **Unsure**, **Unknown**,
  **Not needed**, **All**, each with its count.
- Each row: title (year), the need and a plain reason ("Forced subtitle file attached", "Forced track inside the file
  (pictures: can't be shown yet)", "Not in English originally: needs full subtitles", "English only (TMDB)", "Also
  spoken: French, Russian (TMDB)", "TMDB hasn't been asked yet"), and three buttons: **Needed**, **Not needed**,
  **Automatic** (the current one pressed, `aria-pressed`). The owner's choice is labelled "Your choice".
- Language names from `Intl.DisplayNames` (fallback: the code). Data and logic in `frontend/src/api` and
  `frontend/src/state`, no JSX there.
- A change applies the returned row and refreshes the counts. One request per film at a time; a newer click on the
  same film wins; a failed request restores the row and says so beside it. A refresh error shows with Try again even
  while the old list is shown.
- Shown 100 rows at a time with "Show more" (the All filter has 1,077 rows).

**2c — backend: OpenSubtitles search (evidence for rule 4)**
- `OpenSubtitlesClient` (search only; no login, no download): key from `OPENSUBTITLES_API_KEY`, `User-Agent`
  `CineFilm v<version>`, never logged. Paced under 5 a second, retries 429 with `Retry-After`, a timeout, and a
  response-size cap. Off when the key is absent (as OMDb is).
- `film.os_forced_count int`, `film.os_searched_at timestamptz`, `film.os_best json` (the top-ranked result's id,
  file id, release, fps, download count; ≤ 2 KB). Searched for films with an IMDb id whose need is not settled by rules
  1–3, never searched before or searched more than 30 days ago with no result, after each scan and daily.
- Rule 4 switches on. Admin rows show "OpenSubtitles: 7 foreign-parts-only files" (2c includes that small frontend
  change, or it rides with 2d's).
- `SECURITY.md` gains OpenSubtitles as an outbound service.
- **Built (2026-11-12)**, with these differences from the plan above:
  - Package `gregochr.cinefilm.opensubtitles`: `OpenSubtitlesApi`/`Client`/`Config`, `OpenSubtitlesParser` and
    `OpenSubtitlesRanking` (pure), `OpenSubtitlesRefresher` (same `pending` re-run flag as `LanguagesRefresher`, its own
    `openSubtitlesExecutor`) and `OpenSubtitlesScheduler` (06:00, off with `cinefilm.scan.schedule=false`).
    `RequestPacer` (tmdb) became public and takes an interval, so the client uses it at 250 ms.
  - **Every film with an IMDb id is searched**, not only those rules 1–3 leave open: phase 2d needs `os_best` for
    films that already have sidecars, and a search costs no download quota. Due: never searched, `os_searched_imdb_id`
    differs from `imdb_id`, or `os_forced_count = 0` and searched more than 30 days ago (never searched first, then the
    oldest).
  - `os_best` is `varchar(2000)` holding JSON `{id, fileId, release, fps, downloadCount, fromTrusted,
    hearingImpaired}` (not a `json` column); `film.os_searched_imdb_id` was added. A count only counts while
    `os_searched_imdb_id = imdb_id` (rule 4 and the DTO ignore a stale one), and a change of IMDb id resets all four
    columns (`Film.setImdbId` in the entity, `FilmRepository.clearOpenSubtitles` on the row, flushed after the id).
  - The reply is read from `attributes` (`files[0].file_id`, `feature_details.imdb_id`); `id` may be a number or a
    digit string (OpenSubtitles writes a string). A result with no usable id or file id is dropped. A reply that is not
    JSON or has no `data` list is an outage, never "no results". Only the first page (50) is read.
  - The DTO gained `openSubtitlesForcedCount` and `openSubtitlesSearchedAt`; `ScanStatus` gained
    `openSubtitlesLastSearchedAt`, `openSubtitlesRunning` and `openSubtitlesEnabled`. The Admin wording ("7
    foreign-parts-only files") is a frontend change for a later PR.
  - The User-Agent version is `cinefilm.version`, which is `${APP_VERSION:1}` (the image's build argument, so a release
    sends `CineFilm v0.4.1`; a dev build or an unusable value sends `CineFilm v1`).
  - The connect and read timeouts are 10 s each; the read timeout covers the wait for the response headers, as for
    TMDB and OMDb (the body read is capped in size, not in time).

**2d — the cross-check of existing sidecars** (login, `/download`, quota, comparison, Admin `CHECK` reason). Planned
in detail once 2c is merged and login and `/download` have been tried with one real download.

### Phase 3 — automatic timing check

- **Reference first, speech second:**
  - About 25 films have a full English track (pictures). Their cue **times** can be read without OCR (ffprobe packet
    times), turned into a timing-only reference, and the forced file aligned to it subtitle-to-subtitle. Hundreds of
    reference cues make this far more reliable than speech.
  - Where there is no full track, a downloaded full English `.srt` from OpenSubtitles (phase 2's search, one more
    download) can be the reference.
  - Speech alignment is the last resort: it cannot tell foreign speech from English, and with 3–41 cues it can lock
    on to the wrong lines.
- **Tool:** `alass` (subtitle-to-subtitle and subtitle-to-audio alignment).
  - To verify at the start of the phase: its arguments, `--no-split`, frame-rate guessing, and whether it accepts an
    audio-only file.
  - It runs on x86_64 (dockermacmini is x86_64); the release is pinned by version and **SHA-256** in the Dockerfile
    (rule 8).
  - CineFilm extracts the audio itself with ffmpeg (argument list, `-protocol_whitelist file`, rule 4) to a mono
    8 kHz WAV in the cache. Parts are concatenated for a split edition, and the WAV is deleted afterwards. alass never
    runs ffmpeg on the media.
  - alass writes a corrected file, so CineFilm fits one offset (no speed: every file is 23.976) from input versus
    output cue times.
- **Acceptance:** `ALIGNED` when the fitted offset is within ±10 s and the residual is small; otherwise `CHECK`. The
  original cue times are always kept, and only `offset_ms` changes, so "Reset timing" undoes it. Owner-verified
  subtitles are never re-aligned.
- **Running it:** one job at a time, `nice`, a 10-minute timeout, and only while no HLS session is encoding
  (playback comes first).

### Phase 4 — download what is missing

- For each `NEEDED` edition with no forced subtitle, search OpenSubtitles (IMDb id, English, foreign parts only).
  Ranking:
  1. frame rate 23.976;
  2. a release name close to the edition (theatrical or extended);
  3. trusted uploader;
  4. download count.

  The hash match is skipped: DVDFab re-encodes will not match.
- Downloads go through a queue within the daily quota, and the rest carries to the next day. Each is stored and
  aligned at once (phase 3). A `CHECK` result tries the next candidate (at most 3). Admin shows the candidates, so the
  owner can pick another or remove a download.
- A sidecar always wins: if the owner later drops a `.srt` beside the film, it replaces the download for playback.
- **Every new film** then runs the whole chain on the scan that finds it:
  1. match a sidecar (phase 1);
  2. decide the need and cross-check (phase 2);
  3. download if needed (phase 4);
  4. align (phase 3);
  5. wait in Admin for the owner's spot-check.

### Phase 5 — full English subtitles for foreign-language films

Forced subtitles cover English-language films with some foreign dialogue. A film made in another language needs
**full** English subtitles from start to finish, which phases 1–4 deliberately leave out (rule 6 marks it
`FOREIGN_LANGUAGE_FILM`). Plex handles these today; CineFilm must too before the owner can leave Plex.

- **Which films:** rule 6's films (TMDB `original_language` not English), plus any the owner marks. Admin lists them
  with the same filters as phase 2b ("Full subtitles: needed, none yet").
- **The audio in the owner's copy decides, not only TMDB.** A survey on 2026-11-12 found 19 of 1,074 films with a
  non-English original language, and several are English for the owner:
  - *Léon* is mostly English (TMDB says French because it is a French production);
  - the three Leone westerns, *Crouching Tiger* and *Ballerina* have English-labelled audio;
  - the animated films are probably English dubs.

  So a film whose playing audio track is English is not a foreign-language film. Most files carry no audio language,
  so those stay `UNSURE` for the owner's one tap. The likely real list: *Oldboy*, the two Millennium films,
  *Martyrs*, *The Assassin*, the Bruce Lee films, *33 Days*.
- **Scale of the forced-subtitle `UNSURE` pile:** the same survey found 378 English-language films that list other
  spoken languages. Phase 2c's OpenSubtitles evidence has to settle most of these, so the owner isn't left with 378
  taps.
- **Find:**
  - Full English `.srt` files beside the film (`<stem>.en.srt`, `<stem>.eng.srt`) are matched as in phase 1, by exact
    name.
  - Otherwise OpenSubtitles is searched without `foreign_parts_only` and the download goes through phase 4's queue and
    ranking (not hearing-impaired by default).
- **Check:** phase 3's alignment. Hundreds of lines make speech alignment far more reliable than for forced files.
  Phase 2d's comparison against a second OpenSubtitles file catches the wrong cut.
- **Play:** a normal choice in the player's Subtitles menu ("English"), on by default for these films, never shown
  when Subtitles is Off. The forced track still plays when it is Off. The saved subtitle preference keeps working.
- **Model:** phase 4's `downloaded_subtitle` and phase 1's `subtitle_sidecar` gain a `kind` (`FORCED | FULL`) rather
  than new tables.
- **To decide at the start of the phase:** whether full subtitles are also wanted for English-language films (for
  hard-to-hear dialogue). Out of scope unless the owner asks.

### Phase 6 — subtitles stored as pictures inside the file

About 60 rips carry their subtitle tracks as pictures (`dvd_subtitle`, DVD-style bitmaps), not text: about 25 full
English tracks, 3 real forced tracks (the LOTR theatrical cuts) and about 33 empty placeholders. Browsers cannot
display them; Plex burns them into the video while transcoding.

Two ways, to be settled by a spike at the start of the phase:
- **A. Convert once to text (recommended).** OCR each picture track to `.srt` (ffmpeg extracts the bitmaps; an OCR
  tool turns them into text), store it like a downloaded subtitle, and check it with phases 2d and 3.
  - Pros: it plays everywhere, including iPhone native fullscreen and direct play, at no cost per playback.
  - The OCR output is also the timing reference phase 3 wants.
  - Risk: OCR errors (italics, `l`/`I`). Mitigation: comparison with an OpenSubtitles file and the owner's spot-check
    in Admin.
- **B. Burn in during transcoding (fallback).** An ffmpeg overlay in the HLS encode.
  - Only works when transcoding, so it forces a transcode even when the file could direct-play.
  - It costs CPU on every playback and cannot be switched off mid-stream.

Empty placeholder tracks are detected (no packets, or no bitmaps after OCR) and hidden. Phase 6 may shrink a lot if
phases 4–5 find text versions for most of these films: measure first.

## Owner decisions needed

1. ~~OpenSubtitles account and API key~~ **Done (2026-11-11).** `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`
   and `OPENSUBTITLES_PASSWORD` are in dockermacmini's `.env`. The key has `dev_mode` on: OpenSubtitles' pricing page
   allows up to **100 downloads a day** in dev mode (5 a day on the free API package, 2,000 a day on "Light", $20 a
   month). Dev mode is described as for testing, so CineFilm must not depend on it lasting: it reads the remaining
   allowance from the API and queues the rest for the next day. At 100 a day the back catalogue (downloads only for
   films that need them, plus one per existing sidecar for the cross-check) should take a few days, with no purchase.
2. **Unverified downloads during playback:** recommended is to show an automatically `ALIGNED` download straight
   away (Admin flags it as not yet verified). The alternative is to show it only after the owner taps "Looks right".
3. **Unmatched LOTR files:** attach them in Admin (the plan's default; nothing on disk changes), or rename them on
   workhorse to match the film files.

## Risks

- **Few-line alignment** can lock on to the wrong speech. Mitigations: a subtitle reference first, offset-only
  fitting, tight acceptance, the owner's spot-check, and original times always kept.
- **Wrong edition** (a theatrical `.srt` on an extended cut): the runtime check, the OpenSubtitles comparison and the
  alignment residual each catch it.
- **OpenSubtitles terms, quotas or availability change** (as Subscene's did). Phase 1 does not depend on it, and
  downloaded subtitles are stored in CineFilm, so they outlive the service.
- **Mac mini resources:** alignment decodes a film's audio (minutes of CPU). It runs one at a time, niced, and waits
  for transcoding.
- **iPhone native fullscreen** may need the HLS rendition; the phase 1 spike settles it before anything else is
  built.
