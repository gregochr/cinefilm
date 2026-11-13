package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * The split manifest, {@code split-<date>.json}.
 *
 * @param date the run date
 * @param films every exported film, excluded ones included, by film id
 * @param looks each use of the test split, copied unchanged from the previous manifest
 */
@JsonPropertyOrder({"date", "films", "looks"})
public record SplitManifest(@JsonProperty("date") String date, @JsonProperty("films") List<ManifestEntry> films,
                            @JsonProperty("looks") List<Object> looks) {
}
