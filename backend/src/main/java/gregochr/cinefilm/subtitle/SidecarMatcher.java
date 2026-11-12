package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides which film file a forced subtitle file belongs to, by exact name only (owner decision, 2026-11-11). The
 * subtitle's path without its {@code .en.forced.srt} or {@code .forced.en.srt} suffix must equal a film file's path
 * without its video extension, ignoring case, in the same folder: {@code Taken.en.forced.srt} belongs to
 * {@code Taken.m4v}, {@code X - pt1.en.forced.srt} to {@code X - pt1.m4v}. Several files that match (the same stem
 * as {@code .m4v} and {@code .mkv}) are ambiguous, so none is chosen. Everything else (a whole-edition file for a
 * split edition, a name without its edition tag, a near-miss) is left unmatched for the owner to attach in Admin.
 * Matching never marks a subtitle as timed for a whole edition: only an owner's attach does. Pure.
 */
final class SidecarMatcher {

    private static final Pattern VIDEO_EXTENSION = Pattern.compile("\\.(?:m4v|mp4|mov|avi|mkv)$",
        Pattern.CASE_INSENSITIVE);

    /** A film file the subtitle may belong to. */
    record FileRef(Long id, String path) {
    }

    /** The match: the film file. */
    record Match(Long mediaFileId) {
    }

    private final Map<String, List<Long>> byName = new HashMap<>();

    /** Reads every film file's name once. */
    SidecarMatcher(List<FileRef> files) {
        for (FileRef file : files) {
            String name = VIDEO_EXTENSION.matcher(file.path()).replaceFirst("").toLowerCase(Locale.ROOT);
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(file.id());
        }
    }

    /**
     * The film file a subtitle belongs to.
     *
     * @param sidecarPath the subtitle's path relative to the library root
     * @return the match, or empty when no film file, or more than one, has exactly that name
     */
    Optional<Match> match(String sidecarPath) {
        List<Long> ids = byName.getOrDefault(SidecarNames.stem(sidecarPath).toLowerCase(Locale.ROOT), List.of());
        return ids.size() == 1 ? Optional.of(new Match(ids.get(0))) : Optional.empty();
    }
}
