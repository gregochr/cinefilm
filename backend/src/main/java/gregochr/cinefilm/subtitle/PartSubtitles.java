package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.SubtitleStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which subtitle stream of a later part is the same track as the one the viewer chose in part 1: the same language
 * and forced flag (the one at the same rank when several qualify), else the stream at the same rank, else none (a
 * subtitle track has no default to fall back on). Pure.
 */
final class PartSubtitles {

    private PartSubtitles() {
    }

    /**
     * The matching stream of another part.
     *
     * @param chosen the stream chosen in part 1; one of {@code first}
     * @param first part 1's subtitle streams
     * @param other the other part's subtitle streams
     * @return the matching stream, or empty when the part has none that match
     */
    static Optional<SubtitleStream> match(SubtitleStream chosen, List<SubtitleStream> first,
                                          List<SubtitleStream> other) {
        List<SubtitleStream> ranked = other.stream().sorted(Comparator.comparingInt(SubtitleStream::getIndex))
            .toList();
        int rank = first.stream().sorted(Comparator.comparingInt(SubtitleStream::getIndex)).toList()
            .indexOf(chosen);
        List<SubtitleStream> same = ranked.stream()
            .filter(s -> sameLanguage(s.getLanguage(), chosen.getLanguage()) && s.isForced() == chosen.isForced())
            .toList();
        if (!same.isEmpty()) {
            return same.stream().filter(s -> ranked.indexOf(s) == rank).findFirst().or(() -> Optional.of(same.get(0)));
        }
        return rank >= 0 && rank < ranked.size() ? Optional.of(ranked.get(rank)) : Optional.empty();
    }

    private static boolean sameLanguage(String a, String b) {
        return a == null ? b == null : b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
