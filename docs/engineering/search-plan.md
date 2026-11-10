# Header search as you type — plan

Source brief and target screens: `docs/design/search/` (`PROMPT.md`, `screens/01`–`05`). The brief's colour blocks
and names are sample data. Four PRs, one conventional commit each, in this order (the brief asks for these four
commits): **index**, **matching/ranking**, **desktop dropdown**, **iPhone**. Each branches from the updated `main`.

## What exists today

- `Header.jsx`: a plain `<form role="search">` that navigates to `/library?q=…` on Enter. Hidden on `/library*`
  (Library has its own box). `useSlashFocus` focuses `[data-global-search]` on `/`.
- Library search: `useUrlSearch` (250 ms debounce) → `GET /api/films?q=` → `BrowseRules.matchesSearch`, a
  case-insensitive **substring** match over title, cast and director names. No accent folding.
- Phone: `TabBar` "Search" goes to `/library?focus=search`.
- Data the client has: `FilmCard` (id, title, year, posterPath, progress, director, collectionId, sortTitle);
  `GET /api/collections` cards (id, name, ownedCount, totalCount, posterPaths ×3); `displayName()` and `sortKey()`
  in `state/collections.js`. No per-person list endpoint, no film-count per person, no ETag anywhere.
- Cast is capped at TMDB's top 10 billed (`TmdbClient.TOP_CAST`), so people ≈ a few thousand, not tens of thousands.
- `ScanFinished` and `PlexImportFinished` events exist.

## Settled choices

- **Search runs on the client over one index.** No call per keystroke, no debounce in the dropdown.
- **New endpoint `GET /api/search-index`** rather than stitching `/api/films` + `/api/collections` + people: one
  request, one ETag, and people/film counts don't exist anywhere else.
- **Freshness by content hash, not event bookkeeping.** The server builds the index, hashes the serialised body
  (SHA-256, first 16 hex) and returns it as a weak `ETag`, so Tomcat still compresses it; `If-None-Match` matching → `304`. Built on demand
  inside a read transaction and memoised in a small `SearchIndexCache` that `ScanFinished`, `PlexImportFinished` and
  any playback/progress write invalidate (cheap: it only drops the memo). Correct even if an invalidation is
  missed, as long as the memo is dropped on those writes; the hash makes the client side trivially right.
- **Client refresh:** load at app start (after sign-in), revalidate with `If-None-Match` on `visibilitychange` →
  visible, and after this tab sees a scan/import finish (Admin already polls those). The cached copy also lives in
  `localStorage` (`cinefilm.searchIndex.v1`, with its ETag, try/catch around every access) so the box works
  instantly on reload while revalidation runs.
- **In-progress "time left" comes from the index**, so it's as fresh as the last revalidation. Playback writes drop
  the server memo; the client revalidates on return to the tab. Good enough: the dropdown is not the source of truth.
- **Normalisation is one function, mirrored once.** `normalise(s)`: NFD, strip combining marks, lower-case, drop
  `'` `’` `‘`, every other non-letter/digit → space, collapse spaces. Words = split on space. Pre-computed per entry
  when the index loads, never per keystroke.
- **Highlight** is computed on the normalised string and mapped back to the original via an index map built during
  normalisation (folding can change lengths: `æ`, `ß`, removed apostrophes). Unit-tested on "O’Connor", "WALL·E",
  "Amélie".
- **Collection display name** = `displayName()` (strip " Collection"); search and highlight run on it.
- **People in the index:** anyone with a DIRECTOR credit, or a CAST credit, on at least one *browsable* film. Film
  counts per role count browsable films only (as the Person page lists only owned films, decision 5).
- **Handover decision 5** ("live as you type (debounced)") is amended: the header dropdown is undebounced and
  client-side; the Library page keeps its debounced server search. The README gets a line pointing at
  `docs/design/search/`.
- **Library search becomes word-prefix too** (in PR 2), so "See all" doesn't show *Twisters* for "w". Backend
  `BrowseRules.matchesSearch` is ported to the same normalise + every-term-prefixes-some-word rule over title, cast,
  director and the film's collection display name. A shared JSON test-vector file
  (`docs/design/search/vectors.json`, read by both a JUnit test and a Vitest test) keeps the two in step. No typo
  fallback on the server.
- **"See all {n} results":** `n` = total matches across all four groups before capping (the brief's "17" for "wa"
  is 8 + 1 + 5 + 3). It opens `/library?q={query}`; Library then lists films only, so its count may differ. Accepted.
- **Header search stays hidden on `/library*`.** `/` already focuses Library's own box there.

## Owner decisions (the bold defaults, accepted by the owner 2026-11-10)

1. **Ranking vs the screenshots.** The written rule puts in-progress films before alphabetical, but `01` shows
   *Warrior* (in progress) last, and `05` shows *Harry Potter* before *The Hangover* collection although both start
   with "ha" and "hangover" < "harry". **Default: the written rule wins; the screenshots are sample data.**
2. **People tie-break.** Alphabetical across a few thousand actors lets bit-part names crowd out the ones you mean
   ("ha" → three obscure "Ha…" actors, no Tom Hanks). **Default: within a tier, people sort by film count (desc),
   then alphabetical.** Matches `02`/`05` closely enough and makes the "Tom Hanks under Actors" check hold on real
   data. Films and collections stay as written.
3. **"warr" → only Warrior** holds on the test fixture, not necessarily the real library (an actor named Warren
   would match). **Default: the done-when check is proven on fixture data; real data shows whatever genuinely
   matches.**

## PR 1 — `feat(search): compact search index with ETag`

**Backend** (new package `gregochr.cinefilm.search`):

- `SearchIndexDtos`: compact JSON, short keys, arrays not objects where it's a list of scalars:
  ```json
  {
    "films": [{"id": 1, "t": "Warrior", "y": 2011, "d": ["Gavin O'Connor"], "p": "/abc.jpg",
               "left": 7380000, "at": "2026-11-01T20:00:00Z", "s": "warrior"}],
    "collections": [{"id": 4, "n": "Star Wars Collection", "o": 2, "of": 9, "p": ["/a.jpg", "/b.jpg"]}],
    "people": [{"id": 9, "n": "Tom Hanks", "a": 9, "d": 0}]
  }
  ```
  `left` = ms remaining (runtime − position) when `FilmView.progress()` is present, else omitted; `at` =
  `lastPlayedAt` (for "most recently watched") or omitted; `s` = `sortTitle`. Collections use the same
  ≥ `MIN_OWNED` rule and owned/total as `CollectionService.cards` (reuse it, don't re-derive). Posters: first two of
  `posterPaths`.
- `SearchIndexService.build()` over `LibraryLoader.loadAll()` + `CollectionService.cards(...)`; people derived from
  the loaded films' credits (no extra query).
- `SearchIndexCache`: holds `(bytes, etag)`; `@EventListener` on `ScanFinished`, `PlexImportFinished`, and the
  playback-state write paths (progress, watched, hide-from-CW, restore, regrouping/split) → invalidate.
  Concurrency: a `volatile` holder, rebuild under a lock, last writer wins.
- `SearchIndexController` `GET /api/search-index`: `ETag` header, `Cache-Control: no-cache` (always revalidate),
  `304` on matching `If-None-Match` (handle the `W/` and quoted forms). Behind the existing session auth.
- Enable `server.compression` for `application/json` if it isn't on (it isn't configured today); check that the
  `api` behind Tailscale doesn't already compress.

**Frontend:**

- `api/resources.js`: `searchApi.index({ etag, signal })`. `client.js` gains a small `getWithEtag(path, etag)` that
  sends `If-None-Match` and returns `{ status: 304 } | { data, etag }` (no JSX, reusable by Apple TV).
- `state/searchIndex.js` (no JSX): `prepareIndex(raw)` → entries with pre-normalised words and display strings; the
  `localStorage` cache; `useSearchIndex()` hook — loads once per app (module-level store with subscribe, so the
  header and the phone sheet share it), revalidates on `visibilitychange` and on a `cinefilm:library-changed` window
  event that Admin dispatches when a scan or import finishes.
- `fixtureServer.js`: serve a fixture index built from the existing fixtures, plus the brief's sample titles needed
  by the done-when checks (*Walk the Line*, *WALL·E*, *War Horse*, *War of the Worlds*, *Warrior* in progress,
  *Hancock*, *The Hangover*, two *Harry Potter*s, *Star Wars* and *Harry Potter* and *The Hangover* collections,
  Tom Hanks, Christoph Waltz, Denzel Washington, Emma Watson, Gavin O'Connor).

**Tests:** JUnit for the builder (browsable-only, people roles and counts, collection rule, `left`), the cache
invalidation, the controller (200 with ETag, 304, ETag changes after a scan event). Testcontainers integration test
for the endpoint (CI only). Vitest for `getWithEtag`, `prepareIndex`, the store (304 keeps data, storage throwing
still works, revalidate on visibility).

## PR 2 — `feat(search): word-prefix matching, ranking and typo fallback`

Pure module `frontend/src/state/search.js` (no JSX, no React):

- `normalise(s)` → `{ text, words, map }` (see Settled choices).
- `matchEntry(entry, terms)` → `null | { tier, ranges }`. Every query term must prefix some word; a word can be used
  by one term only (so "ha ha" needs two "ha…" words). `tier 0` = the first term matches the first word, after
  skipping a leading "the" word (and the first word itself if the query starts with "the"); `tier 1` otherwise.
- `search(index, query)` → `{ groups: [{ key, label, items, total }], total, fuzzy }`:
  1. exact pass over all four groups;
  2. if `total === 0` and the normalised query (spaces removed) is ≥ 4 chars, a fuzzy pass: per term, the best
     Damerau–Levenshtein (optimal string alignment) distance between the term and each word's same-length prefix
     (and prefix ± 1 / ± 2 so insertions and deletions at the end count); allowed **1** for 4–6 chars, **2** for 7+,
     measured on the whole query length; rank by total distance, then as below. `fuzzy: true` drives the notice.
  3. Order in a group: tier, then (films) in progress first, then `at` desc, then `sortKey` of title; (collections)
     `sortKey` of display name; (people) film count desc then name (owner decision 2).
  4. Caps: Titles 5, Collections 2, Actors 3, Directors 3; `total` per group before capping.
- Pure DL with an early exit when the running minimum exceeds the allowance; the whole index is a few thousand
  entries, so a linear scan per keystroke is fine. A Vitest perf guard: 5,000 people + 1,100 films, a fuzzy query,
  under 16 ms on CI (generous; fails loudly if someone adds an O(n²)).

**Backend:** port normalise + word-prefix to `BrowseRules.matchesSearch` (title, cast, directors, collection display
name), driven by `docs/design/search/vectors.json` in both test suites. Library's `NoResults` copy is unchanged.

**Tests** (the brief's list, all in `search.test.js`): word-prefix incl. multi-word ("star wa"), mid-word rejected
("w" ✗ *Twisters*), accents ("amelie" → *Amélie*), apostrophes ("ocon" → O'Connor); ranking ("The" ignored, start
before later word, in-progress first, recent before older, alphabetical last); group order and caps and
"3 of 7" totals; typo distances at exactly 4, 6 and 7 characters, each at the allowance and one past it; fallback
never fires when any group has an exact hit; "warr" doesn't pull in "Wars"; the W → Wa → War → Warr narrowing ends
on *Warrior* against the fixture index; highlight ranges map back correctly.

## PR 3 — `feat(search): desktop dropdown in the header`

- `components/SearchBox.jsx` replaces the header form; `components/SearchResults.jsx` renders groups and rows
  (shared with PR 4); `state/useSearchCombobox.js` (no JSX) owns query, active index, open/close and key handling.
- Opens at 1 character; anchored right to the box; 540 px wide; `max-height: min(720px, 100vh - 120px)`; scroll
  inside; the active row scrolls into view (`block: 'nearest'`). Backdrop: fixed scrim at 60 % behind the panel,
  above the page, below the header; clicking it closes.
- Rows: Titles 32×48 `Poster` thumb (reuse `Art`, `POSTER_SIZE` w92 if `images.js` gains it, lazy, reserved
  ratio); "{year} · {director}"; "{time} left" right-aligned, using the same `formatLeft` ContinueCard
  uses. Collections: a 2-poster fan (reuse `CollectionFan` at a small size if it scales;
  otherwise a `small` variant). People: 40 px initials circle (first + last initial).
- Highlight: `<mark class="search-hit">` styled weight 600, `--cf-text`, underline `#6F675B` 3 px offset; rest
  `#D5CCBE`. Add these as tokens in `base.css` (`--cf-search-hit-underline`, `--cf-text-soft`) rather than literals.
  No amber anywhere in the dropdown.
- Keyboard: first row active on every query change; ↑/↓ across groups (wrap: no, clamp); Enter opens the active
  row; Esc #1 clears and closes, Esc #2 blurs; click outside closes; hover sets active. `/` keeps working through
  `data-global-search`.
- Opening: film → `/films/:id`, collection → `/collections/:id`, person → `/people/:id`. Then clear and close.
- Footer: mono 11 px hints left; "See all {n} results" right, only when any group is capped → `/library?q=`.
- States: fuzzy notice line; no results ("No matches for "{q}"" + "Search looks at titles, collections, actors and
  directors."); index not loaded yet → a single quiet "Loading…" row, never a spinner; index failed → a quiet line
  and Enter falls back to the old `/library?q=` behaviour.
- ARIA: input `role="combobox"` `aria-expanded` `aria-controls` `aria-activedescendant` `aria-autocomplete="list"`;
  panel `role="listbox"`; each section `role="group"` `aria-label`; rows `role="option"` `aria-selected` with stable
  ids (`search-opt-film-12`). A visually hidden `aria-live="polite"` region announces "{n} results" (or the
  no-match text), updated on the same render, not per keystroke spam: only when the count changes.
- Focus ring: the box keeps `--cf-focus-ring` while focused (it's an input; the ring is on the `.searchbox` wrapper
  via `:focus-within`).

**Tests:** RTL for open-at-one-char, group order rendered, "3 of 7" header, first row selected, Enter opens the top
result (navigation asserted), ↑/↓ across groups, Esc twice, outside click, hover, See-all visibility and target,
fuzzy notice, no-results copy, ARIA attributes and live text. Header tests updated.

## PR 4 — `feat(search): full-screen search on iPhone`

- Below 600 px (the existing tab-bar breakpoint) the header box isn't shown; the TabBar "Search" tab opens a
  full-screen sheet (`components/SearchSheet.jsx`) instead of `/library?focus=search`. Use a `?search=1` URL param
  on the current route so the back gesture closes it and "Cancel returns to where you were" is just `navigate(-1)`;
  deep-link without history → Cancel removes the param.
- Sheet: input at top (17 px, so iOS doesn't zoom), clear button, Cancel; the same `SearchResults` with a `size="lg"`
  prop for 52 px rows (posters/circles per `05`); nothing behind (opaque `--cf-bg`). Input autofocuses (iOS opens the
  keyboard only from a user gesture: focus synchronously in the tap handler path).
- No keyboard-hint footer on phone; "See all" stays.
- Tapping a result navigates with `replace` over the `?search=1` entry, so Back from the film goes to where you were.

**Tests:** RTL at the phone breakpoint (mock `matchMedia` as `useHoverNone.test.jsx` does): Search tab opens the sheet, Cancel
restores the previous URL, clear button, tapping a result navigates and closes, same groups as desktop.

## Gates and verification (every PR)

The CLAUDE.md gates, plus CI diff-cover 90 %. PR 1 also boots the `dev` profile against Homebrew Postgres and
checks `curl -i` gives an ETag then a 304. PR 3 and 4: run the app with fixtures and check the done-when list in the
browser (W → Warr narrowing, "ha", "warripr", no network request per keystroke in the network panel), and compare
against `screens/01`–`05`.

## Review notes (adversarial pass on this plan)

- *Stale index after playback:* the memo is dropped on progress writes, but the client only revalidates on tab
  focus. On a single-user server that's fine: you've just come back from the player, which is a route change in the
  same tab, not a visibility change. **Fix:** the Player's exit path also dispatches `cinefilm:library-changed`.
- *Progress writes are frequent* (every few seconds during playback) and each drops the memo. Harmless: nothing
  rebuilds until someone asks for the index. No rebuild per progress write.
- *ETag hash over an unstable serialisation* would cause 200s every time. Build lists in a fixed order (id) and
  serialise with one `ObjectMapper`; a test asserts two builds of the same data give the same ETag.
- *Fuzzy prefix comparison* is easy to get wrong for insertions/deletions at the end of the term ("warripr" vs
  "warrior" is a substitution; "warior" vs "warrior" is an insertion). Comparing against word prefixes of length
  `len ± k` covers it; the tests pin each operation type, including a transposition ("wraaior" ✗ vs "wrarior" ✓).
- *Highlight index mapping* under NFD folding is the most likely source of off-by-one rendering bugs; it has its own
  tests and the fuzzy path highlights the matched word prefix only.
- *Payload size:* ~1,100 films + ~5,000 people ≈ 300 KB raw, ~80 KB gzipped. PR 1 records the real numbers from the
  dev seed and production counts in the PR description; if it's far larger, drop `p` from films and build poster
  URLs lazily from a second call. Not expected.
- *Two search semantics:* the dropdown (with typo fallback) and Library (exact word-prefix only). Documented above;
  shared vectors keep the exact part identical.
- *localStorage holding library data:* titles and names only, no secrets; acceptable for a single-user private app.
  The UI has no sign-out today (`sessionApi.signOut` is unused), so it's cleared on the session-expired event; if
  a sign-out control is added later, it clears it too.
