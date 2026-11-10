package gregochr.cinefilm.transcode;

import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import java.util.List;
import java.util.Locale;

/**
 * Which audio stream of a later part carries the same track as the one the viewer chose in part 1. The viewer picks
 * from part 1's streams; the other parts of a split film usually have the same streams in the same order, but nothing
 * guarantees it, so the match is by language first (then by rank among those), else by rank, else the part's default.
 * Pure.
 */
final class PartAudio {

    private PartAudio() {
    }

    /**
     * The stream of another part that matches the chosen part-1 stream.
     *
     * @param chosen the stream chosen in part 1
     * @param other the other part's audio streams, not empty
     * @return the part's stream with the chosen one's language (the one at the same rank when several share it, else
     *     the first), else the stream at the chosen one's rank, else the part's default stream, else its first
     */
    static AudioChoice match(AudioChoice chosen, List<AudioChoice> other) {
        List<AudioChoice> sameLanguage = other.stream().filter(a -> sameLanguage(a.language(), chosen.language()))
            .toList();
        List<AudioChoice> pool = sameLanguage.isEmpty() ? other : sameLanguage;
        return pool.stream().filter(a -> a.position() == chosen.position()).findFirst()
            .or(() -> sameLanguage.stream().findFirst())
            .or(() -> other.stream().filter(AudioChoice::isDefault).findFirst())
            .orElse(other.get(0));
    }

    private static boolean sameLanguage(String a, String b) {
        return a != null && b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
