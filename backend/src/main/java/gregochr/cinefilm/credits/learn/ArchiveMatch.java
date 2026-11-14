package gregochr.cinefilm.credits.learn;

/**
 * An archive item that has a given file, and the version of it that holds that file.
 *
 * @param item the item
 * @param media the item's version whose part is the file
 */
record ArchiveMatch(ArchiveItem item, ArchiveItem.Media media) {
}
