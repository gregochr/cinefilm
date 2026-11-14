# Library repair: TMDB evidence splits different films — plan

Two small PRs. The owner asked on 2026-11-14, after "Elf (2003)" showed in To do with `Elf.m4v` and
`Enchanted.m4v`: "Files look like different films; 2 untagged versions of one film". This plan amends
`split-films-plan.md` § "Library repair". Until now repair has never used TMDB; after PR B its result depends on
whether TMDB answers. A pointer is added there.

- **PR A**: the Edit editions reason line stops blaming equal runtimes (`EditionSuggester` only).
- **PR B**: repair splits a film when TMDB shows that the other title is a different film.

Revised after an adversarial review (14 findings) and a check against production the same day. The numbers are in
"Production evidence".

## Why it happens

Before #55 and #64, the Plex import took every Part that Plex listed under one item into one CineFilm film, with no
title check. Plex had filed `Enchanted.m4v` under Elf. The different-film guard now stops new cases like this.
Repair finds the old ones, but it splits only on a sequel marker or a `{tmdb-N}` tag. "elf" against "enchanted" has
neither, so the film is flagged and waits for the owner.

## Production evidence (read from the live Admin API, 2026-11-14)

| Film | Files (duration) | TMDB search for the other title, exact-title hits |
|---|---|---|
| Elf (10719, 97 min) | `Elf.m4v` 96.7 min, `Enchanted.m4v` 103.0 min | "Enchanted": 4523 (2007, 107 min), 404277 (1998, 96 min), … |
| Ghost | `Ghost (1990).m4v` 126.7 min, `The Witches.m4v` 87.8 min | "The Witches": 10166 (1990, 91), 40244 (1966, 89) |
| Top Gun: Maverick (361743) | `Top Gun Maverick (2022).m4v` 130.2 min, `Top Gun.m4v` 109.6 min | "Top Gun": 744 (1986, 110), 243655 (1955, 73) |
| Spider-Man 2 | `Spider-Man 2 (2004).m4v` 122.2, `The Amazing Spiderman.m4v` 130.7 | "The Amazing Spider-Man" 1930: title differs by a hyphen |
| Blade Runner | `Blade Runner.m4v` 7,056,883 ms, `Blader Runner (1982) {edition-The Final Cut}.m4v` 7,056,917 ms | one film in two qualities (34 ms apart) |

**The first draft's rule fails Elf.** It asked "which film is this file?" via `TmdbMatcher.match`. Two exact
"Enchanted" hits are both within 8 minutes of 103 min. Both score 75, the lead is under `MARGIN`, the result is
`NEEDS_MATCH`, and the film stays flagged. A runtime rule against the reference fails as well: 103 against Elf's
97 is inside the 8-minute tolerance.

**The right question is "is this file *not* the reference film?"**, not "which film is it?". Every exact-title
"Enchanted" on TMDB is some film other than Elf. Which one it is gets settled afterwards by the normal pending
match, or by the owner through Fix match ("Unmatched").

## PR B — the rule

A film the owner has not settled is split when today's `RepairRules.decide` says FLAG and all of these hold:

1. Exactly one group's comparison title equals the film's own: the *own group* (unchanged).
2. The film has a reference TMDB id: `film.tmdbId`, else the own group's shared `{tmdb-}` tag (unchanged).
3. Every other group has today's evidence (a sequel marker, or a `{tmdb-}` tag that differs from the reference) or
   the new **TMDB evidence**.

A group can only have TMDB evidence when it is **eligible**. Its files carry no `{tmdb-}` or `{imdb-}` tag (review
1: a tag is the owner's word, so it is never overruled by a title search). They carry no `editionTag` and no
`plainEdition` either (review 5: TMDB lists some cuts as films of their own, such as "Superman II: The Richard
Donner Cut").

An eligible group **has TMDB evidence** when all of these hold:

- a. `tmdb.search(group.title, group.year)` returns at least one hit whose `title` or `originalTitle` has the
  same `FileNameParser.comparisonTitle(String)` as the group (an *exact hit*). Use that method, never `parse` or
  `GroupingGuard.titleOf`, which read the string as a path (review 6). If the group has a year and nothing is
  found, search once more without it, mirroring the first two queries of `TmdbMatcher.queries`.
- b. **No** exact hit has the reference id.
- c. The group's comparison title is **not** one of the reference film's titles: its `title`, its
  `originalTitle`, and every entry from TMDB's `/3/movie/{id}/alternative_titles`, each through
  `comparisonTitle`. Review 2 named "Léon: The Professional" with `The Professional.mkv` and "Se7en" with
  `Seven.mkv` as alternative titles that also exist as exact-title films of their own. This check keeps them
  flagged.

Otherwise the film stays flagged as today. That includes: TMDB disabled or unavailable, no exact hit (typos such
as "Blader Runner" and "The Good Sheperd", and "Amazing Spiderman" against "Spider-Man"), and an alternative
title of the reference. It is all or nothing per film, as today.

Expected results on the production list: **split** Elf/Enchanted, Ghost/The Witches and Top Gun: Maverick/Top Gun
(if "Top Gun" is among Maverick's alternative titles, it stays flagged; that is the safe direction). **Stay** for
Blade Runner, The Good Shepherd, Snow White, Spider-Man 2, First Blood, Comic Book Origins (no own group) and the
seven Star Wars films (no own group, or an alternative title).

The split film is `PENDING`, as any split is today. In the scan, `retryPending` runs straight after repair. With
two exact "Enchanted" hits it may land `NEEDS_MATCH`, and Fix match settles it in one pick. After a Plex import
it waits for the next scan.

### Design

- **`RepairRules` stays pure.** `decide(filmTitle, filmTmdbId, files, Set<String> differentFilm)`: the set holds the
  comparison titles of the groups that have TMDB evidence. A group is strong when `otherTag || sequelDiffers ||
  differentFilm.contains(title)`. Keep the three-argument overload (empty set).
  - A pure `RepairRules.lookups(decision inputs)` returns the eligible groups worth looking up. It returns them only
    when today's decision is FLAG, the own group exists, the reference is non-null, and the group lacks today's
    evidence.
  - A pure `RepairRules.isDifferentFilm(String groupTitle, List<SearchHit> hits, long referenceId, Set<String>
    referenceTitles)` applies a–c.
- **`TmdbApi.alternativeTitles(long id)`** returns `List<String>` from `/3/movie/{id}/alternative_titles`, using
  the `titles[].title` field. It is a new method on the API, so `MovieDetails` and its cache stay unchanged. Cache
  it by id like `movie`. Empty when TMDB has no film with that id. On an outage it throws
  `TmdbUnavailableException`, like the others. Client tests follow the existing `TmdbClient` tests (a stub server).
- **`LibraryGrouping.repair(boolean tmdbUsable)` returns `RepairResult(int done, boolean tmdbDown)`** (review 7).
  - The reference titles come from `tmdb.movie(reference)` (cached) plus `alternativeTitles(reference)`, fetched
    once per film and only when a group needs a lookup.
  - Lookups run inside the per-film `try`, outside any transaction.
  - The first `TmdbUnavailableException` sets `tmdbDown`, and no more lookups happen in that run.
  - Any other runtime error in a lookup counts as "no evidence" for that film only, and is logged like today's
    failures.
- **Callers**:
  - `ScanService.groupLibrary` passes `!run.tmdbDown && tmdb.enabled()` and sets `run.tmdbDown` from the result,
    so `retryPending` skips straight away after an outage.
  - Give `repair()` and `group()` separate `try`s, so a repair failure never skips `group()`.
  - Fix the comment "neither needs TMDB" at `ScanService.java:281`.
  - `PlexImportService.groupLibrary` passes `tmdb.enabled()`.
- **`LibraryGrouping`** gets `TmdbApi` injected. It does not need `TmdbMatcher`. There is no bean cycle (review 13).
- **Split log line** for a TMDB-evidence split: `Moved "Enchanted" out of "Elf" into a film of its own (TMDB lists
  it as a different film)`. The other splits keep today's wording.
- **Cost:** one or two searches per eligible group, plus two calls per reference film, which are cached by id
  after the first. Searches are not cached. A film that stays flagged repeats the lookups on every repair (startup,
  nightly at 04:30, an Admin scan, a Plex import). That is a few calls per film; accepted and stated.
- **Re-merge after a split: verified safe** (review 10).
  - `applyOutcome` checks `GroupingGuard.accepts`. The titles differ, so a match to the reference id leaves the film
    `NEEDS_MATCH`.
  - The auto-resolve key includes the comparison title.
  - The Plex pairing leaves out files with another title.
  - A second `repair()` sees one title group and returns NONE.

### Tests (Sonnet writes them; `test-standards.md` applies)

`RepairRulesTest` (pure):
- `decide` with the set:
  - Elf with {"enchanted"} → SPLIT. Empty set → FLAG.
  - Two other groups with only one in the set → FLAG.
  - A sequel group plus a group in the set → SPLIT.
  - Reference null → FLAG. No own group → FLAG.
- `lookups`:
  - none for NONE or SPLIT, or without an own group or reference;
  - a group tagged `{tmdb-<reference>}` is excluded, and so is one tagged `{imdb-…}` (review 1);
  - a group with an `editionTag` or a `plainEdition` is excluded (review 5);
  - only groups without today's evidence are returned.
- `isDifferentFilm`:
  - Elf's real hits (4523, 404277) with reference 10719 → true;
  - an exact hit with the reference id → false;
  - no exact hit: the "Blader Runner" fuzzy hit on 78, and "the amazing spiderman" against "The Amazing
    Spider-Man" → false;
  - the group title among the reference's alternative titles ("the professional" against Léon's) → false;
  - an exact match on `originalTitle` only → true;
  - null titles on hits → ignored;
  - "Fast & Furious" against "Fast and Furious" → false (`comparisonTitle` differs from the matcher's
    `normalise`; this is stricter, and accepted, review 6).

`LibraryGroupingTest` (an `ApiTestBase` test with `@MockitoBean TmdbApi`; update the hand-built constructors at
lines 641 and 661):
- **Elf end to end.** After `repair(true)`:
  - "Enchanted" is a new `PENDING` film, and `ingest.pending()` shows its title.
  - Elf keeps `Elf.m4v`.
  - A SPLIT change with the TMDB wording is logged, and no FLAG.
  - `applyOutcome(newId, MATCHED 10719)` leaves it `NEEDS_MATCH` and does not merge it back.
  - `applyOutcome(newId, MATCHED 4523)` applies.
- **Outage.** The first search throws `TmdbUnavailableException` → the result reports `tmdbDown`, the film stays
  flagged, and `verify(tmdb, never())` shows no further search after that one.
- **TMDB off.** `repair(false)` → no TMDB interaction, flagged.
- **Tagged group.** A group tagged with the reference id, against a fake that would say "different film" → not
  looked up, flagged.
- **Rocky 4/5 sequel split.** Makes no TMDB calls.
- **Second `repair` after a split.** No TMDB calls, nothing changed.
- **Confirmed films.** Never read (keep the existing test).

`ScanServiceGroupingTest` (it mocks `LibraryGrouping`):
- `repair` gets `false` when the walk already set `tmdbDown`;
- a `tmdbDown` result makes `retryPending` skip;
- a repair exception still runs `group()`.

`TmdbClientTest`: `alternativeTitles` parses, returns empty on 404, and throws on outage.

## PR A — the Edit editions reason line

On Blade Runner the panel said "Runtimes 1h 57m and 1h 57m — these may be different films".
`EditionSuggester.differentFilms` quotes both runtimes whenever they are known, as if they were the evidence. The
real reason is that the names differ. Use the suggester's own constants (review 3), comparing the first kept file
with the first excluded file, as today:

| Runtimes | Line |
|---|---|
| Either unknown | `These files may be different films` (unchanged) |
| Within `SAME_CUT_MS` (60 s) | `The names differ but the runtimes match (1h 57m) — if it's the same film, move it back to keep it here` |
| Within `DIFFERENT_CUTS_MS` (5 min) | `The names differ but the runtimes are close (1h 52m and 1h 57m) — if it's the same film, move it back to keep it here` |
| Further apart | `Runtimes 1h 37m and 1h 43m — these may be different films` (unchanged; Elf lands here at 6.3 min) |

- Placement does not change: the file is still pre-placed in Not this film.
- "Move back" is the button label (`EditEditions.jsx:195`).
- Tests: just below, at, and just above both thresholds; the Blade Runner pair (34 ms → "match"); the Elf pair →
  unchanged wording; keep the three existing assertions.

## Gates and delivery

The backend gate from `CLAUDE.md` for both PRs.
- **PR A**: `changelog.d/20261114-edit-editions-reason.md`, commit `fix(admin): the Edit editions reason no longer
  calls matching runtimes evidence of different films`.
- **PR B**: `changelog.d/20261114-repair-tmdb-evidence.md`, commit `fix(library): split films whose files TMDB
  lists as different films`.

Then the owner-rule review and PR steps, one PR at a time. PR A first, because it is tiny.

## Not in scope

- **An alternative title of the same film.** The Star Wars "Episode IV - A New Hope" against "Star Wars (1977)"
  rows are half the list. They are flagged as "different films", which is wrong. Settling them automatically as
  one film is the natural next step, and condition c's alternative-title lookup is the evidence it would use.
- Splitting when the own group is missing (Comic Book Origins, First Blood). The film's own match is wrong there;
  Fix match covers it.
