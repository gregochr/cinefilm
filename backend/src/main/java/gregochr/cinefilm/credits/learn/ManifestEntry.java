package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One film's place in the split manifest.
 *
 * @param filmId the film
 * @param unitKey the unit (collection or film) that is never split across sides
 * @param side its side (a {@code SPLIT_CONFLICT} film's own hash side, for reference only)
 * @param fold its cross-validation fold (0 to 4) when on the development side, otherwise null
 */
@JsonPropertyOrder({"film_id", "unit_key", "side", "fold"})
public record ManifestEntry(@JsonProperty("film_id") long filmId, @JsonProperty("unit_key") String unitKey,
                            @JsonProperty("side") Side side, @JsonProperty("fold") Integer fold) {
}
