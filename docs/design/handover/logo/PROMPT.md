# Task: add the CineFilm logo, tab icons and the new buffering animation

CineFilm has a logo: the **Gate**. It's a rounded cine-film frame left open on the right so it reads as a **C**, with an amber lamp dot inside. Add it across the app, and replace the plain spinner in the player with the new buffering animation.

## What's in this bundle

| File | What it is |
|---|---|
| `screens/01-logo-board.png` | The logo at large size, in the header, as tab icons and as the home-screen icon, plus a buffering still |
| `screens/02-buffering-sequence.png` | Three moments of the buffering animation: holding, mid-advance, holding again |
| `assets/logo-mark.svg` | The mark (64×64 viewBox), amber `#E8B25A`, transparent background |
| `assets/favicon.svg` | The same mark with slightly heavier strokes so it holds up at 16px |
| `assets/favicon-16.png`, `assets/favicon-32.png` | PNG fallbacks |
| `assets/app-icon.svg`, `assets/apple-touch-icon.png` (180), `assets/icon-192.png`, `assets/icon-512.png` | Home-screen and web-app icons: the mark on `#0E0C0A`, square (iOS rounds the corners itself) |
| `reference/buffering-demo.html` | A working, self-contained reference of the buffering animation (open it in Safari). Port it into a React component; don't iframe it |

Before changing anything, look at how the app's `index.html`/head, header, sign-in page, empty-library state and player buffering state are built now. Fit into what's there, and use the existing design tokens: amber = `--cf-amber` or its equivalent.

## 1. Tab icon, home-screen icon and web-app manifest
- Copy the assets into the public/static folder and add to the document head:
  ```html
  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
  <link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
  <link rel="icon" href="/favicon-16.png" sizes="16x16" type="image/png">
  <link rel="apple-touch-icon" href="/apple-touch-icon.png">
  <meta name="theme-color" content="#0E0C0A">
  ```
- If there's a web app manifest, add `icon-192.png` and `icon-512.png`, with `"background_color": "#0E0C0A"`. If there isn't one, create a minimal one: name "CineFilm", display "standalone", those two icons.
- Remove any default framework favicon.

## 2. A `LogoMark` component
- Inline SVG built from `logo-mark.svg`, so it stays sharp and can use the colour token.
- Props: `size` (px, default 30) and an optional `title`. It's decorative (`aria-hidden`) unless a title is given.

## 3. Where the logo appears
- **Header:** the mark (30px) sits left of the "CineFilm" wordmark, with a 12px gap and both vertically centred. The mark and wordmark together form one link to Home, with `aria-label="CineFilm — Home"`. Keep the wordmark as it is (Instrument Serif italic). On phones, the same lockup at 26px.
- **Sign-in / session expired:** the mark at 48px, above the wordmark.
- **Empty library (first run):** the mark at 64px, above "The house lights are up".
- **Not found / error pages,** if there are any: the mark at 48px above the message.
- Nowhere else. Don't add it to the player chrome, cards or the footer.

## 4. Buffering animation (replaces the spinner)
Port `reference/buffering-demo.html` into a `BufferingIndicator` component. Use it wherever the player currently shows the spinner and "BUFFERING".

- **What it does:**
  - The Gate mark sits in a 160×96 window. The lamp throws a beam out through the open side of the C, fading to nothing, and the beam flickers.
  - Three tiny dust specks drift through the beam.
  - Every 2.2 s the frame **advances**: a film strip moves up one frame, with a dark frame line passing through the window.
  - The lamp dot flickers too.
- **Timings:** copy them exactly from the reference: frame advance 2.2 s (hold for 45%, move over 30%), lamp flicker 1.1 s, beam flicker 1.6 s, dust 1.8 / 2.3 / 2.0 s with staggered delays.
- **Seamless loop:** the strip holds two identical frames, so the jump back to the start can't be seen. Keep that structure.
- **Label:** "BUFFERING" in IBM Plex Mono 12px, letter-spacing 0.14em, below the window. The wrapper has `role="status"` and `aria-live="polite"`.
- **Wait before showing:** only show the indicator after **400 ms** of continuous buffering, so short stalls don't flash it. Hide it as soon as playback resumes.
- **Reduced motion:** with `prefers-reduced-motion: reduce`, show a still mark with the beam at full strength, no dust and no movement.
- **Unique IDs:** the SVG gradient id must be unique per instance (e.g. React's `useId`) so two instances never clash.
- **Keep the rest of the buffering state:** the controls stay hidden, and the step-down toast and quality indicator still work as before.

## Done when
- The Gate icon shows in the Safari and Chrome tabs. Adding CineFilm to the iPhone home screen shows the dark square icon with the amber Gate.
- The header shows the mark beside the wordmark and links Home. The sign-in and empty-library screens show the larger mark.
- When buffering, the new animation runs smoothly in Safari on Mac and iPhone, appears only after 400 ms, and is still when reduced motion is on.
- No old spinner remains anywhere.
- Commit as separate conventional commits: icons/manifest, `LogoMark` and its placements, `BufferingIndicator`.
