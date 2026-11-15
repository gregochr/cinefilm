package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The OpenSubtitles searches the Admin picker ran, kept for {@value #TTL_MINUTES} minutes so a download can be
 * limited to a file that a search this server ran for the same edition and kind actually returned (the client never
 * names a file the server has not offered). In memory only: a restart forgets them, and the picker searches again. One
 * entry per edition and kind; a new search replaces the old one. An entry is valid up to and including the moment
 * {@value #TTL_MINUTES} minutes after its search, and refused after that.
 */
@Component
class PickerSearches {

    /** How long a search stays valid, in minutes. */
    static final long TTL_MINUTES = 15;
    /** How long a search stays valid. */
    static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);

    /** A file a search offered. */
    record Offered(String imdbId, OpenSubtitlesResult result) {
    }

    private record Key(long editionId, SubtitleKind kind) {
    }

    private record Entry(String imdbId, Instant searchedAt, Map<Long, OpenSubtitlesResult> byFileId) {
        boolean validAt(Instant now) {
            return !now.isAfter(searchedAt.plus(TTL));
        }
    }

    private final Clock clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /** Creates the cache over the application clock. */
    PickerSearches(Clock clock) {
        this.clock = clock;
    }

    /**
     * Remembers what a search offered, replacing the edition's earlier search of the same kind, and forgets every
     * search that has expired.
     *
     * @param editionId the edition searched for
     * @param kind forced or full
     * @param imdbId the IMDb id searched
     * @param results the results offered
     */
    void put(long editionId, SubtitleKind kind, String imdbId, List<OpenSubtitlesResult> results) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.validAt(now));
        Map<Long, OpenSubtitlesResult> byFileId = new HashMap<>();
        results.forEach(r -> byFileId.put(r.fileId(), r));
        entries.put(new Key(editionId, kind), new Entry(imdbId, now, Map.copyOf(byFileId)));
    }

    /**
     * The file, when a search for this edition and kind in the last {@value #TTL_MINUTES} minutes offered it.
     *
     * @param editionId the edition
     * @param kind forced or full
     * @param fileId the OpenSubtitles file id
     * @return the file and the IMDb id it was searched for, or empty
     */
    Optional<Offered> find(long editionId, SubtitleKind kind, long fileId) {
        Entry entry = entries.get(new Key(editionId, kind));
        if (entry == null || !entry.validAt(clock.instant())) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.byFileId().get(fileId)).map(r -> new Offered(entry.imdbId(), r));
    }
}
