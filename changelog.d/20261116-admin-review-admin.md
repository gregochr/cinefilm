### Fixed — Admin frontend fixes from the final review

The rail's Playback pill counts what Playing now lists (direct play included), not only live HLS sessions. "All in
Subtitles" under the forced To do group opens the forced tab, and the Subtitles tab now lives in the address
(`#foreign`, `#forced`). The Playback section's transcoding read polls quietly like the summary: hidden tabs pause it, a
failure before the first answer shows one error instead of flickering, and a hung read cannot stop it. Every Admin
card shows load failures as one inline banner with its own Try again, and a failed Stop uses the same alert line as To
do rows. "Scan library now", "Refresh all ratings", "Import from Plex again" and "Sign out" keep focus while they work
(`aria-disabled` and a one-request guard); after a scan starts, focus moves to the card heading and the start is
announced. "Show all" and "Show fewer" report `aria-expanded`, row buttons are described by their film's title, the
To do and Subtitles pages share one wording for subtitle rows, and every "Add my own file…" form shares one read of the
library's subtitle files. The dead filter-chip helpers are removed.
