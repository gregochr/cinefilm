# Forced English subtitles — plan

Written 2026-11-11. Status: **draft, reviewed once (adversarial review against the code, findings folded in), nothing
built.**

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
| …that match a film by name (group key, which ignores case) | 21 | includes `No Time to Die` → `No Time To Die.m4v` |
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
  re-matched. Otherwise strip the suffix, parse the stem with `FileNameParser`, and match the media file with the same
  group key and part tag:
  - stem with a part tag (`- pt1`) → that part's file;
  - stem without one, for an edition of one file → that file;
  - stem without one, for a split edition (the group key matches, the files carry part tags) → a whole-edition
    sidecar, stored against part 1 and flagged `whole_edition`, so it is served on the edition timeline as it is;
  - stem without an edition tag when the film has several editions, or no match → **unmatched**.
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
