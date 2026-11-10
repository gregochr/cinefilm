# Split films (pt1/pt2), self-grouping editions, the different-film guard, Edit editions — plan

Written 2026-11-09 from the owner's brief and two addenda; revised the same day after an adversarial review. A film
split across several files must appear, play and be tracked as **one film, one edition, one continuous timeline**;
parts are never visible in browse or the player. Files tagged the Plex way group themselves into editions; files that
are different films are never grouped; only genuine ambiguity reaches Admin, where **Fix match** and **Edit
editions** settle it. **Join parts is deferred by the owner (2026-11-09)** and not built.

## What exists today (read before changing anything)

- **Scanner** (`LibraryScanner`, `ScanService`, `LibraryIngest`, `TitleGuess`): every file becomes its own `Edition`
  named `"Original"`. The title comes from the embedded `title` tag if present, else the file name minus extension and
  a trailing `(year)`. No `{edition-…}`, part or id tags are read. `addNew` folds any file whose TMDB match is already
  a film into that film; `applyOutcome` (pending retry) merges the same way; unchanged files return early in `handle`.
- **Model**: `Film 1—n Edition 1—n MediaFile`; `Edition.mediaFiles` is `cascade=ALL, orphanRemoval=true`;
  `playback_state.edition_id` is an FK with no cascade and `PlaybackState` is not a mapped child.
  `Edition.originalFile()` is the first `ORIGINAL` file and every reader assumes one. `MediaFile.durationMs` exists
  (ffprobe `format.duration`). `Edition.runtimeMs` is the single file's duration. `EditionRepository.moveOne/moveAll`
  and `LibraryIngest.merge` clear the persistence context. `LibraryIngest` documents "never deletes an edition" — this
  plan changes that invariant (absorbed editions are deleted after their state is folded); update the Javadoc.
- **Plex import** pairs Plex part paths with files the scan created, flattens every `Media → Part` into one list,
  merges every film sharing the item's TMDB id (`PlexFilmWriter.apply`, no title check), applies `editionTitle` to all
  paired editions. Plex's `viewOffset` is a whole-item (global) position.
- **Playback** (`transcoding-plan.md`): HLS for everything, per-file keyframe index → `CutList`, one ffmpeg run per
  session, restart at a cut on far seeks, `-copyts`, `-output_ts_offset 10`, relative `-segment_times`. `SessionService
  .create` validates `startMs` and builds the ladder from the single original. Direct play is the fallback. Subtitles
  per file at `/api/media-files/{id}/subtitles/{index}.vtt`. Resume/watched per edition (`PlaybackService`, 92% or
  credits marker of `runtimeMs`).
- **Admin**: attention flags `multiple-files` for any film with more than one present original; `needs-match` has no
  action. **Fix match does not exist** (handover only). No per-film Admin page.
- **Not built at all**: chapters, scrub-thumbnail sprites, pre-made travel versions. Nothing to shift now; when built
  they must go through `EditionTimeline`. Say so in the PRs.
- **The media mount is read-only on purpose** (SECURITY.md rule 3) — one reason Join parts is deferred.

## Phases and PRs

One PR per phase, each from updated `main`; Sonnet builds; planner reviews; CI + Codex gate; squash-merge.
**No release between phases 2 and 6**: phase 2 creates multi-part editions that play only part 1 until phase 4, and
"Unmatched" films that need Fix match (phase 5). Tell the owner in the phase-2 PR (the owner tags releases).

| # | Commit | Content |
|---|---|---|
| 1 | `feat(model)` | migration, `EditionTimeline`, `PartsMatch`, `FileNameParser`, multi-part-aware `Edition` |
| 2 | `feat(scan)` | the guard, grouping new files, auto-resolve, library repair, default edition, report |
| 3 | `feat(plex)` | every Part of every Media in order, guard on import, Plex edition title/order |
| 4 | `feat(playback)` | spike, one HLS timeline across parts, subtitles, sessions, Film detail line |
| 5 | `feat(admin)` | attention rules, Unmatched + Fix match, Parts don't match, grouping report |
| 6 | `feat(admin)` | Edit editions panel (Admin + Film detail) |

## Shared rules (every phase that groups follows these)

### Facts come from the path only

`FileNameParser` (pure) parses the path relative to the library root. Nothing probe-derived (embedded title, date tag)
feeds grouping, so facts can be recomputed for every known file at any time (moved files included). The grouper, the
guard and Admin compute them on the fly; nothing about tags is stored.

Parse order on the file stem (after stripping trailing video extensions repeatedly, `m4v|mp4|mov|avi|mkv`, so
`pt2.m4v.m4v` → `pt2`):

1. Id tags `{tmdb-N}`, `{imdb-ttN}` → `tmdbId`, `imdbId`; removed.
2. Edition tag `\{edition-([^}]{1,})\}` (case-insensitive) in the file name and/or the immediate folder name; name cut
   to 32. Same or only one ⇒ that name; different, or two different in one name ⇒ the file's, and `tagConflict`.
3. Quality tokens removed as whole separated tokens after the first word: `HD SD UHD 4K 2160p 1080p 1080i 720p 576p
   480p HDR HDR10 BluRay Blu-ray BRRip BDRip WEB-DL WEBRip DVDRip Remux x264 x265 HEVC H264 H.264 AAC AC3 DTS`.
4. Year: `(1999)` / `[1999]` anywhere; a **bare** 4-digit year token (1888–2100) counts only in scene-style names
   (no spaces in the stem): the **last** such token that is not the first — it and **everything after it** go
   (`Rocky.IV.1985.1080p.BluRay.x264-SPARKS` → "Rocky IV", 1985; `Blade.Runner.2049.2017.1080p` → "Blade Runner
   2049", 2017). In names with spaces a bare number is title ("Blade Runner 2049", "2001 A Space Odyssey", "1917").
   A part suffix after the year is still read.
   Folder `Title (Year)` supplies the year when the file has none.
5. Part suffix at the end: `[ ._-]+(?:(?:pt|cd|disc|disk|dvd)[ ._-]?|part)([1-8])$` (case-insensitive): "- pt1",
   ".part2", " cd1", "pt.1", "cd-1", "_disc 2". "Part" **with a space** ("Deathly Hallows - Part 2") is a title, not
   a part (owner: "Part 2" in the title is a sequel marker).
6. Trailing separators trimmed; dots/underscores read as spaces when there are no spaces (as `TitleGuess.tidy`).

Outputs: `title` (display), `year`, `editionTag`, `partTag`, `tagConflict`, ids, and:

- `comparisonTitle`: the title with known plain edition words also removed (`Director's Cut`, `Directors Cut`,
  `Extended`, `Extended Edition`, `Extended Cut`, `Theatrical`, `Theatrical Cut`, `Unrated`, `Final Cut`, `Special
  Edition`, `Remastered`, `IMAX`, `Uncut`), Unicode-normalised (NFD, accents dropped), lower case, runs of
  non-letters/digits → one space, and **standalone roman numerals II–XX converted to numbers** ("Rocky IV" ≡ "Rocky 4";
  "Rocky IV" ≠ "Rocky V"; "V for Vendetta" maps consistently). Sequel markers (numerals, trailing numbers, "Vol. N",
  "Part N" as words) are never removed.
- `plainEdition`: the edition word removed in `comparisonTitle`, if any (used as an edition name when untagged).
- `groupKey` = folder | comparisonTitle | year | lower-cased editionTag.

### The different-film guard

Two files may be grouped (editions or parts of one film) **only if their `comparisonTitle`s are identical**. Applies
to: `LibraryIngest.addNew` TMDB-id folding, `LibraryIngest.applyOutcome` (pending retry) merging, `PlexFilmWriter`
merging of same-TMDB films and pairing, and the auto-resolve joins. A guarded fold that is refused leaves the file in
its own film with `NEEDS_MATCH` and the TMDB hit as its candidate ("Unmatched"). This also stops the
split → pending → re-merge flip-flop.

- **Exempt**: films the owner settled (`film.editions_confirmed`, set by Fix match and Edit editions).
- **Runtime signal**: untagged same-title files (not parts) whose runtimes differ by more than 15% are flagged
  "Runtimes differ by 22% — check these are the same film". The signal only flags: once titles are identical, the
  versions stay editions of one film (untagged theatrical and extended cuts routinely differ by more than 15%).

### Library repair (runs inside the scan, after the walk and **before** `retryPending`; also after a Plex import)

Only on **strong evidence**, only in non-confirmed films. In a film whose originals have different comparison titles:

- **Split** when exactly one same-title group's comparison title equals the film's own (normalised the same way) and
  every other group differs from it by a sequel marker (number/numeral/Vol/Part N token difference) or by its own
  `{tmdb-}` tag. Those other editions move whole (bulk update, state and edition id unchanged) to a new `PENDING`
  film titled from the file, which `retryPending` then matches (file name and year); unconfident ⇒ `NEEDS_MATCH`.
  A multi-part edition whose parts' titles differ is first unfolded (state rule below).
- **Otherwise flag**, never guess: "Files look like different films" in attention (no tie-break picks).
- Idempotent: a second scan over the same disk writes no `grouping_change` rows (tested).

### Joining editions (scan auto-resolve, Plex import, Edit editions) — one operation, `EditionJoin`

Order matters (persistence context is cleared by bulk operations):

1. Merge films first (`LibraryIngest.merge`).
2. Move files with a bulk JPQL update (`update MediaFile m set m.edition = :target where m.id in :ids`,
   flush + clear automatically), set `partNumber`s.
3. Reload; recompute runtime.
4. Fold playback state onto the survivor (below) and save it.
5. Delete the absorbed editions' `PlaybackState` rows, then the absorbed editions by id.
6. Repoint `FilmPreference.defaultEditionId/lastEditionId`; remap `audioStreamId`/`subtitle` to the survivor's part-1
   stream with the same language and forced flag, else the same rank, else clear.

File ids, stream ids and `media_keyframes` rows survive a join (repository test).

**State folding** (`EditionTimeline`): an absorbed part's position becomes `partStart + position`. The survivor's
state is the one with the latest `lastPlayedAt`. **Watched only if the edition holding the last part was watched** (or
the survivor already covered all parts); a watched non-last part becomes a resume position at the start of the next
part. Credits marker: the last part's marker shifted by its part start, else null. **Unfolding** (repair or Plex
split): `locate()` the global position into the part's edition; watched goes to the last part's edition.

A group with a part lacking a duration (unreadable, not probed) is not joined until it has one.

### Default edition

Only when the owner has not chosen one (`film_preference.default_edition_chosen = false`; true once the owner picks
on Film detail or in Edit editions): Plex's order (phase 3), else an untagged edition or one whose name contains
"theatrical", else the longest runtime.

### Concurrency

Grouping runs under the scan/import `LibraryLock`. Fix match and Edit editions saves take the same lock and answer
409 "A scan is running" when they can't. Progress reports for an edition deleted by a join answer 404 and the player
ignores them.

## Phase 1 — model

Migration `V9__split_films.sql` (expand-only):

- `media_file.part_number int not null default 1`; index `media_file(edition_id, kind, part_number)` (not unique;
  uniqueness enforced in code and tested). Map as `int`/`Integer` (`ddl-auto=validate`; no `smallint`).
- `film.editions_confirmed boolean not null default false`.
- `edition.grouping_source varchar(10) not null default 'AUTO'` (`AUTO`, `PLEX`, `OWNER`): the scan's auto-resolve
  only joins `AUTO` editions; the Plex import may split an `AUTO` join it disagrees with; nothing but the owner
  changes `OWNER`.
- `film_preference.default_edition_chosen boolean not null default false`.
- `grouping_change` (`id`, `happened_at`, `kind` `split|join|flag`, `summary varchar(500)`).

Code:

- **`EditionTimeline`** (film package, pure): from ordered part durations (each > 0): `totalMs()`, `partCount()`,
  `partStartMs(i)`, `locate(globalMs) → Position(partIndex, offsetMs)`, `toGlobal(partIndex, offsetMs)`. Negative
  clamps to 0; a time exactly on a join belongs to the later part at offset 0; ≥ total → last part at its full
  duration; `toGlobal` rejects an offset outside its part. `of(Edition)` uses `originalParts()`. Single part ⇒
  identity.
- **`PartsMatch.check(List<MediaFile>)`** → `Result(matches, differences)`: codec, width, height, pixel format; audio
  streams in order (count, codec, channels, language); subtitle streams in order (count, format, language). Phase 4
  may add frame rate, profile/level, sample rate after the spike.
- **`FileNameParser`** with every rule above (pure; in phase 1 so phases 2, 3, 5 and 6 share it).
- **`Edition`**: `originalParts()`, `partCount()`, `originalFile()` = part 1 (audit every caller: `FilmMapper`,
  `AdminService`, `StreamService`, `SessionSources`, `PreferencesService`), `hiddenFromBrowse()` when any part is
  missing, not playable or has no duration, `recomputeRuntime()` = sum of parts. `LibraryIngest.refresh` uses it.
- Tests: `EditionTimeline` boundaries/clamps/identity/invalid; `PartsMatch` each difference; `Edition` helpers;
  `FileNameParser`: the brief's Two Towers pt1 / `pt2.m4v.m4v`, cd1/cd2, `.part1`, no-edition split files, an
  edition with no parts, an unrelated file in the same folder, folder-only tag, conflicting tags, ids, 33-char
  edition, "Apollo 13", scene names, Rocky IV ≡ Rocky 4 ≠ Rocky V, Robin Hood ≡ Robin Hood - HD, Karate Kid ≠ Karate
  Kid 2, "Title - pt2" is a part, Kill Bill Vol. 1 ≠ Vol. 2, "Deathly Hallows - Part 1" ≠ "- Part 2", "Alien" ≡
  "Alien Director's Cut", "Léon" ≡ "Leon". Migration proven in CI; boot `dev` locally.

## Phase 2 — scanning, auto-resolve, repair, report

- **New file**: if an `AUTO` edition of a non-confirmed film has an original with the same `groupKey`, **every**
  original of that edition is part-tagged, and the new file's part number is free ⇒ added as a part (no TMDB lookup).
  Otherwise a new film + edition: name = edition tag, else `plainEdition`, else `"Original"`; `partNumber` = part tag
  or 1. `{tmdb-N}` fetches TMDB movie N directly; `{imdb-tt…}` uses TMDB `/find` (add to `TmdbApi`). Part-tagged
  files search with duration 0. TMDB-id folding goes through the guard.
- **Auto-resolve** (`EditionGrouper`, end of scan after repair, and after Plex import): for non-confirmed films and
  `AUTO` editions, group known originals by `groupKey` across editions and films; a group whose files are all
  part-tagged with numbers 1..n and no duplicates is joined into one edition (survivor: the lowest part's edition).
  Untagged and tagged files of one key are not joined. Same folder + comparison title + year with different edition
  tags ⇒ one film, several editions (Gladiator's two cuts are one tile even with TMDB down).
- Repair, default edition and `grouping_change` rows as in Shared rules; Admin shows them in phase 5 (logged now).
- Tests: Gladiator (Extended Cut + Theatrical Cut) → one film, two editions, default Theatrical; Two Towers (Extended
  pt1 + pt2 + Theatrical Cut) → one film, Extended with 2 ordered parts, runtime = sum; existing rows where pt1 and
  pt2 are separate films (one matched, one NEEDS_MATCH) → joined; part 1 watched + part 2 unwatched ⇒ not watched,
  resume at part 2's start; part 2's resume becomes global; prefs repointed and streams remapped; confirmed film
  untouched; pt1 + pt1 duplicate not joined; untagged + pt2 not joined; Rocky IV / Rocky V split and not re-merged on
  the next scan (idempotency); "Alien" + "Alien Director's Cut" not split; TMDB-id fold refused for different titles.

## Phase 3 — Plex import

- `PlexFilm.files` → `media: List<PlexMedia(List<String> parts)>`, Part order and Media order kept; every Part of
  every Media is paired.
- Guard first: files whose comparison titles differ from the item's are split off (kept: those equal to Plex's title,
  else those sharing the first Media's first Part's title); split-off files are not paired with this item, stay or
  become their own films and are matched on their own; unconfident ⇒ Unmatched. Plex's metadata and watch state
  apply to the kept files only. Same-TMDB film merging goes through the guard.
- For a non-confirmed film, a Media whose **every** Part paired is joined (`EditionJoin`) into one edition in Plex's
  order, `grouping_source = PLEX`; an `AUTO` join that disagrees with Plex is unfolded first. A partly paired Media is
  not joined and is reported. Each Media is its own edition named by `editionTitle` (cut to 32); two Media with the
  same name get "Original" / "Original 2" (the Edit editions unique-name rule must hold). Edition ids are recomputed
  after joins before watch state is written. Untagged Media more than 15% apart in runtime stay separate editions and are flagged.
- `viewOffset` is global: merged into the joined edition against the summed runtime.
- Default edition from Plex: when several items share a TMDB id, the first item in Plex's section listing.
- After the import, repair and auto-resolve run over the library.
- Tests: two Media × two Parts → two editions with ordered parts; Parts out of path order keep Plex's order; Rocky IV
  item with "Rocky V.m4v" split off; partly paired Media; duplicate names; resume on a joined edition; confirmed film
  not regrouped.

## Phase 4 — playback across parts

- **Spike first** (recorded in the PR, like the transcoding spike): two real parts on dockermacmini, copy and
  transcoded rungs, per-part runs with `-output_ts_offset 10+P`; ffprobe PTS both sides of the join; confirm
  `-force_key_frames` stays in input time; check part 2's `start_time`, and part 1's audio vs video end. Decide
  whether `partStart` uses `format.duration` or the video stream's end (store per file if so). Owner plays through a
  join in Safari (Mac, iPhone) and Chrome.
- **Timeline**: `CutList.join(List<CutList>)` → global cuts (each part's shifted by `EditionTimeline.partStartMs`),
  a forced boundary at each part start, plus `partStartSegments`.
- **Playlists**: `#EXT-X-DISCONTINUITY` before the first segment of every later part, at the same index in every rung.
- **`SessionService.create`**: validate `startMs` against `EditionTimeline.totalMs`; start segment from the joined
  list; height = smallest part's; copy rung only if `PartsMatch` passes and every part's index is `copy_ok`;
  hardware decode per part. Index part 1 synchronously, later parts in the background (the index job pre-indexes at
  scan anyway); a part not indexed in time ⇒ the usual 503 retry.
- **Audio/subtitle mapping per part**: the chosen part-1 stream's language + forced flag, else rank, else default.
- **Encoder runs are per part**: `TranscodePlan` carries the part's local cut list, its first global segment and its
  start; local times for `-force_key_frames`/`-segment_times`; `-segment_start_number <global n>`;
  `-output_ts_offset <10 + partStart>`.
- **`SessionManager`**: per-session finished segment ranges (not just the current run); `advance`/`cleanPartial`
  bounded by the run's part; a clean exit before the last part chains into the next part (unless too far ahead); a
  request in another part restarts there; a request for the next part's first segments while the run is near its end
  waits for the chain. Fake-encoder tests: chain at a join; request part 1's last segment just after the chain
  starts; seek across a join; kill during part 1 with part-2 segments on disk; crash in part 2; look-ahead across.
- **Subtitles**: `GET /api/editions/{id}/subtitles/{streamIndex}.vtt` (part 1's index = the logical stream); each
  part's mapped stream extracted (existing cache), cues shifted by the part start, concatenated. Pure VTT shifter
  tests (`hh:mm:ss.mmm`, `mm:ss.mmm`, cue settings). The player uses the edition URL for every film. Old route stays.
- **Direct play**: `StreamService` answers 409 for multi-part; `EditionDto.parts`; `original` available on a
  multi-part edition only with a copy rung; the player never direct-plays one.
- **Resume/watched/±10 s/scrubber** then work in edition time. Tests: start in part 2, progress after the join,
  watched at 92% of the summed runtime, credits marker in part 2.
- **Film detail** file line: `Extended Edition · 2 parts · 3h 55m · 1080p HEVC` (single-part unchanged). No part
  numbers in browse, Film detail or the player. `RouteSecurityDriftTest` updated.

### Spike results (2026-11-10)

Run on the real server with Fellowship Extended pt1 and pt2 (both H.264 1920×800 23.976, AAC 2ch and AC-3 5.1, both
`start_time` 0), using the existing per-part command shape (`-copyts`, `-noaccurate_seek -ss`, copy and libx264 rungs,
`-output_ts_offset`, `-segment_start_number`). Phase 4 is built to these:

1. **The video is seamless at the join on both rungs when `partStart` is part 1's _video stream_ duration** (part 1's
   last PTS is 6353.4204 and part 2's first is 6353.4204 with offset 10). `-output_ts_offset 10 + partStart` works as
   planned.
2. **`-force_key_frames` and `-segment_times` stay in input (local) time under `-copyts`**, so each part is given its
   own local times; only the output (timestamp offset, segment numbers) moves onto the film's timeline.
3. **`partStart` must come from the video stream's duration, not `format.duration`.** Part 2's `format.duration` is
   7354.432 (the audio); its video is 7354.347. Stored as `media_file.video_duration_ms` (below).
4. **There is an audio gap**: part 1's AAC ends 1.04 s before its video. Audio is always re-encoded (even on the copy
   rung), so every non-last part pads it with `-af apad -shortest`: the audio runs on as silence and the output ends
   with the picture, so audio and video end together and no drift builds up across joins. The last part is unchanged.
5. **Pitfall: never bound a per-part run with `-t`/`-to`.** Under `-copyts` an output `-t` compares with input-time
   PTS and produced an empty output; every part runs to its natural end (a command-builder test pins it).
6. Both parts pass `PartsMatch`, so the copy rung is allowed for such editions.

The padding command (`-af apad -shortest`) was not itself run in the spike; it is the idiom for "audio to the end of the
video", and the owner's play-through of a join in Safari (Mac, iPhone) and Chrome is its proof.

### As built

- **Video duration.** `media_file.video_duration_ms` (migration `V10`, expand-only, nullable). `FfprobeParser` records the
  first real video stream's `duration`; `LibraryIngest.fill` stores it (0 when ffprobe gave none, so "probed, none" is
  told from "never probed"), and the scan re-probes a file whose value is still NULL once (like the pixel format), so
  existing rows fill on the next scan. `MediaFile.timelineDurationMs()` is the video duration when above 0, else the
  container's; `EditionTimeline.of(Edition)`, a part's keyframe index and `Edition.recomputeRuntime` (for several parts
  only) use it, so the runtime, the playlist and the player agree. A film in one piece keeps using the container
  duration everywhere, exactly as before.
- **Timeline.** `CutList.join` shifts each part's cuts by the sum of the earlier parts' timeline durations and cuts at
  every part start; segment numbers run on across parts. The joined list knows its parts (`part(i)`, `partStartSegment`,
  `partOfSegment`, `lastSegmentOfPart`). Every rung's playlist carries `#EXT-X-DISCONTINUITY` before each later part.
- **Sessions.** `SessionSources` reads every part (all must be playable and readable); `SessionService` indexes part 1
  now and later parts in the background for up to 8 s, then answers 503 (`Retry-After: 1`); the ladder uses the
  smallest part's picture; the copy rung needs matching parts and a copy-ok joined list; audio for a later part is the
  stream with part 1's chosen language (the same rank among those), else the same rank, else its default.
- **Encoder runs** are per part (`TranscodePlan.partStartMs/firstSegment/padAudio`). `SessionManager`: a run covers one
  part; a clean exit before the last part chains into the next unless the player is more than 3 minutes behind it (the
  sweep or the player's request starts it later); `finishedFrom` per part replaces "the run finished" for a part's last
  segment (the next file's first segment may exist from an earlier run); restarts and clean-ups touch only the run's
  part (`SegmentFiles.deleteRange`).
- **Subtitles.** `GET /api/editions/{id}/subtitles/{streamIndex}.vtt` (`EditionSubtitleService`, `VttShifter`,
  `PartSubtitles`); the player uses it for every film.
- **Direct play** answers 409 for several files; `EditionDto.parts`; `original` is available for such an edition only with
  a copy rung and an encoder. The frontend never direct-plays one: where a film in one piece falls back it shows "This
  film can't be played right now" with Try again, skips the direct-stream probe, and asks again on a 503 from the
  session request (up to 15 times, a second apart).

## Phase 5 — Admin: attention, Unmatched + Fix match, report

- **Attention**: `multiple-files` ("Several files") only for genuine ambiguity in a non-confirmed film: two or more
  untagged editions (untagged duplicates; runtime difference > 15% stated), a `tagConflict`, duplicate part numbers,
  a part gap (pt1 + pt3; pt2 alone), or "Files look like different films". Detail says which. Gladiator and Two
  Towers are not listed. New `parts-mismatch`: "Parts don't match — will transcode". `needs-match` is labelled
  **Unmatched** with a **Fix match** button.
- **Fix match** (handover: search TMDB, radio-pick results with poster, year, director, cast, runtime):
  `GET /api/admin/films/{id}/match?query=&year=` (≤ 8 results, details via the pacer); `POST /api/admin/films/{id}/
  match { tmdbId }` applies `applyMovie`, sets `editions_confirmed`; if another film has that TMDB id the owner's
  choice joins into it (explicit decision, guard not applied), confirmed. TMDB unavailable ⇒ 503 shown in the panel.
  Takes the library lock.
- **Grouping report**: the newest 20 `grouping_change` rows under the scan panel.
- Tests: the attention rules; Fix match search/apply/join/503/409; report listing.

**As built (phase 5).** `FixMatch` (service) + `FixMatchController`; `GET /api/admin/grouping-report` returns
`{ items }` (newest 20). The lock holder for Fix match is `LibraryLock.MATCH`; only apply takes the lock (409 "A scan is
running" for any holder; search only reads TMDB, so it works during a scan and a React StrictMode double search cannot
collide with itself), and a scan refused because of a match says "A match is being fixed". TMDB trouble is a new
`ServiceUnavailableException` (503). Joining into an existing film goes through the new `EditionJoin.joinFilms`
(which delegates to `LibraryIngest.merge`; the title guard is not applied) and writes a `join` report line. The
survivor's editions are not part-joined: the owner arranges them in phase 6. `multiple-files` detail lines: "Files
look like different films", "The edition tags in a file name and its folder disagree", "Duplicate part numbers: N",
"Parts found: 1, 3, missing: 2", "N untagged versions of one film[, runtimes differ by P%]" (several are joined with
"; "). Part checks run per edition tag across the film's files. `parts-mismatch` is not exempted by
`editions_confirmed` (a technical fact) and shows PartsMatch's first difference. The Fix match panel opens as a card
below the grid (per the handover) and its apply button reads "Use this match", as in the handover.

## Phase 6 — Edit editions panel

- `GET /api/admin/films/{id}/editions` → files (id, fileName, durationMs, width, height, videoCodec, sizeBytes,
  presence, editionId, partNumber), editions (id, name, default), suggestion (assignments + one-line reason).
- `PUT /api/admin/films/{id}/editions` `{ editions: [{ id|null, name, default, files: [{ fileId, part|null }] }],
  notThisFilm: [fileId] }`. Validation (server is the authority; same rules in `frontend/src/state`): every file of
  the film assigned once or in `notThisFilm`; each edition's parts 1..n without gaps (`null` = single file, only as
  the edition's only file); names 1–32 characters, not blank, unique per film (case-insensitive); exactly one
  default; at least one edition. 400 with the first broken rule.
- Save (one transaction, library lock, via `EditionJoin` / unfold rules): kept editions keep their state (clamped to
  the new runtime); new editions start fresh; an edition left with no files is deleted with its state; part numbers,
  runtimes, default (`default_edition_chosen`), `grouping_source = OWNER`, `editions_confirmed = true`. "Not this
  film" files move to a new film with `NEEDS_MATCH` → Unmatched → Fix match.
- **Suggestion** (`EditionSuggester`, pure): tags and part suffixes first ("From the edition tags and part numbers");
  untagged by runtime: more than 5 minutes apart ⇒ shorter "Theatrical", longer "Extended" ("Runtimes 2h 35m and 2h
  51m — likely Theatrical and Extended"); within 1 minute ⇒ "Runtimes match (2h 35m) — likely copies of one cut"
  ("Original", "Original 2"); plain edition words used as names.
- **Panel** (`components/EditEditions.jsx`, dialog in Admin's visual language; amber only on Save): per file the
  name in mono, runtime · resolution · codec · size, Edition picker (existing + "New edition…" with a 32-character
  name field), Part picker (single file, 1–8); a Default edition radio per edition; the reason line; inline
  validation; Save / Cancel (discards). Opened from the attention row and from Film detail → "Edit editions…". It is
  also where each part's file name, duration and codec are listed (there is no other per-film Admin page). API in
  `frontend/src/api`, state and validation in `frontend/src/state` (no JSX).
- Tests: validation both sides; save keeps state; Not this film; suggestion reasons; opened from both places.

**As built (phase 6).** The API contract is in `foundation-plan.md` ("Edit editions"). Where it differs from the plan above:

- **Rules and error shape.** The rules are `EditionRules` (server, the authority) and `editionRules.js` (panel); one JSON
  table of cases, `backend/src/test/resources/shared/edition-validation-cases.json`, is read by both suites. The server
  returns every broken rule as `{ field, message }` (not only the first), in a fixed order; `error` is the first message.
  Extra rules beyond the design's list: file placement ("Every file has to be in an edition or marked Not this film.",
  "Each file can only be in one edition, or marked Not this film."), parts outside 1 to 8, and an edition with no files.
  A lone file may be sent as `part: null` or 1.
- **State on save** (`StateCarry`, pure, over the `StateFolding` and `EditionTimeline` rules `EditionJoin` uses). A kept
  edition keeps its id, so its history stays, and a renamed edition keeps it because the panel sends the edition's id with
  the new name (a "New edition…" that takes over the files of an emptied edition adopts its id). When files move between
  editions the state follows the files: each old edition's state and credits marker are relocated across the editions its
  files went to (`StateFolding.unfold`/`relocate`), then the pieces landing in one edition are folded onto its new timeline
  (`StateFolding.fold`), positions and the last part's marker shifted by the part start (`timelineDurationMs`), watched only
  when the edition holding the last part was watched. So pt1 and pt2 joined from two editions give pt2's 5 min the position
  partStart + 5 min. A new edition made from an emptied edition's files inherits that share. State is lost only where it is
  truly gone: an edition whose files all go to Not this film or that is removed, or a resume position that sat in a file
  that left. An edition with a file of no known duration cannot be folded: its position is only clamped to the new runtime
  and its marker cleared if the files changed. `EditionJoin.repoint` (package-visible) remaps stream choices, and a
  remembered last edition that was deleted points at the default.
- **Lock.** A save takes `LibraryLock.EDIT`; a scan refused because of it says "Editions are being edited".
- **Suggestion** (`EditionSuggester`): beyond the plan's rules, a settled film is suggested as arranged; files whose title
  is another film's are suggested as Not this film with the design's "these may be different films" line; plain edition
  words in a file name count as tags; runtimes 1 to 5 minutes apart are copies with "Runtimes are close (…) — likely
  copies of one cut. Kept apart so you can compare; rename one, or mark it Not this film."; three or more cuts, or
  unknown runtimes, are kept apart as "Original", "Original 2", … with their own reason; a single file has no reason
  (the panel hides the Suggested line). A suggested edition continues the existing edition holding most of its files
  (so its history stays). The suggestion also carries the default's name (the current default while it is still there,
  else Original/Theatrical, else the longest).
- **Panel.** `components/EditEditions.jsx` (dialog on desktop, full-screen sheet at 600px and below). Opened from the
  Several files row in Admin (not from Parts don't match: splitting editions does not change what the parts are) and
  from Film detail → "Edit editions…". Esc is Cancel with no prompt. After a save the Admin lists refresh and a toast
  says "Editions saved".

## Join parts — deferred (owner, 2026-11-09)

Brief kept for when it is revisited: lossless concat (`-c copy`, MP4/M4V `+faststart`), temp file, duration = sum ±
1 s, swap in, originals to a backup folder never deleted, progress kept, background job with progress in Admin. It
writes to the read-only library (SECURITY.md rule 3), which needs an owner decision first.
