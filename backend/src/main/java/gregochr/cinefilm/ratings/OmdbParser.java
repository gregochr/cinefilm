package gregochr.cinefilm.ratings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an OMDb reply into ratings. Pure: no Spring, no I/O. OMDb writes a missing value as {@code "N/A"}; that, a
 * blank, a missing key and anything that does not parse are all "no value" (null), never an error.
 */
public final class OmdbParser {

    static final int URL_MAX = 512;
    private static final String NOT_AVAILABLE = "N/A";
    private static final String ROTTEN_TOMATOES = "Rotten Tomatoes";
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final int PERCENT_MAX = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OmdbParser() {
    }

    /**
     * Reads an OMDb reply body.
     *
     * @param json the body of {@code GET /?i=...&tomatoes=true}
     * @return found with the ratings, not found when {@code Response} is {@code "False"}, or limit reached when the
     *     error says the request limit is spent
     * @throws OmdbUnavailableException when the body is not JSON
     */
    public static OmdbResult parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new OmdbUnavailableException("OMDb returned unreadable JSON");
        }
        if (!root.isObject()) {
            throw new OmdbUnavailableException("OMDb returned an unexpected reply");
        }
        if ("False".equalsIgnoreCase(text(root, "Response"))) {
            String error = text(root, "Error");
            boolean limit = error != null && error.toLowerCase(Locale.ROOT).contains("request limit");
            return limit ? OmdbResult.limitReached() : OmdbResult.notFound();
        }
        return OmdbResult.found(new OmdbRatings(imdbRating(root), imdbVotes(root), rottenTomatoesScore(root),
            rottenTomatoesUrl(root)));
    }

    private static BigDecimal imdbRating(JsonNode root) {
        String value = text(root, "imdbRating");
        if (value == null) {
            return null;
        }
        try {
            BigDecimal rating = new BigDecimal(value).setScale(1, RoundingMode.HALF_UP);
            return rating.signum() < 0 || rating.compareTo(TEN) > 0 ? null : rating;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer imdbVotes(JsonNode root) {
        String value = text(root, "imdbVotes");
        if (value == null) {
            return null;
        }
        try {
            int votes = Integer.parseInt(value.replace(",", ""));
            return votes < 0 ? null : votes;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer rottenTomatoesScore(JsonNode root) {
        for (JsonNode rating : root.path("Ratings")) {
            if (!ROTTEN_TOMATOES.equals(text(rating, "Source"))) {
                continue;
            }
            String value = text(rating, "Value");
            if (value == null || !value.endsWith("%")) {
                return null;
            }
            try {
                int percent = Integer.parseInt(value.substring(0, value.length() - 1).strip());
                return percent < 0 || percent > PERCENT_MAX ? null : percent;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String rottenTomatoesUrl(JsonNode root) {
        String value = text(root, "tomatoURL");
        if (value == null || value.length() > URL_MAX) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return web && uri.getHost() != null ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A string value, trimmed; null when the key is missing, not a string, blank or {@code "N/A"}. */
    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() || NOT_AVAILABLE.equalsIgnoreCase(text) ? null : text;
    }
}
