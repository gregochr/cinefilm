package gregochr.cinefilm.credits.learn;

/**
 * One row of the planner's read-only database export: one media file and the film it belongs to.
 *
 * @param mediaFileId the file's id
 * @param path its path relative to the library root
 * @param fingerprint its fingerprint now, {@code size:modified-epoch-ms}
 * @param durationMs the length of its timeline, the one the product's window uses
 * @param editionId its edition
 * @param lastOriginalPart whether it is its edition's last original part; null for a file that is not an original
 * @param filmId its film
 * @param filmTmdbId the film's TMDB id, or null
 * @param filmTitle the film's title
 * @param filmYear the film's release year, or null
 * @param collectionId the film's collection, or null
 * @param collectionTmdbId the collection's TMDB id, or null
 * @param plexStartMs the credits start the Plex import stored for this file
 * @param plexFingerprint the fingerprint the import stored
 * @param presence the file's presence ({@code PRESENT} or not)
 */
record ExportRow(long mediaFileId, String path, String fingerprint, long durationMs, long editionId,
                 Boolean lastOriginalPart, long filmId, Long filmTmdbId, String filmTitle, Integer filmYear,
                 Long collectionId, Long collectionTmdbId, long plexStartMs, String plexFingerprint,
                 String presence) {
}
