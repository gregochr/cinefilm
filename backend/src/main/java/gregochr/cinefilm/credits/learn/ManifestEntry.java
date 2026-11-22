package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One film's place in the split manifest.
 *
 * @param filmId the film
 * @param unitKey the unit (collection or film) that is never split across sides
 * @param side its side (a {@code SPLIT_CONFLICT} film's own hash side, for reference only)
 * @param fold its cross-validation fold (0 to 4) when on the development side, otherwise null
 * @param lookedAt true when the film is on the test side and was looked at by credits 3c, so its files are excluded
 *     as {@code LOOKED_AT}: {@code side} is then the side it would have had anyway, which the deny file and later
 *     runs follow. Null (and left out of the JSON) for every other film, so an older manifest reads unchanged
 */
@JsonPropertyOrder({"film_id", "unit_key", "side", "fold", "looked_at"})
public record ManifestEntry(@JsonProperty("film_id") long filmId, @JsonProperty("unit_key") String unitKey,
                            @JsonProperty("side") Side side, @JsonProperty("fold") Integer fold,
                            @JsonProperty("looked_at") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean lookedAt) {

    /** An entry of a film that was not looked at. */
    public ManifestEntry(long filmId, String unitKey, Side side, Integer fold) {
        this(filmId, unitKey, side, fold, null);
    }

    /** Whether the film is on the test side and was looked at. */
    boolean isLookedAt() {
        return Boolean.TRUE.equals(lookedAt);
    }
}
