# Task: build the "Edit editions" panel

A film can exist as several files: different cuts (Theatrical, Extended), duplicate copies of one cut, or one cut split across files (pt1, pt2). The scanner groups most of these itself. When it can't tell, I need a panel to decide:
- which file belongs to which edition
- the part order
- which edition is the default
- which files aren't this film at all

It's also the only place each file's technical details are listed.

**It opens from:**
- the "Edit editions" button on a "Films needing attention → Several files" row in Admin
- Film detail → "Edit editions…"

It's a **modal dialog** on desktop and a **full-screen sheet** on phones (≤ 600px).

## What's in this bundle

| File | Shows |
|---|---|
| `screens/01-typical-two-towers.png` | Extended Edition in 2 parts plus a Theatrical Cut, the suggestion applied, and the focus ring on the first Edition select |
| `screens/02-untagged-copies.png` | Two untagged files with the same runtime, suggested as "Original" / "Original 2", one marked **Missing** |
| `screens/03-validation-errors.png` | A part gap ("Parts 1 and 3 — part 2 is missing.") and a duplicate name, with Save disabled |
| `screens/04-not-this-film.png` | Rocky V moved to the "Not this film" group |
| `screens/05-loading.png` | A static skeleton |
| `screens/06-save-failed.png` | An error banner inside the dialog |
| `screens/07-phone.png` | The full-screen sheet: stacked rows, full-width pickers, sticky footer |
| `reference/edit-editions.dc.html` | The design comp's source, with the exact styles, copy and **the validation logic in `renderVals()`**. It's sample data in a design-tool format: read it for values and rules, then build idiomatic React. Don't port the format. |

Before building, look at the data model from the editions/parts work (Film → Edition → MediaFile with `partNumber`), the existing Admin attention list and Fix match, and Film detail. Fit into what's there, and use the existing tokens and components.

## Layout (desktop)
- **Backdrop:** the page dims (`rgba(5,4,3,0.72)`).
- **Dialog:** 920px wide, max-height 900px, background `#15120F`, 1px `#2A251F` border, 12px radius, large shadow. The header and footer stay fixed and the body scrolls.
- **Header:**
  - the label "Edit editions" (12px uppercase, letter-spacing 0.12em, muted `#A39A8B`)
  - under it, the film title in Instrument Serif 32px, plus " · {year}" in muted text
  - a round × close button (40px), top right
- **Suggestion line:** a small outlined "SUGGESTED" pill (11px uppercase, `#4A4339` border), then the suggestion in muted 14px. The suggestion is **already applied** to the controls. Examples:
  - "From the edition tags and part numbers"
  - "Runtimes 2h 35m and 2h 51m — likely Theatrical and Extended"
  - "Runtimes match (2h 50m) — likely copies of one cut. Kept apart so you can compare; rename one, or mark it Not this film."
  - "Runtimes 1h 31m and 1h 44m — these may be different films"
  - When a file is set aside as Not this film because its name differs, and the runtimes are within 60 seconds or 5 minutes of each other, the line no longer says "different films" (2026-11-14, `docs/engineering/repair-tmdb-evidence-plan.md` PR A):
    - "The names differ but the runtimes match (1h 57m) — if it's the same film, move it back to keep it here"
    - "The names differ but the runtimes are close (1h 52m and 1h 57m) — if it's the same film, move it back to keep it here"
- **Files, grouped by edition:**
  - One card per edition (1px `#2A251F` border, 10px radius).
  - **Group header:** on a `#1A1713` strip, the edition name in 12px uppercase, then "{n} parts · {total runtime}" (or "single file · …") in mono.
  - **Group errors** go in a row under the header.
  - **Rows within a group:** sorted by part. Desktop grid `minmax(0,1fr) 220px 130px auto`, gap 14px. The columns are:
    1. **File:**
       - **Name** in IBM Plex Mono 13px, with **middle truncation that always keeps the last 22 characters visible**, so "…ded Edition} - pt1.m4v" survives.
       - **Full name** on hover (`title`) and on keyboard focus (the span is focusable and carries an `aria-label`).
       - **Meta line** under it in mono 12px, muted: "1h 54m · 1080p · HEVC · 14.2 GB". If the file isn't on disk, add a muted outlined "MISSING" pill.
    2. **Edition:** a labelled select listing the existing editions plus "New edition…". Choosing "New edition…" shows a name field under it: max 32 characters, with a live "{n}/32" counter inside it on the right.
    3. **Part:** a labelled select: "Single file", Part 1–8.
    4. **Not this film:** a secondary pill button.
  - **Changing a file's edition moves its row into that group immediately**, and the group headers and totals update live.
- **"Not this film" group:** at the bottom, with a dashed border. Its header reads "NOT THIS FILM", then "When you save, each of these becomes its own unmatched film. Fix it with Fix match in Admin." Each row has a "Move back" button.
- **"Editions after saving":**
  - one row per resulting edition, 48px tall: a radio for the default, the name, and "{parts} · {runtime}" in mono
  - the selected one gets a `#211C16` background and a "Default edition" label on the right
  - radios use `accent-color: #EDE6DA`, **not amber**
  - wrap them in a `fieldset` with the legend "Default edition"
- **Footer:**
  - On the left, a muted note: "Resume positions and watched state are kept for each edition." When there are errors it reads "Fix {n} problem(s) to save.", and while loading "Loading files…".
  - On the right, **Cancel** (secondary) and **Save**.
  - **Save** is the only amber in the panel (`#E8B25A`, text `#1A1206`). When disabled it's `#2A251F` with `#6F675B` text, and it's disabled while there are errors or the panel is loading.
- **Colours:**
  - **Errors:** text `#F0876A`, border on the offending field `#C2604A`. Never amber.
  - **Error banner:** background `#2A1712`, border `#5A2A1E`, text `#F3B4A2`, heading `#F7CABB`.

## Validation (inline, plain language; see `03` and `renderVals()` in the reference)
- Every file is in exactly one edition or marked Not this film. The edition select makes this structural.
- **Within an edition:**
  - More than one file and any set to "Single file" → "Single file only works when an edition has one file — give each file a part number."
  - Parts must be 1..n: a duplicate → "Two files are both part {n}."; a gap → "Parts 1 and 3 — part 2 is missing."
  - One file set to a part other than 1 → "Only one file — set it to Single file or Part 1."
- **New edition names:**
  - blank → "Give this edition a name."
  - more than 32 characters → "Keep the name to 32 characters."
  - the same as another edition, ignoring case → "Another edition is already called {Name}."
- **Editions and default:**
  - Everything marked Not this film → "Keep at least one file with this film."
  - The default edition no longer exists → "Choose a default edition."
- Errors appear next to the offending control, or under the group header for part errors. The footer counts them, and Save stays disabled until there are none.
- **Server:** run the same validation on save and return field-level errors in the same wording.

## States
1. **Typical** (`01`).
2. **Untagged copies** (`02`).
3. **Errors** (`03`).
4. **Not this film** (`04`).
5. **Loading** (`05`): the header shows straight away (the film title is already known), the body is a **static** skeleton (no shimmer), and Save is disabled.
6. **Save failed** (`06`): a `role="alert"` banner at the top of the body. "**Couldn't save.** A library scan is running — try again when it finishes. Your changes are still here." Use a generic fallback for other errors: "**Couldn't save.** Something went wrong — try again." **Keep the user's edits.**
7. **Phone** (`07`):
   - a full-screen sheet with 16px gutters
   - the title at 26px
   - each row stacked: name, meta, then Edition, Part and Not this film at full width
   - the footer sticky at the bottom with the note above two equal-width buttons

## Saving
- **On Save:**
  - Apply the editions, the part numbers and the default.
  - Files marked Not this film are detached into new unmatched films and appear in Admin → Films needing attention for Fix match.
  - **Keep each edition's resume position and watched state.** If an edition is renamed, its history goes with it.
- **After saving:**
  - Close the dialog and show a short toast: "Editions saved".
  - If opened from Admin, remove the attention row once it's resolved.

## Accessibility and keyboard
- `role="dialog"`, `aria-modal="true"`, `aria-labelledby` = the title. **Focus is trapped** inside.
- **Opening:** focus goes to the first Edition select (or the close button while loading).
- **Closing:** focus returns to the button that opened the panel.
- **Esc** works like Cancel. If there are unsaved changes, still close (it's a single-user app, so there's no confirm prompt), unless you'd rather add one; ask me.
- Tab order: header → each row (name, Edition, name field, Part, Not this film) → Not this film group → default radios → footer.
- **Real labels on every control:** "Edition" and "Part" are visible labels, and the new-name field has `aria-label="New edition name"`.
- Error messages are `role="alert"` and linked to their control with `aria-describedby`.
- **Focus ring on everything:** `0 0 0 2px #0E0C0A, 0 0 0 4px #EDE6DA`.
- **Motion:** only a simple fade in and out (about 150 ms). With reduced motion, no animation.

## Done when
- The panel opens from both entry points, pre-filled with the scanner's suggestion and the line explaining it.
- Editing regroups rows live, all the validation messages above appear in the right place, and Save is disabled until they're fixed.
- "Not this film" files become new unmatched films on save.
- Resume and watched state survive the save, including renames.
- Phone layout matches `07`.
- Keyboard, focus trap and focus return all work.
- **Tests cover** every validation rule, with the exact messages; regrouping; the default edition being removed; Not this film round-trip ("Move back"); save failure keeping the edits; and resume/watched preserved after rename.
- Commit as separate conventional commits: validation module (shared by client and server), dialog UI, save endpoint, entry points.
