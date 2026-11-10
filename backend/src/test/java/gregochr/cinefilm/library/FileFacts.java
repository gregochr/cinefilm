package gregochr.cinefilm.library;

import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;

/** Builds {@link FileFact}s for the pure grouping tests. */
final class FileFacts {

    private FileFacts() {
    }

    /** A playable, present file of an auto-grouped edition of a matched film, 100 minutes long. */
    static FileFact fact(long film, long edition, long file, String path) {
        return fact(film, "Film " + film, MatchStatus.MATCHED, edition, file, path, GroupingSource.AUTO);
    }

    static FileFact fact(long film, String title, MatchStatus status, long edition, long file, String path,
                         GroupingSource source) {
        return new FileFact(film, title, null, status, edition, "Original", source, file, path, 1, 6_000_000L,
            Presence.PRESENT, Playability.PLAYABLE, FileNameParser.parse(path), false);
    }

    static FileFact withExtraFiles(FileFact fact) {
        return new FileFact(fact.filmId(), fact.filmTitle(), fact.filmTmdbId(), fact.filmStatus(), fact.editionId(),
            fact.editionName(), fact.editionSource(), fact.fileId(), fact.path(), fact.partNumber(),
            fact.durationMs(), fact.presence(), fact.playability(), fact.parsed(), true);
    }

    static FileFact withTmdb(FileFact fact, Long filmTmdbId) {
        return new FileFact(fact.filmId(), fact.filmTitle(), filmTmdbId, fact.filmStatus(), fact.editionId(),
            fact.editionName(), fact.editionSource(), fact.fileId(), fact.path(), fact.partNumber(),
            fact.durationMs(), fact.presence(), fact.playability(), fact.parsed(), fact.extraFiles());
    }

    static FileFact withDuration(FileFact fact, Long durationMs) {
        return new FileFact(fact.filmId(), fact.filmTitle(), fact.filmTmdbId(), fact.filmStatus(), fact.editionId(),
            fact.editionName(), fact.editionSource(), fact.fileId(), fact.path(), fact.partNumber(), durationMs,
            fact.presence(), fact.playability(), fact.parsed(), fact.extraFiles());
    }

    static FileFact withPlayability(FileFact fact, Playability playability) {
        return new FileFact(fact.filmId(), fact.filmTitle(), fact.filmTmdbId(), fact.filmStatus(), fact.editionId(),
            fact.editionName(), fact.editionSource(), fact.fileId(), fact.path(), fact.partNumber(),
            fact.durationMs(), fact.presence(), playability, fact.parsed(), fact.extraFiles());
    }

    static FileFact withPresence(FileFact fact, Presence presence) {
        return new FileFact(fact.filmId(), fact.filmTitle(), fact.filmTmdbId(), fact.filmStatus(), fact.editionId(),
            fact.editionName(), fact.editionSource(), fact.fileId(), fact.path(), fact.partNumber(),
            fact.durationMs(), presence, fact.playability(), fact.parsed(), fact.extraFiles());
    }
}
