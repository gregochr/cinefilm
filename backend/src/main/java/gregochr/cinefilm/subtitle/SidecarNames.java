package gregochr.cinefilm.subtitle;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What an English subtitle file beside a film is called, classified by the dot-separated tokens just before
 * {@code .srt} (case ignored):
 * <ul>
 *   <li>a {@code forced} token with {@code en} or {@code eng} ({@code X.en.forced.srt}, {@code X.forced.eng.srt}) is a
 *   {@link SubtitleKind#FORCED} subtitle;</li>
 *   <li>otherwise {@code en} or {@code eng}, with at most one of {@code sdh}, {@code hi} or {@code cc} before or after
 *   it ({@code X.en.srt}, {@code X.sdh.en.srt}), is a {@link SubtitleKind#FULL} subtitle;</li>
 *   <li>anything else (another language, no language, no stem) is not a subtitle CineFilm uses.</li>
 * </ul>
 * Only the trailing tokens are read, so a film whose own name contains dots or even {@code .en} keeps it in its stem
 * ({@code Mr. Smith.en.srt} has the stem {@code Mr. Smith}). Pure.
 */
public final class SidecarNames {

    private static final String EXTENSION = ".srt";
    private static final int MAX_TAIL_TOKENS = 3;
    private static final Set<String> LANGUAGES = Set.of("en", "eng");
    private static final Set<String> MODIFIERS = Set.of("sdh", "hi", "cc");
    private static final String FORCED = "forced";

    private SidecarNames() {
    }

    /**
     * A subtitle file's kind and the name of the film it is named after.
     *
     * @param kind forced or full
     * @param stem the path (or file name) without the language tokens and {@code .srt}: the path of the film without
     *     its video extension
     */
    public record Named(SubtitleKind kind, String stem) {
    }

    /**
     * Reads a file name.
     *
     * @param path a path or file name
     * @return the kind and stem, or empty when it is not an English subtitle with a stem in front of its tokens
     */
    public static Optional<Named> classify(String path) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            return Optional.empty();
        }
        String base = path.substring(0, path.length() - EXTENSION.length());
        String[] tokens = base.split("\\.", -1);
        for (int tail = Math.min(MAX_TAIL_TOKENS, tokens.length - 1); tail >= 1; tail--) {
            List<String> window = Arrays.stream(tokens, tokens.length - tail, tokens.length)
                .map(t -> t.toLowerCase(Locale.ROOT)).toList();
            Optional<SubtitleKind> kind = kind(window);
            if (kind.isPresent()) {
                String stem = String.join(".", Arrays.copyOfRange(tokens, 0, tokens.length - tail));
                return stem.isEmpty() ? Optional.empty() : Optional.of(new Named(kind.get(), stem));
            }
        }
        return Optional.empty();
    }

    /** The kind the trailing tokens spell, or empty when they are not exactly a language and its markers. */
    private static Optional<SubtitleKind> kind(List<String> window) {
        long languages = window.stream().filter(LANGUAGES::contains).count();
        long forced = window.stream().filter(FORCED::equals).count();
        long modifiers = window.stream().filter(MODIFIERS::contains).count();
        if (languages != 1 || forced > 1 || modifiers > 1 || languages + forced + modifiers != window.size()) {
            return Optional.empty();
        }
        return Optional.of(forced == 1 ? SubtitleKind.FORCED : SubtitleKind.FULL);
    }
}
