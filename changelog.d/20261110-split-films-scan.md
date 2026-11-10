### Added — The scan groups split films and editions, and keeps different films apart

A film that comes as several files now joins itself into one film with one edition: the scan reads the `{edition-…}`
tag, a "pt"/"cd"/"part" ending and `{tmdb-…}`/`{imdb-…}` ids from the file name, adds a further file of an
edition it already has without asking TMDB, and at the end of every scan joins the files of one cut into a single
edition (watch progress and your remembered audio and subtitle choices are carried across, and a half-watched first
part resumes at the start of the next). Two cuts of one film in one folder become one film with two editions, and the
film opens on the theatrical or untagged cut, or else the longest, until you pick one. Different films are never
grouped: a file that TMDB matches to a film with a different title (Rocky IV and Rocky V) stays on its own, unmatched,
and a film that already holds files with different titles is split when the evidence is strong, or noted for you when
it is not. Everything the scan splits, joins or flags is logged for the Admin report that comes in a later step.
Nothing changes in the player yet, so a joined film plays only its first file until playback follows; do not release
this before that step.
