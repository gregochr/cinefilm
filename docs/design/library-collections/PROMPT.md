# Task: move Collections into Library, and add "Group collections"

Collections currently sit as a long grid at the end of Home. Move them into a **Collections tab in Library**, as Plex and Jellyfin do, and add an optional switch that folds each collection into one tile in the Films grid.

The design is in `screens/`. Poster colour blocks stand in for real artwork, and the film data is sample data.

| Screenshot | Shows |
|---|---|
| `screens/01-library-films-tab.png` | Library, Films tab: tabs at top right, the "Group collections" switch (off), and no Collection filter |
| `screens/02-library-collections-tab.png` | Library, Collections tab |
| `screens/03-library-films-grouped.png` | Films tab with "Group collections" on |
| `screens/04-collection-page.png` | The existing Collection page (unchanged; shown for reference) |
| `screens/05-home-without-collections.png` | Home with the Collections section removed |

Before changing anything, look at the existing Home, Library, collection data and the Collection page in this codebase. Fit into what's there, and use the existing design tokens and components.

## 1. Remove Collections from Home
- Delete the Collections section at the end of Home entirely (see `05`).

## 2. Library tabs
- Add two tabs, top right on the same row as the "Library" heading: **Films {count}** and **Collections {count}**. The counts are in IBM Plex Mono 12px, muted.
- The active tab has primary text and a 2px amber bottom border; the inactive one is muted.
- Use `role="tablist"` and `role="tab"` with `aria-selected`. Arrow keys move between the tabs.
- Each tab is its own URL (e.g. `/library` and `/library/collections`), so back/forward and deep links work.
- **Remove the "Collection" filter** from the Films tab; the Collections tab replaces it.

## 3. Collections tab (see `02`)
- **Toolbar:**
  - Search ("Collection name").
  - Sort: **Name** (default; ignore a leading "The"), **Recently added to** (most recent film added to the collection), **Size** (owned count, largest first).
  - Show: **All**, **Complete** (every film owned), **Missing films** (at least one not owned).
- **Grid:** `repeat(auto-fill, minmax(240px, 1fr))`, gap 36px rows and 24px columns.
- **Each card:**
  - The poster area is 190px high, on the surface colour, with a 10px radius.
  - The name in the serif, 24px.
  - Under it, "{owned} of {total} films", plus "✓ Complete" when owned equals total.
  - The card links to the collection's page.
- **Display name:** strip a trailing " Collection" from TMDB's name ("Harry Potter Collection" becomes "Harry Potter"). Use the same stripped name on the Collection page heading and the Film detail link.
- **Poster fan:** these rules fix the bugs in the current Home cards.
  - Show **min(owned, 3)** posters: 1 owned is a single poster, 2 are two fanned, 3 or more are three. **Never render empty placeholder slots.**
  - Centre the fan horizontally in the card.
  - Order: the earliest-released owned film at the front; the next ones fan back and to the left.
  - **No opacity on posters.** Use `filter: brightness(0.6)` on the rearmost poster and `brightness(0.8)` on the middle one. Each poster in front casts a left shadow: `-8px 0 18px rgba(0,0,0,0.5)`.
- Lazy-load poster images.

## 4. "Group collections" switch on the Films tab (see `01` and `03`)
- Add a switch labelled **Group collections** to the Films toolbar. It's **off by default**. Remember the setting per user in the same place other Library preferences are kept, or in `localStorage` if there's nowhere else.
- **When on,** every film that belongs to a collection is folded into one **collection tile** in the grid:
  - **Shape:** the same size as a poster (2:3). Surface background, a 1px border in the line colour, 8px radius.
  - **Inside:** a fan of up to 3 of its posters, using the same min(owned, 3) and brightness rules as above.
  - **Count:** a round badge, top right, with the number of owned films (Plex Mono 12px).
  - **Name:** the collection name in the serif over a bottom scrim.
  - **Caption under the tile:** "Collection · {n} films".
  - **Click:** opens the collection's page.
- **Where the tile sits:** the collection takes the place its "lead" film would have under the current sort:
  - **Title:** the collection's display name.
  - **Year:** the earliest film's year.
  - **Recently added:** the most recently added film.
- **Filters:** Genre, Decade and Show apply to the films first. A collection tile appears if at least one of its films passes, and its count shows the films that pass.
- **Search** never groups: if a search term is entered, show individual films even with the switch on.
- **Heading count:** while grouped, it reads "{films} films in {tiles} tiles · sorted by …".
- Films not in any collection show as normal.

## Not in scope
- Metadata, poster and matching problems, such as unmatched rips titled "GLADIATOR_II.Title1" and films with no poster. The Plex import should bring Plex's matches and artwork, so revisit these after the import has run.

## Done when
- Home has no Collections section.
- Library has working Films and Collections tabs with their own URLs.
- The Collections tab searches, sorts and filters as above.
- No card ever shows an empty poster slot, and no poster is see-through.
- No "Collection" suffix appears anywhere in the UI.
- "Group collections" folds and unfolds correctly under every sort, with the filters, and is ignored while searching.
- Tests cover:
  - the fan-count rule (1, 2, 3 and 5 owned)
  - stripping the name suffix
  - the Complete and Missing films filters
  - where a group sits under each sort
  - filter interaction while grouped
- Commit as separate conventional commits: Home, tabs, Collections tab, grouping.
