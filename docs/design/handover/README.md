# CineFilm — UX handover (direction: Projection Booth)

CineFilm is a private, single-user, Plex-style movie server for a few hundred self-ripped films. It's the companion to CineLight, which dims the Philips Hue lights when a film starts. There are no accounts, no social features and no "content provider" feel: it should feel like **my own cinema**.

This bundle is the design handover for the **web front end** (React). It contains:

| Path | What it is |
|---|---|
| `README.md` | This spec: visual language, screens, behaviour rules and decisions |
| `screens/*.png` | The visual target for every screen (2× retina) |
| `design/tokens.css`, `design/tokens.json` | Colours, type, radii, spacing and focus ring as CSS variables / JSON |
| `reference-markup/*.dc.html` | The design comps' source, for exact values (paddings, sizes, copy) |

> **About `reference-markup/`.** These are design-tool comps written in a small templating format (`{{hole}}`, `<sc-for>`, `<sc-if>`, a `renderVals()` class). Use them to look up exact values and copy. Don't port the format: build idiomatic React components. Inline styles in the comps should become tokens and components. Poster and backdrop colour blocks are placeholders for TMDB images. Film data in the comps is sample data.

---

## 1. Targets

- **Primary:** Safari on a MacBook, at home and away (hotel or office Wi-Fi over VPN).
- **Must work:** iPhone Safari (responsive; browse and watch).
- **Later, don't block:** Apple TV (10-foot, remote-driven focus UI) and a native iPhone app. Keep a real focus model and keep data and UI logic separable so these can reuse it.

## 2. Visual language

- Dark, cinematic and calm. **Posters and backdrops supply the colour; the chrome recedes.**
- Warm black ground. Serif display type for film titles and section headings. Inter for UI text. **IBM Plex Mono for timecodes and technical data** (player times, admin stats, file names).
- **Amber is rare.** Use it only for:
  - the primary action (Play/Resume, Import, Scan now)
  - progress fills
  - the underline on the current menu item
  - the small eyebrow label ("Pick up where you left off")

  Never use it for general buttons or icons.
- **Focus ring** (keyboard, and later the TV remote): `0 0 0 2px var(--cf-bg), 0 0 0 4px var(--cf-text)`. Posters and cards also scale 1.03 and gain a soft shadow. It's on screen in `06-library` (first poster), `11-player-end` (primary button) and `13-states` (password field). Never remove outlines without this replacement.
- Any nod to old cinema stays subtle: the serif, the warm lamp tone and the "house lights" copy. No film-strip graphics.
- **Fast and quiet:** no autoplaying trailers, no carousels that move on their own, no video backgrounds on browse pages.

Fonts (Google Fonts or self-hosted):
- **Instrument Serif:** 400 and 400 italic. The wordmark "CineFilm" is set in the italic.
- **Inter:** 400, 500 and 600.
- **IBM Plex Mono:** 400 and 500.

See `design/tokens.css` for every value.

## 3. Screens

| Screenshot | Screen | Notes |
|---|---|---|
| `01-home-desktop.png` | Home | See "Home" below |
| `02-film-detail-desktop.png` | Film detail | See "Film detail" below |
| `03-film-detail-editions.png` | Film detail, film with several editions (Blade Runner) | Edition picker; see §6b |
| `04-person-desktop.png` | Person (actor or director) | Reached by clicking any cast or director name. Shows only films **in my library**, with the role played ("as Neil McCauley"). It's not a full filmography. |
| `05-collection-desktop.png` | Collection | Films I own in release order, plus missing ones as dashed "Not in your library" tiles; see §6b |
| `06-library-desktop.png` | Library | See "Library" below |
| `07-player-controls-quality-menu.png` | Player, controls visible, quality menu open | See §4 |
| `08-player-audio-subtitles-panel.png` | Player, Audio & Subtitles panel open | See §4 |
| `09-player-scrub-preview.png` | Player, hovering the scrubber | Frame preview and time; see §4 |
| `10-player-buffering-stepdown-toast.png` | Player, buffering, with the step-down toast and controls hidden | See §4 |
| `11-player-end-of-film.png` | Player, end of film | See §4 |
| `12-admin.png` | Admin | See "Admin" below |
| `13-states.png` | Edge states | See §5 |
| `14-home-iphone.png` | Home, iPhone (390 wide) | Hero stacks; rows scroll sideways with snap; bottom tab bar: Home / Library / Search |
| `15-film-detail-iphone.png` | Film detail, iPhone | Full-width Resume; "Start over" and "Mark watched" side by side; settings as list rows (Quality, Subtitles, Audio, Dim the lights) |
| `16-player-iphone-landscape.png` | Player, iPhone landscape | Large touch targets: ±10 s and play/pause in the centre, scrubber with 28px hit height. See decision 10 for fullscreen |

**Home**
- Hero: "Pick up where you left off" shows the most recent in-progress film.
- Rows: "Continue watching", "Recently added", "Not watched in years", and a weekly theme ("This week: the 1970s", captioned "A new theme every Monday"). Collections live in Library, not on Home (decision 16).
- The rows are static grids with a "See all" link, not carousels.

**Film detail**
- Backdrop with a scrim fading into the page; poster overlaps the backdrop.
- Primary button: "Resume from 1:04:31". Secondary: "Start from beginning". A "Mark as watched" icon button, then the "Dim the lights" switch (CineLight).
- Progress bar with time left.
- Quality, Audio and Subtitles pickers.
- A link to the film's collection in the meta line, when it belongs to one.
- An edition picker when there's more than one edition (§6b).
- Director and cast, each linking to their Person page.
- File line: "Original file · 1080p HEVC · 5.1 · added …".

**Library**
- Poster grid. The heading count reads "24 films · sorted by recently added".
- Search matches title, actor and director, live as you type.
- Sort: Title / Year / Recently added. Title sort ignores a leading "The".
- Filters: Genre, Decade, and Show (All / Unwatched / Watched).
- Two tabs beside the heading, each its own URL: **Films** (`/library`) and **Collections** (`/library/collections`),
  with counts. A "Group collections" switch (off by default) folds each collection into one tile in the Films grid.
  See `docs/design/library-collections/` for the target screens and rules.
- Watched films get a check badge.
- No-results state: "Nothing on the shelf", with a "Clear search and filters" button.
- Shortcut hints sit along the bottom.

**Admin** (only I see it, so it can be plain). It has five panels:
- **Plex import:** progress, the film currently importing, and matched / need-a-match counts.
- **Library scan:** folder, last scan, "Scan now".
- **Transcode worker:** online/offline, heartbeat, active sessions (film, Original → 720p, device, Remote, speed).
- **Pre-made versions (for travel):** a switch for "Automatically prepare films in Continue watching", a per-film status list (ready, making with %, queued), storage used, and "Prepare a film…".
- **Films needing attention**, including "3 files match one film", with a "Group as editions" action.
- **Fix match:** search TMDB, then radio-pick from results showing poster, year, director, cast and **runtime**. The runtime is often what tells same-titled films apart, e.g. The Wicker Man 1973 vs 2006.

### Responsive

- **Desktop:** content `max-width: 1360px`, 40px side padding. Poster grid `repeat(auto-fill, minmax(160px, 1fr))`.
- **Phone (≤ 600px):**
  - 16px gutters
  - the hero card stacks
  - rows become horizontal scrollers with scroll-snap
  - the header nav becomes a bottom tab bar
  - detail settings become list rows
- **Not drawn yet** (follow the same patterns): mobile Library, mobile Person, mobile Admin.

### Images and data (remote use matters)

- All posters and backdrops are `loading="lazy"`, sized to need. Suggested TMDB sizes: posters `w342` (grid) or `w500` (detail); backdrops `w1280` desktop or `w780` phone.
- Reserve the aspect ratio (2:3 posters, 16:9 stills) so nothing shifts while loading.
- Skeletons are **static** (no shimmer).

## 4. Player

Custom controls over HTML5 `<video>`.

**Layout**
- Top-left: back button, then the title in serif and the year.
- Top-right: the network/quality indicator.
- Bottom, scrubber row: elapsed time, then a scrubber that shows the buffered range, then **remaining** time ("−01:45:29"), all in mono.
- Bottom, button row: play/pause, −10 s, +10 s, volume, then on the right Audio & Subtitles, Quality and Fullscreen.

**Scrubber preview (`09`).**
- On hover or drag, a 256px-wide 16:9 frame preview appears above the pointer, with the time in mono underneath. A thin marker on the track shows the hover point.
- The track thickens from 4px to 6px on hover.
- Previews come from a **sprite sheet generated at scan time**, one frame every 10 s.
- If a film has no sprite yet, show just the time.

**Keyboard** (scoped to the player only, never global):

| Key | Action |
|---|---|
| Space | Play/pause |
| ← / → | −10 s / +10 s |
| ↑ / ↓ | Volume |
| F | Fullscreen |
| C | Cycle captions: Off → English → English (forced) |
| A | Open the Audio & Subtitles panel |
| Q | Open the quality menu |
| Esc | Close menu, then exit fullscreen |

**Auto-hide.** Controls hide after **3 s** without mouse or keyboard input while playing. Any movement or key brings them back. They never hide while paused or while a menu is open. When hidden, only a 3px hairline progress bar remains at the bottom (`10`).

**Network/quality indicator.**
- A small pill in mono: `Auto · Original · Home`, `720p · Remote`, `480p · Remote · slow`.
- The dot is green at home and amber when remote or degraded.
- This is the **only** "you're remote" cue (decision 7).

**Quality menu**
- Options: Auto (default), Original, 1080p, 720p, 480p.
- Each row shows its bitrate in mono. Auto shows what it's currently using ("now Original").
- The current choice gets an amber tick.
- **Worker offline:** Auto and any lower quality that **isn't pre-made** are disabled. A footer reads "Lower qualities unavailable right now — the transcode worker is offline. Pre-made versions still play." (`13`).

- Pre-made versions are marked "· pre-made" after the bitrate (§6b).

**Audio & Subtitles panel (`08`).** One panel with two columns, opened by the captions button or A.
- **Audio:** each track with its codec or source in mono, e.g. English 5.1 · DTS, English Stereo · AAC, Commentary · Michael Mann.
- **Subtitles:** Off (note: "forced if needed"), English, English (forced).
- The choice is remembered per film (decision 2).
- If Safari can't decode a track, e.g. TrueHD, the server transcodes the audio only; the video can still direct-play.

**Buffering:** a centred spinner plus "BUFFERING" in mono. Controls stay hidden unless the user interacts.
While a film is starting or resuming (before its first frame plays) the same indicator reads "PREPARING"; it reads
"BUFFERING" only for a stall after playback has begun (owner, 2026-11-10). The time readout shows the resume point
from the moment the player opens, never 0:00.

**Auto step-down:** a toast at the top centre reads "Switching to 720p for smoother playback". It shows for about 4 s, is non-blocking, and plays no sound.

**End of film**
- Appears when playback reaches the credits marker (decision 1).
- Shows "The end", the title, and the year · director.
- Primary: **Mark as watched**, focused by default.
- Secondary: **Back to library**.
- Text link: "Keep watching the credits". Esc does the same.
- No "Up next".

## 5. States (`13-states.png`, plus where noted)

| State | Copy and behaviour |
|---|---|
| Loading | Static skeleton blocks at the real layout's sizes; no shimmer |
| Empty library (first run) | "The house lights are up", then "Nothing in the library yet. Bring your films, posters and watch history across from Plex, or point CineFilm at a folder." Buttons: **Import from Plex** (primary) / Scan a folder |
| No search results | In Library: "Nothing on the shelf", then "No films match "…" with these filters…", with a **Clear search and filters** button |
| Film has no subtitles | The Subtitles field reads "None for this film" (dashed, inert). The player's captions button is dimmed and C does nothing. Link: "Find subtitles in Admin" |
| Transcode worker offline | See the quality menu above |
| Remote on a slow connection | Indicator `480p · Remote · slow`, plus a one-off card per session (decision 11): "You're on a slow connection", then "Playing at 480p to avoid stalls. Auto will step up if things improve." Buttons: OK / Choose quality |
| Session expired while away | Sign-in card: "You've been signed out while you were away. Sign in again and you'll be back where you were." Password field and **Sign in**. After sign-in, return to the exact route and playback position |

## 6. Decisions (agreed with Chris, 8 Nov 2026)

1. **Watched:** a film is marked watched automatically when playback reaches the **end-credits marker**, or **92%** of runtime if there's no marker. **Resume rewinds 10 s** from the saved position; the button label still shows the saved position.
2. **Quality and subtitles:** both the detail page and the player have them. The last choice is **remembered per film**, and the detail page pre-selects it.
3. **Auto step-up:** after a step-down, Auto may step back **up one level at a time** after about **60 s** of healthy throughput. Each change shows a quiet toast; the step-down copy is above, and step-up uses the same pattern, e.g. "Back to 1080p".
4. **Weekly Home row:** one theme (a decade, genre or director drawn from the library) is chosen **each Monday**. It never changes while the page is open.
5. **Search:** **live as you type** (debounced) across title, actor and director. Names everywhere link to a **Person page** listing only owned films.
6. **Forced subtitles:** when Subtitles is **Off** and the film has an English (forced) track, the forced track plays automatically. Choosing full English replaces it. The UI shows "Off · forced when needed".
7. **Remote cue:** only the corner indicator in the player. Nothing on browse pages.
8. **CineLight:** a **"Dim the lights"** switch next to Play on Film detail, **on by default**. Turning it off skips the Hue dimming for that playback only. CineFilm needs to tell CineLight about the skip, e.g. a flag on the play event. There's no CineLight indicator in the player.
9. **Admin access:** through the settings icon in the header (single user; no need to hide it).
10. **iPhone fullscreen:** custom controls inline. **Fullscreen hands off to Safari's native player**, which gives AirPlay, picture-in-picture and lock-screen controls. The custom quality menu isn't available while in native fullscreen, which is accepted.
11. **Slow-connection message:** the card in §5 shows **once per session**; after that, only the indicator.

## 6b. Additions agreed afterwards (from comparing with Plex, Jellyfin and Netflix)

12. **Editions.** One film can have several files. Examples: The Final Cut, Director's Cut, Theatrical Cut.
    - **Data model:** `Film` 1—n `Edition` 1—n `MediaFile`. Each edition has a name, a year, a runtime, and a note ("No voice-over").
    - **Film detail:** an edition picker (radio cards) that only appears when there's more than one. The Play button names the edition ("Play The Final Cut") and the runtime in the meta line follows the choice.
    - **Remembered:** the default edition, and the last one played, per film.
    - **Kept per edition:** resume position and watched status. Library and Home still show one poster per film.
    - **Admin:** scanning flags several files matching one TMDB id; the admin then confirms "Group as editions" and names each.
13. **Audio track choice.** Via the Audio & Subtitles panel in the player (§4) and an Audio picker on Film detail. Every audio stream needs its language, channels, codec and title (e.g. "Commentary — Michael Mann").
14. **Scrubber previews.** A sprite sheet and an index (WebVTT thumbnails or JSON) are made at scan time, with a frame every 10 s at about 256px wide. They're lazy-loaded when the player opens.
15. **Pre-made versions (travel copies).**
    - 720p and 480p copies are encoded ahead of time and stored beside the original.
    - **Played directly:** they need no transcode worker, so they still play when it's offline.
    - **Auto:** prefers a pre-made version whenever one matches the target quality.
    - **Automatic queue:** films in Continue watching are prepared automatically (the switch is on by default). The admin can also queue any film by hand.
    - **In the UI:** marked "· pre-made" in the quality menus, and the Admin card shows status and storage used.
16. **Collections.**
    - **Source:** TMDB collections (e.g. Alien, Blade Runner).
    - **Two films make a collection** (owner, 2026-11-09). A TMDB collection counts only once at least two of
      its films are in the library. With one, that film looks like any other: no Collections tab card, no
      grouped tile, no link on Film detail. TMDB puts any film with a sequel in a collection, and a "collection" of
      one owned film plus a "Not in your library" tile is noise.
    - **Display name:** a trailing " Collection" is dropped from TMDB's name everywhere ("Harry Potter Collection"
      shows as "Harry Potter").
    - **Collection page:** owned films in release order, followed by missing parts as dashed "Not in your library" tiles. The missing tiles can be hidden in Admin.
    - **Elsewhere:** the Collections tab in Library, the optional "Group collections" tiles in the Films grid, and a
      link to the film's collection on Film detail. Home has no Collections row and Library no Collection filter
      (owner, 2026-11-09; `docs/design/library-collections/`).

## 7. Accessibility

- Use real elements: `<button>`, `<a href>`, `<input>` with a `<label>`, `<select>`. Icon-only buttons get an `aria-label` (Back, Settings, Mark as watched, Fullscreen…).
- Segmented controls (Sort, Show) are button groups with `aria-pressed`. Player menus use `role="menu"` with `menuitemradio` and `aria-checked`.
- The scrubber is `role="slider"`, with `aria-valuetext` in words ("1 hour 4 minutes 31 seconds").
- Status messages (toast, buffering, indicator) are `role="status"` (polite).
- Text contrast is at least 4.5:1. Muted text `#A39A8B` on `#0E0C0A` passes, so don't go dimmer for body text.
- Touch targets are at least 44px.

## 8. Not yet designed / left to implementation

- Mobile Library, mobile Person and mobile Admin (follow the patterns in §3).
- The step-up toast is described in decision 3 but not drawn.
- Apple TV: build the focus model now (§2 ring and scale) so it can carry over.
