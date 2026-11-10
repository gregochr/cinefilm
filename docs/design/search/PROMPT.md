# Task: search as you type in the header, with results grouped and typo-tolerant

The header search box should show results **as I type**, narrowing with every letter (W → Wa → War → Warr leaves only *Warrior*). Results are grouped into **Titles, Collections, Actors, Directors**, in that order, and it forgives a typo ("warripr" still finds *Warrior*).

The design is in `screens/`. The colour blocks stand in for posters, and the data is sample data.

| Screenshot | Shows |
|---|---|
| `screens/01-desktop-wa.png` | The desktop dropdown for "wa": every group present, first row selected, "3 of 5" counts, "See all 17 results" footer |
| `screens/02-desktop-ha.png` | "ha": Hancock, The Hangover, the Harry Potter films · the Harry Potter and Hangover collections · Tom Hanks among the actors (the list scrolls to Directors) |
| `screens/03-desktop-typo-warripr.png` | No exact match, so it falls back to close matches, with the notice line |
| `screens/04-narrowing-sequence.png` | w → wa → war → warr → warripr side by side: groups drop out as the query narrows, ending on one result |
| `screens/05-iphone-ha.png` | iPhone: full-screen search with Cancel, the same groups and larger rows |

Before changing anything, look at how the header search and the Library search work now, and what film, collection and person data the client already has. Fit into what's there, and use the existing tokens and components.

## 1. Index and speed
- The library is small (about 1,100 films), so **search on the client**:
  - Load one compact index when the app starts, then cache it.
  - It holds, per film: id, title, year, director names, poster thumbnail, and in-progress time left.
  - It also holds collections (id, display name, owned/total) and people (id, name, role, film count, as actor and/or director).
  - Refresh it after a library scan or import, using an ETag or version number.
- Filter **on every keystroke**, with no wait, debounce or network call per letter. Results should feel instant over the VPN.
- **Normalise** the query and the names: lower-case, accents folded (é → e), punctuation treated as spaces, apostrophes ignored ("ocon" finds O'Connor).

## 2. Matching and ranking
- **Exact (word-prefix) matching:** split the query into terms. Every term must match the **start of some word** in the name.
  - Examples: "ha" matches *Hancock*, *The Hangover* and Tom **Ha**nks; "wa" matches Star **Wa**rs; "star wa" matches *Star Wars: …*.
  - Matches in the middle of a word don't count ("w" doesn't match *Twisters*).
- **Order within a group:**
  1. a match at the very start of the name, ignoring a leading "The"
  2. a match on a later word
  3. then films in progress, then the most recently watched, then alphabetical, ignoring "The"
- **Typo tolerance:** only when the exact search finds **nothing at all**, and the query is 4+ characters. Then:
  - Match each word's start against the query by Damerau–Levenshtein distance: substitution, insertion, deletion, or two letters swapped.
  - Allowed distance: **1** for 4–6 characters, **2** for 7 or more. Rank by distance, then as above.
  - Show the notice line "No exact match for "{query}" · showing close matches" above the groups.
  - Short queries stay exact, so "warr" never drags in "Wars".
- **Highlighting:** show the matched part of the name as weight 600, primary text colour, with an underline (`#6F675B`, 3px offset). The rest of the name is `#D5CCBE`.

## 3. Groups (exactly this order: Titles, Collections, Actors, Directors)

| Group | Row content | Cap |
|---|---|---|
| **Titles** | 32×48 poster · title · "{year} · {director}" · on the right, "{time} left" if in progress | 5 |
| **Collections** | small 2-poster fan · display name (no "Collection" suffix) · "Collection · {owned} of {total} films" | 2 |
| **Actors** | 40px initials circle (or headshot later) · name · "Actor · {n} films" | 3 |
| **Directors** | the same · "Director · {n} films" | 3 |

- A group appears **only when it has matches**. Its header shows the count ("5"), or "3 of 7" when capped.
- Someone who both acts and directs appears in each group that applies.
- **Footer:** keyboard hints on the left, in mono 11px: "↑↓ move · Enter open · Esc close". On the right, "See all {n} results", shown only when results are capped. It opens Library (Films tab) with the search applied.
- **No results, even after the typo fallback:** "No matches for "{query}"", then "Search looks at titles, collections, actors and directors."

## 4. Desktop behaviour
- **Opening:** the dropdown opens under the search box as soon as there's one character, right-aligned to the box.
  - Size: width 540px, max-height `min(720px, 100vh − 120px)`, with internal scrolling.
  - Look: background `#15120F`, 1px `#2A251F` border, 12px radius, large shadow. The page behind dims to 60%.
- **Keyboard:**
  - The **first row is selected automatically**, so **Enter opens the top result**. ↑ and ↓ move through all rows across groups, and the selected row has a `#2A251F` background with an inner hairline.
  - Esc clears and closes the box, and a second Esc blurs it. Clicking outside closes it.
  - `/` focuses the search from anywhere except inside text fields.
- **Opening a result:** a title goes to Film detail, a collection to its Collection page, and an actor or director to their Person page.
- **Accessibility:**
  - The input is `role="combobox"` with `aria-expanded`, `aria-controls` and `aria-activedescendant`.
  - The results are `role="listbox"`, with `role="group"` per section (`aria-label` = the group name) and `role="option"` with `aria-selected` per row.
  - Announce the result count politely ("12 results").
  - Mouse hover moves the selection too.
- **Focus:** the search box shows the standard focus ring while focused.

## 5. iPhone (see `05`)
- Tapping the search icon opens a **full-screen search**:
  - the input at the top (17px text, a clear button) and a **Cancel** button beside it
  - the same grouped results below, in 52px rows
  - nothing behind it
- Same matching, groups, caps and typo fallback as desktop.
- Tapping a result opens it and closes the search. Cancel returns to where you were.

## Done when
- Typing W, Wa, War, Warr narrows the results each time, and at "warr" only *Warrior* is left. Enter opens it.
- "ha" shows *Hancock*, *The Hangover* and the Harry Potter titles, the Harry Potter and Hangover collections, and Tom Hanks under Actors.
- "warripr" finds *Warrior* with the "close matches" notice.
- Results appear with no visible delay; no network call per keystroke.
- Keyboard and screen-reader behaviour works as described.
- iPhone matches `05`.
- **Tests cover:**
  - word-prefix matching, including multi-word queries and accents
  - ranking ("The" ignored, start before later word, in-progress first)
  - group order and caps
  - the typo distances at 4, 6 and 7+ characters
  - the fallback only firing when there are no exact matches
  - Enter opening the first result
- Commit as separate conventional commits: index, matching/ranking, desktop dropdown, iPhone.
