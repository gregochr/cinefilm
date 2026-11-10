### Added — The Plex import follows Plex's versions, keeps different films apart and groups in Plex's order

The Plex import now reads every file of every version Plex lists for a film. A version made of several files becomes
one edition that plays as one film, in the order Plex gives; each version is its own edition, named by Plex's edition
title, and two versions with the same name are told apart ("Original", "Original 2"). Where the scan had joined files
differently from Plex, the import separates them and joins them Plex's way, carrying your progress across. A file whose
name is another film's (Rocky V listed under Rocky IV) is no longer folded into the film and is left for its own
match; untagged versions whose runtimes differ by more than 15% are never joined and are noted for you. Films that
share a TMDB id are merged only when their names agree, and films you settled by hand are not regrouped. The film
opens on the first version Plex lists until you choose one yourself, and a resume position from Plex is a position in
the whole film. After the import the library is repaired and grouped like after a scan. Nothing changes in the player
yet; do not release this before playback follows.
