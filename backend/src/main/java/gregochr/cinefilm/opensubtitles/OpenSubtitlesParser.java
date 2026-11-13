package gregochr.cinefilm.opensubtitles;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a subtitle search reply into what CineFilm keeps. The reply is untrusted: every field may be missing or the
 * wrong type, and text is cut and cleaned. A result is dropped when it is not "foreign parts only" (for the full
 * subtitles search, when it is), is a machine or AI
 * translation, belongs to another film (its {@code feature_details.imdb_id} is not the one asked for), or has no usable
 * id or file id. Pure: no Spring, no I/O.
 */
public final class OpenSubtitlesParser {

    static final int RELEASE_MAX = 200;
    static final int SHORT_TEXT_MAX = 40;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OpenSubtitlesParser() {
    }

    /**
     * Reads a reply body.
     *
     * @param json the body of {@code GET /api/v1/subtitles}
     * @param imdbNumber the IMDb id that was asked for, as a number (without {@code tt} or leading zeros)
     * @return how many results remain, the best of them and the candidates to compare with
     * @throws OpenSubtitlesUnavailableException when the body is not JSON or has no {@code data} list: an answer
     *     that cannot be read is never "no results"
     */
    public static OpenSubtitlesSearch parse(String json, long imdbNumber) {
        return parse(json, imdbNumber, false);
    }

    /**
     * Reads a reply to a search for full English subtitles: the same rules, except that a result is kept only when it
     * is not foreign-parts-only (those are the forced subtitles' search), hearing-impaired files are kept and marked
     * ({@link OpenSubtitlesResult#hearingImpaired}), and the candidates are ranked with hearing-impaired files last.
     *
     * @param json the body of {@code GET /api/v1/subtitles}
     * @param imdbNumber the IMDb id that was asked for, as a number (without {@code tt} or leading zeros)
     * @return how many results remain, the best of them and the candidates a download may use
     * @throws OpenSubtitlesUnavailableException when the body is not JSON or has no {@code data} list
     */
    public static OpenSubtitlesSearch parseFull(String json, long imdbNumber) {
        return parse(json, imdbNumber, true);
    }

    private static OpenSubtitlesSearch parse(String json, long imdbNumber, boolean full) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles returned unreadable JSON");
        }
        if (!root.isObject() || !root.path("data").isArray()) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles returned an unexpected reply");
        }
        List<OpenSubtitlesResult> kept = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            OpenSubtitlesResult result = result(item, imdbNumber, full);
            if (result != null) {
                kept.add(result);
            }
        }
        if (full) {
            List<OpenSubtitlesResult> candidates = OpenSubtitlesRanking.fullCandidates(kept);
            return new OpenSubtitlesSearch(kept.size(), candidates.isEmpty() ? null : candidates.get(0), candidates);
        }
        return new OpenSubtitlesSearch(kept.size(), OpenSubtitlesRanking.best(kept).orElse(null),
            OpenSubtitlesRanking.candidates(kept));
    }

    private static OpenSubtitlesResult result(JsonNode item, long imdbNumber, boolean full) {
        JsonNode attributes = item.path("attributes");
        if (!foreignPartsFlagFits(attributes.path("foreign_parts_only"), full) || flag(attributes, "ai_translated")
            || flag(attributes, "machine_translated")) {
            return null;
        }
        JsonNode imdb = attributes.path("feature_details").path("imdb_id");
        if (!imdb.isIntegralNumber() || imdb.asLong() != imdbNumber) {
            return null;
        }
        Long id = wholeNumber(item.path("id"));
        Long fileId = wholeNumber(attributes.path("files").path(0).path("file_id"));
        if (id == null || fileId == null) {
            return null;
        }
        return new OpenSubtitlesResult(id, fileId, text(attributes, "release", RELEASE_MAX), fps(attributes),
            downloadCount(attributes), flag(attributes, "from_trusted"), flag(attributes, "hearing_impaired"),
            text(attributes, "upload_date", SHORT_TEXT_MAX),
            text(attributes.path("uploader"), "rank", SHORT_TEXT_MAX), fileCount(attributes),
            text(attributes.path("files").path(0), "file_name", RELEASE_MAX));
    }

    /**
     * True when the {@code foreign_parts_only} flag is the one the search wants: a real {@code true} for the forced
     * search, a real {@code false} for the full one. A missing or non-boolean flag fits neither.
     */
    private static boolean foreignPartsFlagFits(JsonNode flag, boolean full) {
        return flag.isBoolean() && flag.asBoolean() != full;
    }

    /** The larger of {@code nb_cd} and how many files are listed, at least 1 and at most 1,000. */
    private static int fileCount(JsonNode attributes) {
        long discs = attributes.path("nb_cd").isIntegralNumber() ? attributes.path("nb_cd").asLong() : 1;
        long listed = attributes.path("files").size();
        return (int) Math.max(1, Math.min(1000, Math.max(discs, listed)));
    }

    private static boolean flag(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isBoolean() && value.asBoolean();
    }

    /** A positive whole number, written as a JSON number or (as OpenSubtitles writes ids) a string of digits. */
    private static Long wholeNumber(JsonNode node) {
        Long number = null;
        if (node.isIntegralNumber()) {
            number = node.asLong();
        } else if (node.isString()) {
            try {
                number = Long.valueOf(node.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return number != null && number > 0 ? number : null;
    }

    private static Double fps(JsonNode attributes) {
        JsonNode value = attributes.path("fps");
        if (!value.isNumber()) {
            return null;
        }
        double fps = value.asDouble();
        return Double.isFinite(fps) && fps > 0 ? fps : null;
    }

    private static int downloadCount(JsonNode attributes) {
        JsonNode value = attributes.path("download_count");
        return value.isIntegralNumber() ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, value.asLong())) : 0;
    }

    /** A string field with control characters made spaces and cut to {@code max} characters; null when not text. */
    private static String text(JsonNode node, String name, int max) {
        JsonNode value = node.path(name);
        if (!value.isString()) {
            return null;
        }
        String clean = value.asString().replaceAll("\\p{Cc}", " ").strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() <= max) {
            return clean;
        }
        // Never end on half of a surrogate pair.
        int end = Character.isHighSurrogate(clean.charAt(max - 1)) ? max - 1 : max;
        return clean.substring(0, end);
    }
}
