# Collections move into Library — plan

Source brief and target screens: `docs/design/library-collections/` (`PROMPT.md`, `01`–`05`). The handover
(decision 16) is updated to match. One PR, four conventional commits in this order: Home, tabs, Collections tab,
grouping.

## Settled choices

- **Decision 16 still holds:** a collection exists only with ≥ 2 owned films (`CollectionService.MIN_OWNED`). The
  sample screen's "The Lord of the Rings · 1 of 3 films" card is sample data; it does not appear. The fan rule still
  handles 1 owned (a filtered grouped tile can show 1 film).
- **Display name** is computed in the frontend (`frontend/src/state/collections.js`, `displayName(name)`: strip a
  trailing `" Collection"`, case-insensitive, trimmed). The API keeps TMDB's name. Used on Collections cards, grouped
  tiles, the Collection page heading and the Film detail link.
- **Group collections** preference: `localStorage` key `cinefilm.library.groupCollections` (`"1"`/absent); Library's
  other settings are URL params, which are per-link, not per-user. Reads and writes in try/catch; default off.
- **Grouping is client-side.** The films list is already the whole filtered, sorted result (no paging).

## API changes

| Endpoint | Change |
|---|---|
| `GET /api/home` | `collections` removed. |
| `GET /api/films` | `collection` param and `facets.collections` removed. `FilmCard` gains `collectionId: number \| null`, set only when the film's collection has ≥ `MIN_OWNED` browsable films (same rule as `FilmDetail.collection`). |
| `GET /api/collections` (new) | `{ filmCount, collections: CollectionCard[] }`. `filmCount` = browsable films in the library (the Films tab count). `collections` = every collection with ≥ `MIN_OWNED` owned films, sorted by name. |

**CollectionCard:** `{ id, name, posterPath, ownedCount, totalCount, posterPaths: (string|null)[], lastAddedAt }`.
`posterPaths` = up to 3 owned films' posters, **earliest release first** (slot order). `lastAddedAt` = the newest
`addedAt` among owned films (ISO instant), for "Recently added to".

## Frontend

- **Routes:** `/library` (Films tab) and `/library/collections` (Collections tab), both under one `Library` shell
  that renders the heading, the count line and the tablist. Header nav keeps "Library" current on both.
- **Tabs:** `role="tablist"`, two `role="tab"` links with `aria-selected`; Left/Right (and Home/End) move focus and
  navigate; roving `tabIndex`. Label `Films` / `Collections` + count in `--cf-font-mono` 12px muted. Active: primary
  text + 2px amber bottom border (amber use allowed: it is the current-nav underline). Counts come from
  `GET /api/collections` (`filmCount`, `collections.length`).
- **Collections tab:** URL params `q`, `sort` (`name` default | `added` | `size`), `show` (`all` default |
  `complete` | `missing`). Search on display name, case-insensitive. Name sort ignores a leading "The". `added` =
  `lastAddedAt` desc; `size` = `ownedCount` desc; ties by name. Complete = `ownedCount >= totalCount`.
  Count line: "{n} collections". No-results uses the existing `NoResults`.
- **Fan component** (`CollectionFan`), shared by the card and the grouped tile: given posters ordered earliest first,
  renders `min(n, 3)` `<img loading="lazy">` (or the existing placeholder colour block when the path is null — still
  a film, never an empty slot). Front = earliest; others step back and left. Rearmost `filter: brightness(0.6)`,
  middle `brightness(0.8)`, front none; every poster in front of another gets `box-shadow: -8px 0 18px
  rgba(0,0,0,.5)`. No `opacity` anywhere. Centred horizontally. Pure helper `fanPosters(posters)` returns the slice
  and per-slot brightness so it can be unit tested.
- **Card:** link to `/collections/:id`; poster area 190px, `--cf-surface` (or the existing surface token), radius
  10px; name in serif 24px; "{owned} of {total} films" + "✓ Complete" when complete. Grid
  `repeat(auto-fill, minmax(240px, 1fr))`, gap `36px 24px`.
- **Films tab:** remove the Collection picker; add `Switch` "Group collections". When on and `q` is empty, call
  pure `groupFilms(films, collections, sort)` from `state/collections.js`:
  - films with `collectionId` in `collections` fold into one tile per collection; others stay.
  - tile `count` = folded films passing the filters; posters = those films, earliest year first.
  - **Placement:** `added`, `year`, `imdb` and `rt` — at the position of the collection's first film in the server order (the
    server order already puts the most-recent / earliest / highest-rated film first, so under the rating sorts a
    tile sits where its best-rated passing film would). `title` — inserted among the remaining items
    by `sortKey(displayName)` vs each film's `sortKey(title)` (lower-case, leading "the " dropped, as
    `SortTitles`), stable.
  - count line while grouped: "{films} films in {tiles} tiles · sorted by …" (singular forms).
- **Grouped tile:** 2:3 like a poster, surface background, 1px line border, 8px radius, fan inside, round count badge
  top right (mono 12px), serif name over a bottom scrim, caption "Collection · {n} films", links to the collection
  page, accessible name "{name}, collection of {n} films".
- **Home:** drop the Collections row and its CSS variant; the empty-library check no longer looks at collections.

## Tests (beyond keeping coverage at 95/90 per file)

- `fanPosters`: 1, 2, 3 and 5 posters → 1/2/3/3 slots, brightness order, earliest at the front.
- `displayName`: "Harry Potter Collection" → "Harry Potter"; no suffix unchanged; "Collection" mid-name unchanged.
- Collections tab filters: Complete, Missing films, search, each sort.
- `groupFilms`: placement under title, year and added; filter interaction (only passing films counted; a
  collection with no passing film has no tile); search disables grouping (screen test).
- Backend: `/api/collections` (min-owned rule, `filmCount`, `lastAddedAt`, poster order), `FilmCard.collectionId`
  only for qualifying collections, Home without collections.

## Review notes

- `FilmCard.collectionId` must be correct in `GET /api/films` (the qualifying set is computed from the same
  `loadAll()` the facets used). Other endpoints that return `FilmCard` may compute it the same way or send `null`;
  the contract says which.
- `frontend/src/api/fixtureServer.js`, `test/mockApi.jsx` and the fixtures gain `/api/collections`; `home.json`
  loses `collections`; `films.json` films gain `collectionId` (Alien and Alien 3 → 1, the Three Colours films → 3,
  so grouping is visible in dev).
