# Holiday themes plan (Halloween and Christmas weeks)

Status: built (#227, #228); the Christmas films admin screen that manages the overrides is `christmas-films-plan.md`. Differences from this plan: the keyword match is on the keyword *name* `christmas`, not id 207317; the fetch is a separate `KeywordsRefresher` (which also runs at start-up), not part of the languages fetch; the admin screen answers the open point about printing the matches.

## What the owner wants

Home's "This week" row (`WeeklyThemeSelector`, decision 4) is a decade, genre or director chosen from the ISO week.
Two weeks a year it must instead be **horror** (Halloween) and **Christmas films** (Christmas). Which films count as
Christmas is the hard part (Die Hard, Gremlins, Trading Places, Home Alone...).

## Decisions (owner, 2026-11-18)

1. **Christmas rule: TMDB keyword plus an override list.** A film is Christmas if TMDB tags it with the `christmas`
   keyword (id 207317), unless the owner excludes it, or it is on the owner's include list. Die Hard carries the tag.
2. **Periods: from the Monday on or before 24 Oct (Halloween) and 18 Dec (Christmas)**, each running to the day
   itself (31 Oct, 25 Dec; see the 2026-11-21 update, which replaced Monday to Sunday). In 2026: Mon 19 Oct and
   Mon 14 Dec. Each starts on a Monday, so the Monday rollover and the caption stay true.
   Rationale: the theme shows before the day, so there is time to watch.

Horror needs no new data: the `Horror` genre already exists on `Film.genres`.

## Design

### Selector (backend, `film` package)

- `WeeklyThemeSelector.choose(date, library)` first asks `HolidayWeeks.holidayFor(date)` -> `HALLOWEEN`, `CHRISTMAS` or
  empty. Pure function of the date: the Monday on or before 24 Oct / 18 Dec of that year through 31 Oct / 25 Dec.
- Holiday week: kind `holiday`, value `halloween` / `christmas`, titles "This week: Halloween" /
  "This week: Christmas", caption stays the standing one. Films: Halloween = genre Horror; Christmas = `isChristmas`.
- Same `pick` for Halloween (oldest first, 12 max); Christmas has its own order (2026-11-21 update). **Fallback:** fewer than `MIN_FILMS` (5) matching films -> use the normal
  rotation, so a small library never gets an empty or thin row.
- Frontend needs no change if it renders `kind`/`value` generically (check `ThemeDto` use; add a test either way).

### Christmas data

- Flyway `V33__film_keywords.sql`: new nullable `keywords_fetched_at` and a `film_keyword(film_id, keyword)` element
  table, mirroring `genres`. Store only the keyword names (small), so the same data can later drive other themes.
- Fetch from TMDB `/movie/{id}/keywords` alongside the existing languages fetch pattern
  (`languagesFetchedAt` in `LibraryIngest`, `FilmRepository` "not yet fetched" queries): new films get their keywords from the
  backfill that runs after every scan, import and match fix, which walks films with `keywords_fetched_at is null`. Reuse the existing TMDB client, rate limit and
  failure handling; a failed fetch leaves the timestamp null so it is retried.
- Override list in config: `cinefilm.holiday.christmas.include` / `.exclude`, comma-separated TMDB ids
  (`application.properties`, bound by a `@ConfigurationProperties` record). Exclude beats include beats keyword.
- `isChristmas(film)` = not excluded and (included or keywords contain `christmas`). Keywords are read lazily, so the
  home query must fetch them (fetch join or batch) to avoid N+1; check how `HomeService` loads genres and do the same.

## Slices (each <= 1,500 changed lines, Sonnet builds, gates per CLAUDE.md)

1. **Keywords data**: migration, entity field, TMDB fetch, ingest + backfill, tests (incl. the integration package for
   the migration). Changelog entry. Merges alone, changes no behaviour.
2. **Holiday weeks**: `HolidayWeeks`, override config, selector changes, `HomeService` loading, tests (both holiday
   weeks, week boundaries, year edges, fallback under 5 films, include/exclude precedence), frontend test, changelog,
   handover README decision 4 gets one line.

## Tests that matter

- Date edges: 18 Dec on each weekday (the Monday-on-or-before shifts); 24 Oct likewise; Sun 13 Dec is not Christmas,
  Mon 14 Dec is; Sun 20 Dec is the last Christmas day.
- A film with only the keyword, only on the include list, on both include and exclude, and with horror genre in
  Halloween week; the 12-film cap and ordering.
- `TestClockConfig` already pins the clock; the selector takes a date so no new clock plumbing.

## Open points for the review

- TMDB keyword coverage is uneven for older or obscure films; the override list is the safety valve. Decide after the
  backfill whether to print the Christmas matches in the admin/log for a sanity check.
- Whether the keywords table should be shared with a later "theme by keyword" feature (not in scope).

## Update 2026-11-19: overrides move to the database

The `cinefilm.holiday.christmas.include` / `.exclude` properties (and the `CINEFILM_HOLIDAY_CHRISTMAS_*` variables)
described above are replaced by a `christmas_override` table and an Admin screen, and the config implementation is
removed with no seed. See `christmas-films-plan.md`.

## Update 2026-11-21: the period runs to the day, and Christmas is shuffled daily (owner)

- **The period runs until the day itself.** Halloween: the Monday on or before 24 Oct through 31 Oct inclusive.
  Christmas: the Monday on or before 18 Dec through 25 Dec inclusive (8 to 14 days). The Sunday no longer ends it, so
  Home keeps the holiday row on 21 to 25 Dec. The Monday start is unchanged, so the caption stays true.
  `HolidayWeeks.christmasWeekFrom` reports next year's period from 26 Dec.
- **Christmas order:** films the owner added by hand (an include, not excluded) first in `ROW_ORDER`, then every other
  Christmas film in a shuffle seeded by the London date's epoch day, applied to the `ROW_ORDER` list. Stable all day
  and for Home and the Admin screen alike; a different dozen each day, changing at London midnight. Halloween keeps its
  order (year, sort title, id).
