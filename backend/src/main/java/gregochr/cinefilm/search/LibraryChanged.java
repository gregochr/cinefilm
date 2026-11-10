package gregochr.cinefilm.search;

/**
 * Published when something the search index shows changed outside a scan or an import: a playback write, or a film
 * regrouped by hand. The index cache drops its memo once the writing transaction has committed.
 */
public record LibraryChanged() {
}
