### Added — Forced English subtitles from the `.srt` files beside your films

A film with a `<name>.en.forced.srt` (or `.forced.en.srt`) file beside it now shows those lines by itself when Subtitles
is Off, as the foreign-language translations a cinema audience saw. Every scan finds the files (new, changed, missing or
back again), reads them whatever their encoding (UTF-8, UTF-16, Windows-1252), line endings or numbering, and matches
each to its film by name, whether or not the film changed; a file named for a split film's whole edition is timed for the
whole film, one named for a part for that part. The player turns the subtitle on for the edition, hides it when you choose
a full track, and the captions button and the C key work for a film that has only a sidecar.

Admin gains a Subtitles panel: the files grouped as Unmatched, Conflicts, Missing and Matched, each with how it was read
(for example "3 blocks skipped, read as Windows-1252"), a warning when it ends after the film, "Attach to…" for a file
no name matched (the Lord of the Rings theatrical files need no renaming), the cue list with "Play from here" (the player
opens 3 seconds before the cue, through a new `at` value in the address), nudges of 0.25 s and 1 s, "Reset timing" and
"Looks right". A check stops counting when the edition's files change. New routes: `GET /api/editions/{id}/forced-subtitles.vtt`
and `/api/admin/subtitles` (list, cues, attach, detach, offset, verify); the film detail's editions carry
`forcedSubtitle`. One expand-only migration (V13, the `subtitle_sidecar` table).
