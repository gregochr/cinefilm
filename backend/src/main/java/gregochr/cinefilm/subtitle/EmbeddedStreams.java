package gregochr.cinefilm.subtitle;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which subtitle track inside a film's media file is the full English one to time a forced subtitle against. Pure.
 *
 * <p>A candidate is an English track that is not forced (not flagged, and not titled "forced", "foreign" or "signs").
 * Among the candidates that hold at least {@value #MIN_FULL_CUES} cues (fewer is a forced or signs-only track, not a
 * full one) the choice is, in order: text over picture (no clear-packet ambiguity), a track flagged default or titled
 * as the full track, then the one with the most cues, then the lowest stream index.
 */
final class EmbeddedStreams {

    /** A track with fewer cues than this is not a full English track. */
    static final int MIN_FULL_CUES = 100;

    private static final Set<String> ENGLISH = Set.of("eng", "en");
    private static final Set<String> PICTURE = Set.of("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub",
        "dvb_teletext");
    private static final Pattern NOT_FULL = Pattern.compile("(?i)forced|foreign|\\bsigns?\\b");
    private static final Pattern FULL = Pattern.compile("(?i)\\b(full|sdh|cc|complete|dialogue|dialog)\\b");

    private EmbeddedStreams() {
    }

    /**
     * One subtitle stream of a file.
     *
     * @param index ffprobe's absolute stream index
     * @param codec the codec name, for example {@code subrip} or {@code hdmv_pgs_subtitle} (may be null)
     * @param language the language tag (may be null)
     * @param forced true when the stream is flagged forced
     * @param isDefault true when the stream is flagged default
     * @param title the stream's title (may be null)
     */
    record Stream(int index, String codec, String language, boolean forced, boolean isDefault, String title) {

        /** True when the stream is stored as pictures, not text. */
        boolean picture() {
            return codec == null || PICTURE.contains(codec.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The choice.
     *
     * @param stream the chosen track, or null when there is none
     * @param starts the chosen track's cue starts in milliseconds, or empty
     * @param reason why there is none, or null
     */
    record Choice(Stream stream, List<Long> starts, String reason) {
    }

    /** The English tracks that are not forced, in stream order: the ones worth reading. */
    static List<Stream> candidates(List<Stream> streams) {
        return streams.stream().filter(s -> english(s.language()) && !s.forced()
            && (s.title() == null || !NOT_FULL.matcher(s.title()).find()))
            .sorted(Comparator.comparingInt(Stream::index)).toList();
    }

    /**
     * Chooses the reference track.
     *
     * @param streams every subtitle stream of the file
     * @param startsByIndex the cue starts read for each candidate, by stream index (a missing one counts as empty)
     * @return the choice, with a reason when there is no usable track
     */
    static Choice choose(List<Stream> streams, Map<Integer, List<Long>> startsByIndex) {
        if (streams.isEmpty()) {
            return new Choice(null, List.of(), "The file has no subtitle track");
        }
        List<Stream> candidates = candidates(streams);
        if (candidates.isEmpty()) {
            return new Choice(null, List.of(), "The file has no full English subtitle track ("
                + streams.size() + (streams.size() == 1 ? " subtitle track, not a full English one"
                : " subtitle tracks, none a full English one") + ")");
        }
        Comparator<Stream> best = Comparator.comparing(Stream::picture)
            .thenComparing((Stream s) -> !labelled(s))
            .thenComparing((Stream s) -> -startsByIndex.getOrDefault(s.index(), List.of()).size())
            .thenComparingInt(Stream::index);
        Stream chosen = candidates.stream()
            .filter(s -> startsByIndex.getOrDefault(s.index(), List.of()).size() >= MIN_FULL_CUES)
            .min(best).orElse(null);
        if (chosen == null) {
            return new Choice(null, List.of(), "The English subtitle track has too few lines to be a full track");
        }
        return new Choice(chosen, startsByIndex.get(chosen.index()), null);
    }

    private static boolean labelled(Stream stream) {
        return stream.isDefault() || stream.title() != null && FULL.matcher(stream.title()).find();
    }

    private static boolean english(String language) {
        if (language == null) {
            return false;
        }
        String lower = language.toLowerCase(Locale.ROOT);
        return ENGLISH.contains(lower) || lower.startsWith("en-");
    }
}
